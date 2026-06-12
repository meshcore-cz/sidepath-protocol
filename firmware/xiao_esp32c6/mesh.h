// BLEEdge v3 mesh core for microcontrollers. Pure wire logic, no NimBLE
// dependency, so it can be host-tested against the Go implementation.
#pragma once

#include <Arduino.h>
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
constexpr uint8_t ANNOUNCE_VERSION  = 1;

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
  PROTO_BLEEDGE_CONTROL = 0x0000,
  PROTO_MESHCORE_PACKET = 0x0001,
  PROTO_BLEEDGE_CHAT    = 0x0100,
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
  uint8_t controlKind = 0;  // parsed when protocol == BLEEDGE_CONTROL.
};

bool directNeighbor(const DatagramHeader& h, uint8_t out[NODE_ID_LEN]);
DatagramHeader parseDatagram(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN]);

bool buildFloodForward(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN],
                       uint8_t newTtl, std::vector<uint8_t>& out);
bool buildSourceRouteForward(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN],
                             uint8_t newTtl, uint8_t newRouteCursor,
                             std::vector<uint8_t>& out);

void announceSignedMessage(const uint8_t pubKey[PUBKEY_LEN], uint64_t epoch, uint32_t seq,
                           int64_t timestamp, uint16_t caps,
                           const uint8_t* neighbors, size_t neighborCount,
                           const char* name, const char* description, const char* platform,
                           std::vector<uint8_t>& out);

void buildAnnounce(const uint8_t selfId[NODE_ID_LEN], uint16_t caps, uint64_t epoch,
                   uint32_t seq, int64_t unixSeconds, const uint8_t datagramId[DATAGRAM_ID_LEN],
                   const uint8_t* neighbors, size_t neighborCount,
                   const uint8_t pubKey[PUBKEY_LEN], const uint8_t signature[SIG_LEN],
                   const char* name, const char* description, const char* platform,
                   std::vector<uint8_t>& out);

void buildNodeInfo(const uint8_t pubKey[PUBKEY_LEN], uint16_t caps, std::vector<uint8_t>& out);

void randomTransferId(uint8_t out[TRANSFER_ID_LEN]);
void fragment(const uint8_t* dg, size_t len, size_t mtu,
              std::vector<std::vector<uint8_t>>& frames);

}  // namespace mesh
