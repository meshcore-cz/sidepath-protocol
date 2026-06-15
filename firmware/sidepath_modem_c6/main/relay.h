// Connectionless relay engine: a small expiring "seen" cache plus the
// scan -> dedup -> decrement -> re-advertise decision. Pure logic, no BLE deps,
// so it is unit-testable on the host.

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// Capacity of the in-memory seen-cache. Each slot is ~12 bytes; 256 entries is
// plenty for a relay and bounded for a small device.
#define SP_SEEN_CAP 256

// How long (ms) a content hash stays "seen". Long enough to suppress the flood
// echo of a packet, short enough that the cache turns over.
#define SP_SEEN_TTL_MS 30000

typedef struct {
    uint32_t hash;       // FNV-1a of the frame content (0 slot => empty)
    uint32_t expires_ms; // tick (ms) after which the entry is reusable
} sp_seen_entry_t;

typedef struct {
    sp_seen_entry_t entries[SP_SEEN_CAP];
    // Counters surfaced by the STATS serial command.
    uint32_t rx;       // frames received off the air (well-formed)
    uint32_t tx;       // frames advertised on host SEND
    uint32_t relayed;  // frames re-advertised by the relay
    uint32_t dup;      // frames dropped as duplicates
    uint32_t ttl_drop; // frames dropped for TTL exhaustion
    uint32_t seen;     // distinct hashes currently held
} sp_relay_t;

// Outcome of feeding a received frame to the relay engine.
typedef enum {
    SP_RELAY_FORWARD, // not seen, TTL>0: caller should re-advertise the (decremented) frame
    SP_RELAY_DUP,     // already seen recently: drop
    SP_RELAY_DEAD,    // TTL reached 0: drop
} sp_relay_action_t;

void sp_relay_init(sp_relay_t *r);

// sp_relay_seen reports whether `hash` is in the cache (and refreshes nothing).
bool sp_relay_seen(sp_relay_t *r, uint32_t hash, uint32_t now_ms);

// sp_relay_mark records `hash` as seen until now_ms + SP_SEEN_TTL_MS.
void sp_relay_mark(sp_relay_t *r, uint32_t hash, uint32_t now_ms);

// sp_relay_consider decides what to do with a received frame whose opaque
// content hashes to `hash` and whose hop-limit is `ttl`. On SP_RELAY_FORWARD it
// marks the hash seen and writes the decremented TTL to *out_ttl. It does not
// touch the rx counter (the caller owns that, since malformed frames never
// reach here).
sp_relay_action_t sp_relay_consider(sp_relay_t *r, uint32_t hash, uint8_t ttl,
                                    uint32_t now_ms, uint8_t *out_ttl);
