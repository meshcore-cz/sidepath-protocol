// BLEEdge mesh core for microcontrollers — wire-compatible with core/ (Go) and
// the Android Kotlin port. Pure logic, no BLE/Arduino BLE dependencies, so it can
// be unit-tested on the host. See core/fragment.go and core/packet.go for the
// reference implementation.
#pragma once

#include <Arduino.h>
#include <vector>

namespace mesh {

constexpr size_t NODE_ID_LEN   = 8;
constexpr size_t PACKET_ID_LEN = 16;
constexpr size_t FRAME_HEADER  = 23;  // version(1)+id(16)+fragIdx(1)+fragCount(1)+crc32(4)
constexpr size_t PUBKEY_LEN    = 32;  // Ed25519 public key
constexpr size_t SIG_LEN       = 64;  // Ed25519 signature
constexpr uint8_t PROTOCOL_VERSION = 2;  // v2 = Ed25519 identities + signed ANNOUNCE

// CBOR packet map keys (see core/packet.go).
enum : uint8_t {
  KEY_VERSION = 1, KEY_TYPE = 2, KEY_ID = 3, KEY_SOURCE = 4, KEY_DEST = 5,
  KEY_MODE = 6, KEY_TTL = 7, KEY_ROUTE_CURSOR = 8, KEY_ROUTE = 9, KEY_TRACE = 10,
  KEY_PAYLOAD_TYPE = 11, KEY_PAYLOAD = 12, KEY_SEQ = 13, KEY_TRACE_METRIC = 14,
};

enum : uint8_t { TYPE_DATA = 1, TYPE_ANNOUNCE = 2, TYPE_ACK = 3 };
enum : uint8_t { MODE_FLOOD = 1, MODE_SOURCE_ROUTE = 2 };
enum : uint8_t {
  PAYLOAD_TEXT = 1, PAYLOAD_MESH_CORE_RAW = 2, PAYLOAD_CHAT_PLAIN = 3,
  PAYLOAD_CHAT_ENCRYPTED = 4, PAYLOAD_CHANNEL = 5, PAYLOAD_TRACE_REQUEST = 6,
  PAYLOAD_TRACE_RESPONSE = 7,
};

// Capability bits (see core/types.go).
enum : uint8_t {
  CAP_SENDER = 0x01, CAP_RECEIVER = 0x02, CAP_RELAY = 0x04,
  CAP_GATEWAY = 0x08, CAP_CODED_PHY = 0x10,
};

uint32_t crc32_ieee(const uint8_t* data, size_t len);

// ---- DedupCache: recently-seen PacketIDs ------------------------------------
class DedupCache {
 public:
  explicit DedupCache(size_t capacity = 128, uint32_t ttlMs = 5UL * 60 * 1000);
  // Returns true if id was already seen (within TTL). Otherwise records it.
  bool seenOrAdd(const uint8_t id[PACKET_ID_LEN]);

 private:
  struct Entry { uint8_t id[PACKET_ID_LEN]; uint32_t seenMs; bool used; };
  std::vector<Entry> entries_;
  uint32_t ttlMs_;
  size_t next_;
};

// ---- Reassembler: collects GATT frames into a packet ------------------------
class Reassembler {
 public:
  explicit Reassembler(uint32_t timeoutMs = 10000) : timeoutMs_(timeoutMs) {}
  // Feed one raw GATT frame. On the frame that completes a packet (CRC valid),
  // fills `out` with the packet bytes and returns true. Otherwise returns false.
  bool addFrame(const uint8_t* raw, size_t len, std::vector<uint8_t>& out);
  void reap();

