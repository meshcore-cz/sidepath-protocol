// Sidepath v3 mesh core for microcontrollers. Pure wire logic, no NimBLE
// dependency, so it can be host-tested against the Go implementation.
#pragma once

#include <Arduino.h>
#include <array>
#include <vector>

namespace mesh {

constexpr size_t NODE_ID_LEN     = 10;
constexpr size_t DATAGRAM_ID_LEN = 16;
constexpr size_t TRANSFER_ID_LEN = 16;
constexpr size_t FRAME_HEADER    = 23;
constexpr size_t PUBKEY_LEN      = 32;
constexpr size_t SIG_LEN         = 64;

constexpr uint8_t FRAME_VERSION     = 2;
constexpr uint8_t DATAGRAM_VERSION  = 3;
constexpr uint8_t NODE_INFO_VERSION = 1;
// Max announce version we emit. v3 carries per-neighbor link details (§8.8). Like the Go/Kotlin
// nodes we emit the lowest version that fits: v1 (bare neighbor IDs) when we have no link details,
// v3 with a neighbor_info section once we have live neighbors. Verifiers accept v1..v3.
constexpr uint8_t ANNOUNCE_VERSION  = 3;

// BLE PHY identifiers for a v3 ANNOUNCE neighbor_info entry (§8.8).
enum : uint8_t { PHY_UNKNOWN = 0, PHY_1M = 1, PHY_2M = 2, PHY_CODED = 3 };

// Which side opened a neighbor link, for a v3 ANNOUNCE neighbor_info entry (§8.8, §4.4).
enum : uint8_t { CONN_DIR_OUT = 1, CONN_DIR_IN = 2, CONN_DIR_BOTH = 3 };

// Link transport for a v3 ANNOUNCE neighbor_info entry (§8.8). This node's links are all BLE.
enum : uint8_t { TRANSPORT_UNKNOWN = 0, TRANSPORT_BLE = 1, TRANSPORT_MESHCORE = 2,
                 TRANSPORT_TCP = 3, TRANSPORT_USB = 4 };

// One directly-linked peer's per-link details, advertised in a v3 ANNOUNCE neighbor_info entry.
// The first group is the original §8.8 fields: [rssi] dBm (0 = no sample), PHY each way, which side
// opened the link, [ageS] seconds since the last packet (0 = unknown). The second group is the
// extended quality hints (also §8.8); each 0 = unknown: [transport] link tech, [rssiEwma] smoothed
// RSSI dBm, [qualityQ8] recent reliability 0..255, [latencyMs] representative RTT, [queueQ8]
// congestion 0..255. A constrained relay may leave the extended hints at 0.
struct AnnounceNeighborInfo {
  uint8_t  id[NODE_ID_LEN];
  int8_t   rssi;
  uint8_t  txPhy;
  uint8_t  rxPhy;
  uint8_t  dir;
  uint32_t ageS;
  uint8_t  transport;
  int8_t   rssiEwma;
  uint8_t  qualityQ8;
  uint16_t latencyMs;
  uint8_t  queueQ8;
};

constexpr uint8_t MAX_TTL        = 16;
constexpr uint8_t ANNOUNCE_TTL   = 5;
constexpr uint8_t MAX_ROUTE_HOPS = 16;

// Datagram CBOR keys.
enum : uint8_t {
  KEY_VERSION = 1, KEY_ID = 2, KEY_SOURCE = 3, KEY_DEST = 4, KEY_TTL = 5,
  KEY_ROUTE = 6, KEY_ROUTE_CURSOR = 7, KEY_PATH = 8, KEY_PROTOCOL = 9,
  KEY_FLAGS = 10, KEY_PAYLOAD = 11,
};

enum : uint16_t {
  PROTO_SIDEPATH_CONTROL = 0x0000,
  PROTO_MESHCORE_PACKET = 0x0001,
  PROTO_SIDEPATH_CHAT    = 0x0100,
};

enum : uint16_t { FLAG_ACK_REQUESTED = 0x0001 };

enum : uint8_t {
  CONTROL_ANNOUNCE = 1,
  CONTROL_ACK = 2,
  CONTROL_TRACE_REQUEST = 3,
  CONTROL_TRACE_RESPONSE = 4,
};

enum : uint16_t {
  CAP_SENDER = 0x0001,
  CAP_RECEIVER = 0x0002,
  CAP_RELAY = 0x0004,
  CAP_GATEWAY = 0x0008,
  CAP_CODED_PHY = 0x0010,
};

uint32_t crc32_ieee(const uint8_t* data, size_t len);

class DedupCache {
 public:
  explicit DedupCache(size_t capacity = 128, uint32_t ttlMs = 5UL * 60 * 1000);
  bool seenOrAdd(const uint8_t id[DATAGRAM_ID_LEN]);

