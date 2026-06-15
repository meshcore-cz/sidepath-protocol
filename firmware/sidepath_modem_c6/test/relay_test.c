// Host unit test for the connectionless relay logic (relay.c). Builds with a
// plain C compiler — no ESP-IDF needed — since relay.c has no platform deps.
//
//   cc -I../main relay_test.c ../main/relay.c -o relay_test && ./relay_test

#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "frame.h"
#include "relay.h"

static int failures;
#define CHECK(cond, msg)                              \
    do {                                              \
        if (!(cond)) {                                \
            printf("FAIL: %s\n", msg);                \
            failures++;                               \
        }                                             \
    } while (0)

static void test_hash_excludes_ttl(void) {
    // The dedup hash is over content only, so a decremented TTL must not change
    // the identity of a packet.
    uint8_t content[] = {0xde, 0xad, 0xbe, 0xef};
    uint32_t h1 = sp_hash(content, sizeof(content));
    uint32_t h2 = sp_hash(content, sizeof(content));
    CHECK(h1 == h2, "hash is stable for identical content");

    uint8_t other[] = {0xde, 0xad, 0xbe, 0xee};
    CHECK(sp_hash(other, sizeof(other)) != h1, "different content -> different hash");
}

static void test_forward_then_dup(void) {
    sp_relay_t r;
    sp_relay_init(&r);
    uint32_t h = 0x12345678;
    uint8_t out = 0;

    sp_relay_action_t a = sp_relay_consider(&r, h, 8, 1000, &out);
    CHECK(a == SP_RELAY_FORWARD, "first sighting forwards");
    CHECK(out == 7, "ttl is decremented on forward");
    CHECK(r.relayed == 1, "relayed counter incremented");

    a = sp_relay_consider(&r, h, 7, 1100, &out);
    CHECK(a == SP_RELAY_DUP, "same content (lower ttl) is a duplicate");
    CHECK(r.dup == 1, "dup counter incremented");
}

static void test_ttl_zero_dropped(void) {
    sp_relay_t r;
    sp_relay_init(&r);
    uint8_t out = 0;
    sp_relay_action_t a = sp_relay_consider(&r, 0xabcdef01, 0, 1000, &out);
    CHECK(a == SP_RELAY_DEAD, "ttl=0 is dropped");
    CHECK(r.ttl_drop == 1, "ttl_drop counter incremented");
    // A dead packet is not marked seen (it never propagated), so a fresh copy
    // with TTL>0 should still forward.
    a = sp_relay_consider(&r, 0xabcdef01, 5, 1100, &out);
    CHECK(a == SP_RELAY_FORWARD, "same hash with ttl>0 still forwards after a dead drop");
}

static void test_expiry(void) {
    sp_relay_t r;
    sp_relay_init(&r);
    uint8_t out = 0;
    sp_relay_consider(&r, 0x55, 4, 1000, &out); // seen until 1000+SP_SEEN_TTL_MS
    CHECK(sp_relay_seen(&r, 0x55, 1000), "marked seen immediately");
    CHECK(!sp_relay_seen(&r, 0x55, 1000 + SP_SEEN_TTL_MS + 1), "expires after the TTL window");

    // After expiry the same hash forwards again.
    sp_relay_action_t a = sp_relay_consider(&r, 0x55, 4, 1000 + SP_SEEN_TTL_MS + 2, &out);
    CHECK(a == SP_RELAY_FORWARD, "re-forwards once the cache entry expired");
}

static void test_capacity_eviction(void) {
    sp_relay_t r;
    sp_relay_init(&r);
    uint8_t out = 0;
    // Fill well past capacity; the cache must keep accepting without crashing
    // and recent entries must remain seen.
    for (uint32_t i = 1; i <= SP_SEEN_CAP * 2; i++) {
        sp_relay_consider(&r, 0x1000 + i, 4, 2000 + i, &out);
    }
    CHECK(r.seen <= SP_SEEN_CAP, "live count never exceeds capacity");
    CHECK(sp_relay_seen(&r, 0x1000 + SP_SEEN_CAP * 2, 2000 + SP_SEEN_CAP * 2),
          "most recent hash is retained");
}

int main(void) {
    test_hash_excludes_ttl();
    test_forward_then_dup();
    test_ttl_zero_dropped();
    test_expiry();
    test_capacity_eviction();
    if (failures == 0) {
        printf("relay_test: all tests passed\n");
        return 0;
    }
    printf("relay_test: %d failure(s)\n", failures);
    return 1;
}