 private:
  struct Assembly {
    bool used = false;
    uint8_t id[PACKET_ID_LEN];
    uint8_t count = 0;
    uint32_t crc = 0;
    uint32_t lastMs = 0;
    std::vector<std::vector<uint8_t>> frags;  // size == count
    std::vector<bool> have;
  };
  static constexpr size_t MAX_PENDING = 4;
  Assembly slots_[MAX_PENDING];
  uint32_t timeoutMs_;
};

// ---- Packet inspection / relay transcode ------------------------------------
struct PacketHeader {
  bool ok = false;
  uint8_t id[PACKET_ID_LEN];
  uint8_t type = 0;
  uint8_t mode = 0;
  uint8_t ttl = 0;
  uint8_t payloadType = 0;
  uint8_t routeCursor = 0;
  bool traceContainsSelf = false;
  uint8_t source[NODE_ID_LEN];   // packet origin (key 4)
  bool hasSource = false;
  uint8_t destination[NODE_ID_LEN];  // packet destination (key 5)
  bool hasDestination = false;
  uint8_t lastHop[NODE_ID_LEN];  // last trace entry — the peer that sent us this frame
  bool hasLastHop = false;
  uint8_t nextHop[NODE_ID_LEN];  // route[route_cursor + 1] after this relay
  bool hasNextHop = false;
  bool routeCurrentIsSelf = false;
  bool routeEndsHere = false;
  const uint8_t* payload = nullptr;  // byte string content for key 12
  size_t payloadLen = 0;
};

// The directly-connected peer that sent us a packet: the last trace hop, or the
// source if the trace is empty (a freshly-originated packet from a direct neighbor).
// Writes 8 bytes to `out` and returns true if known.
bool directNeighbor(const PacketHeader& h, uint8_t out[NODE_ID_LEN]);

// Parses the fields a relay needs and checks whether `selfId` is already in the
// trace (loop). Does not allocate. Returns false on malformed input.
PacketHeader parseHeader(const uint8_t* pkt, size_t len, const uint8_t selfId[NODE_ID_LEN]);

// Produces a forwardable copy of `pkt`: every CBOR map entry copied verbatim
// except TTL (rewritten to `newTtl`) and Trace (with `selfId` appended).
// Returns false on malformed input.
bool buildForward(const uint8_t* pkt, size_t len, const uint8_t selfId[NODE_ID_LEN],
                  uint8_t newTtl, std::vector<uint8_t>& out);

// Produces a forwardable TRACE source-route packet. Requires parseHeader() to
// have confirmed routeCurrentIsSelf and hasNextHop. Rewrites TTL, route_cursor,
// trace, and trace_metric; copies all other fields verbatim.
bool buildTraceSourceRouteForward(const uint8_t* pkt, size_t len,
                                  const uint8_t selfId[NODE_ID_LEN],
                                  uint8_t newTtl, uint8_t newRouteCursor,
                                  int8_t metric, std::vector<uint8_t>& out);

// Builds the canonical byte string that an ANNOUNCE signature covers. Fixed
// explicit layout (must match core.AnnounceSignedMessage in Go and the Kotlin
// port): pubkey[32] | timestamp[4 LE] | caps[1] | seq[4 LE] | count[1] |
// neighbors[count*8]. No crypto here — the caller signs the result.
void announceSignedMessage(const uint8_t pubKey[PUBKEY_LEN], uint32_t timestamp,
                           uint8_t caps, uint32_t seq,
                           const uint8_t* neighbors, size_t neighborCount,
                           std::vector<uint8_t>& out);

// Builds a complete signed ANNOUNCE packet (CBOR) this node originates. `selfId`
// must equal pubKey[:8]. `signature` is the Ed25519 signature over
// announceSignedMessage(...), produced by the caller. `packetId` is the
// caller-generated 16-byte ID (also used for fragmentation and dedup marking).
// `neighbors` is a contiguous array of `neighborCount` 8-byte NodeIDs.
void buildAnnounce(const uint8_t selfId[NODE_ID_LEN], uint8_t caps, uint32_t seq,
                   int64_t unixSeconds, const uint8_t packetId[PACKET_ID_LEN],
                   const uint8_t* neighbors, size_t neighborCount,
                   const uint8_t pubKey[PUBKEY_LEN], const uint8_t signature[SIG_LEN],
                   const char* description, std::vector<uint8_t>& out);

// Splits packet bytes into GATT frames (matches core.FragmentPacket).
void fragment(const uint8_t* pkt, size_t len, size_t mtu, const uint8_t packetId[PACKET_ID_LEN],
              std::vector<std::vector<uint8_t>>& frames);

}  // namespace mesh