 private:
  struct Entry { uint8_t id[DATAGRAM_ID_LEN]; uint32_t seenMs; bool used; };
  std::vector<Entry> entries_;
  uint32_t ttlMs_;
  size_t next_;
};

class Reassembler {
 public:
  explicit Reassembler(uint32_t timeoutMs = 10000) : timeoutMs_(timeoutMs) {}
  bool addFrame(uint16_t peerLink, const uint8_t* raw, size_t len, std::vector<uint8_t>& out);
  void reap();

 private:
  struct Assembly {
    bool used = false;
    uint16_t peer = 0;
    uint8_t transferId[TRANSFER_ID_LEN];
    uint8_t count = 0;
    uint32_t crc = 0;
    uint32_t lastMs = 0;
    std::vector<std::vector<uint8_t>> frags;
    std::vector<bool> have;
  };
  static constexpr size_t MAX_PENDING = 8;
  Assembly slots_[MAX_PENDING];
  uint32_t timeoutMs_;
};

struct DatagramHeader {
  bool ok = false;
  uint8_t id[DATAGRAM_ID_LEN];
  uint8_t source[NODE_ID_LEN];
  bool hasSource = false;
  uint8_t destination[NODE_ID_LEN];
  bool hasDestination = false;
  uint8_t ttl = 0;
  uint16_t protocol = 0;
  uint16_t flags = 0;
  uint8_t routeCursor = 0;
  uint8_t pathLen = 0;
  uint8_t routeLen = 0;
  bool sourceRouted = false;
  bool pathContainsSelf = false;
  uint8_t lastHop[NODE_ID_LEN];
  bool hasLastHop = false;
  bool routeCurrentIsSelf = false;
  bool routeEndsHere = false;
  uint8_t nextHop[NODE_ID_LEN];
  bool hasNextHop = false;
  const uint8_t* payload = nullptr;
  size_t payloadLen = 0;
  uint8_t controlKind = 0;  // parsed when protocol == SIDEPATH_CONTROL.
};

bool directNeighbor(const DatagramHeader& h, uint8_t out[NODE_ID_LEN]);
DatagramHeader parseDatagram(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN]);

bool buildFloodForward(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN],
                       uint8_t newTtl, std::vector<uint8_t>& out);
bool buildSourceRouteForward(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN],
                             uint8_t newTtl, uint8_t newRouteCursor,
                             std::vector<uint8_t>& out);

// Builds the fixed binary layout the ANNOUNCE signature covers (§8.3). [version] selects the layout:
// v1 signs the bare neighbor list; v3 leaves that list empty ([neighborCount] == 0) and appends a
// bridges section (count 0 here) then the neighbor_info section from [infos].
void announceSignedMessage(const uint8_t pubKey[PUBKEY_LEN], uint64_t epoch, uint32_t seq,
                           int64_t timestamp, uint16_t caps, uint8_t version,
                           const uint8_t* neighbors, size_t neighborCount,
                           const AnnounceNeighborInfo* infos, size_t infoCount,
                           const char* name, const char* description, const char* platform,
                           std::vector<uint8_t>& out);

void buildAnnounce(const uint8_t selfId[NODE_ID_LEN], uint16_t caps, uint64_t epoch,
                   uint32_t seq, int64_t unixSeconds, const uint8_t datagramId[DATAGRAM_ID_LEN],
                   uint8_t version, const uint8_t* neighbors, size_t neighborCount,
                   const AnnounceNeighborInfo* infos, size_t infoCount,
                   const uint8_t pubKey[PUBKEY_LEN], const uint8_t signature[SIG_LEN],
                   const char* name, const char* description, const char* platform,
                   std::vector<uint8_t>& out);

void buildNodeInfo(const uint8_t pubKey[PUBKEY_LEN], uint16_t caps, std::vector<uint8_t>& out);

// Builds a source-routed TRACE_RESPONSE replying to a TRACE_REQUEST that was delivered to us.
// [req] is the parsed request header and [reqDg]/[reqLen] its raw bytes (needed to read the path).
// The reply's route is reverse(request path) + request source, and its body echoes the tag, metric
// and forward path. [datagramId] is the new response id; [tagOut] receives the request tag for
// logging. Returns false if the request body can't be parsed or the route would exceed MAX_ROUTE_HOPS.
bool buildTraceResponse(const DatagramHeader& req, const uint8_t* reqDg, size_t reqLen,
                        const uint8_t selfId[NODE_ID_LEN], const uint8_t datagramId[DATAGRAM_ID_LEN],
                        std::vector<uint8_t>& out, uint32_t& tagOut);

void randomTransferId(uint8_t out[TRANSFER_ID_LEN]);
void fragment(const uint8_t* dg, size_t len, size_t mtu,
              std::vector<std::vector<uint8_t>>& frames);

}  // namespace mesh
