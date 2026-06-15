// Over-the-air Sidepath connectionless frame format and shared constants.
//
// The modem is a dumb pipe: the host hands it an *opaque* Sidepath packet and
// the modem floods it over BLE advertisements. The only structure the modem
// imposes is a 1-byte hop-limit (TTL) in front of the opaque content, so it can
// implement the connectionless relay rules (drop on TTL=0, decrement, dedup)
// without ever parsing the Sidepath datagram itself.
//
// Wire layout of the BLE Manufacturer-Specific-Data AD structure we emit/scan:
//
//   AD: len | 0xFF (Manufacturer Specific) | company_id (LE) | version | ttl | content...
//                                            ^^^^^^^^^^^^^^^^   ^^^^^^^   ^^^   ^^^^^^^^^^^
//   company_id = SP_COMPANY_ID (0x5053, "SP")
//   version    = SP_FRAME_VERSION
//   ttl        = remaining hop count (0 => dead, never re-advertised)
//   content    = opaque host bytes ("the packet" as far as the host is concerned)
//
// The "packet" exchanged with the host over serial (SEND/RX) is `ttl || content`
// — the modem prepends the company id + version when transmitting and strips
// them when receiving. Dedup is by a hash over `content` only (TTL is excluded
// so the same packet is recognised as a duplicate after its TTL has been
// decremented by an upstream relay).

#pragma once

#include <stddef.h>
#include <stdint.h>

// Manufacturer "company identifier" tagging our advertisements. 0x5053 spells
// "SP" (little-endian on the wire). Not an assigned Bluetooth SIG id — fine for
// a closed protocol, and distinct enough to filter foreign advertisers cheaply.
#define SP_COMPANY_ID 0x5053u

// Frame format version, bumped if the OTA layout changes incompatibly.
#define SP_FRAME_VERSION 0x01u

// Manufacturer-data overhead before the opaque content: company id (2) +
// version (1) + ttl (1).
#define SP_MFG_OVERHEAD 4

// Largest opaque content we accept from the host or off the air. Extended
// advertising carries far more than legacy 31-byte adv; 229 keeps a single
// ext-adv PDU comfortably (251 max AD payload - AD header - mfg overhead).
#define SP_MAX_CONTENT 229

// Default TTL applied when the host's SEND packet omits a leading TTL byte
// (i.e. sends bare content). Originating hosts normally supply their own.
#define SP_DEFAULT_TTL 8

// FNV-1a 32-bit hash, used as the dedup key over a frame's opaque content.
static inline uint32_t sp_hash(const uint8_t *data, size_t len) {
    uint32_t h = 0x811c9dc5u;
    for (size_t i = 0; i < len; i++) {
        h ^= data[i];
        h *= 0x01000193u;
    }
    return h;
}
