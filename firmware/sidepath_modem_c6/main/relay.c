#include "relay.h"

#include <string.h>

void sp_relay_init(sp_relay_t *r) {
    memset(r, 0, sizeof(*r));
}

// recount refreshes the live-entry count after an expiry sweep.
static void recount(sp_relay_t *r, uint32_t now_ms) {
    uint32_t live = 0;
    for (size_t i = 0; i < SP_SEEN_CAP; i++) {
        sp_seen_entry_t *e = &r->entries[i];
        if (e->hash == 0) {
            continue;
        }
        // Treat expired slots as free (signed compare survives tick wrap).
        if ((int32_t)(now_ms - e->expires_ms) >= 0) {
            e->hash = 0;
            continue;
        }
        live++;
    }
    r->seen = live;
}

bool sp_relay_seen(sp_relay_t *r, uint32_t hash, uint32_t now_ms) {
    if (hash == 0) {
        hash = 1; // 0 marks an empty slot; fold it so it is still cacheable
    }
    for (size_t i = 0; i < SP_SEEN_CAP; i++) {
        sp_seen_entry_t *e = &r->entries[i];
        if (e->hash == hash && (int32_t)(now_ms - e->expires_ms) < 0) {
            return true;
        }
    }
    return false;
}

void sp_relay_mark(sp_relay_t *r, uint32_t hash, uint32_t now_ms) {
    if (hash == 0) {
        hash = 1;
    }
    // Prefer reusing the matching or an expired/empty slot; otherwise evict the
    // soonest-to-expire entry so the cache always accepts a fresh hash.
    sp_seen_entry_t *victim = NULL;
    uint32_t victim_exp = 0;
    for (size_t i = 0; i < SP_SEEN_CAP; i++) {
        sp_seen_entry_t *e = &r->entries[i];
        if (e->hash == hash) {
            victim = e;
            break;
        }
        bool free_slot = (e->hash == 0) || ((int32_t)(now_ms - e->expires_ms) >= 0);
        if (free_slot) {
            victim = e;
            break;
        }
        if (victim == NULL || (int32_t)(e->expires_ms - victim_exp) < 0) {
            victim = e;
            victim_exp = e->expires_ms;
        }
    }
    victim->hash = hash;
    victim->expires_ms = now_ms + SP_SEEN_TTL_MS;
    recount(r, now_ms);
}

sp_relay_action_t sp_relay_consider(sp_relay_t *r, uint32_t hash, uint8_t ttl,
                                    uint32_t now_ms, uint8_t *out_ttl) {
    if (sp_relay_seen(r, hash, now_ms)) {
        r->dup++;
        return SP_RELAY_DUP;
    }
    if (ttl == 0) {
        r->ttl_drop++;
        return SP_RELAY_DEAD;
    }
    sp_relay_mark(r, hash, now_ms);
    *out_ttl = (uint8_t)(ttl - 1);
    r->relayed++;
    return SP_RELAY_FORWARD;
}
