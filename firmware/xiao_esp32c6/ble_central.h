// BLE central role for the Sidepath relay (PROTOCOL.md §4.0). Included once from
// xiao_esp32c6.ino after the relay engine (onDatagram), the send path
// (sendFrameRaw), and the shared peer tables (g_peers / g_connNode /
// g_clientPacketIn, all guarded by g_peersMu) are defined.
//
// As a central the board scans continuously, dials discovered Sidepath peers,
// subscribes to their PACKET_OUT for indications, and writes relayed frames to
// their PACKET_IN. Outbound links join the same g_peers/g_connNode tables the
// server role uses, so flooding and source-routing reach inbound and outbound
// peers uniformly. A mutual inbound+outbound link to one NodeID is collapsed
// deterministically (§4.4).
//
// Targets NimBLE-Arduino 2.x (matching the server-callback signatures in the
// .ino): NimBLEScanCallbacks/NimBLEClientCallbacks, getDisconnectedClient(),
// getCreatedClientCount().
//
// NOTE: inbound (server) + outbound (client) connections share NimBLE's
// connection pool, sized by CONFIG_BT_NIMBLE_MAX_CONNECTIONS (default 3). For
// meaningful dual-role meshing raise it in the build's sdkconfig; MAX_OUTBOUND_LINKS
// below must stay within whatever slots remain for the client role.

#pragma once

#include <array>
#include <map>
#include <set>
#include <string>

// Per-neighbor signal observed from BLE scan advertisements, keyed by NodeID. The scan hears every
// Sidepath advertiser in range — peers we dial and peers that dial us — so this is our own measured
// view of each link, fed into the v3 ANNOUNCE neighbor_info (RSSI in dBm, age from last advert).
struct ScanObs {
  int8_t   rssi;    // dBm as we heard the peer's advertisement
  uint32_t lastMs;  // millis() of that advert
};
static std::map<std::array<uint8_t, mesh::NODE_ID_LEN>, ScanObs> g_neighborObs;

// Cap on simultaneous links this node dials. Keep inbound + outbound within the
// NimBLE connection pool (see file note above).
static const size_t MAX_OUTBOUND_LINKS = 3;
static const uint16_t SCAN_INTERVAL = 80;  // 0.625ms units
static const uint16_t SCAN_WINDOW   = 40;

// Addresses pending connection (discovered, not yet dialed). Drained in loop().
static std::vector<NimBLEAddress> g_connectQueue;
// Addresses we have queued, are dialing, or are linked to — dedups scan hits so
// we neither double-dial nor re-dial an existing peer. Cleared on disconnect/fail.
static std::set<std::string> g_knownAddr;
// Outbound links: conn handle → its client, for targeted disconnect (collapse).
static std::map<uint16_t, NimBLEClient*> g_clientByConn;

static void disconnectClientByConn(uint16_t conn);

// --- helpers --------------------------------------------------------------

// Extracts a peer's NodeID from its 0xBEED manufacturer data (the advertised
// format from setup(): company id 0xBEED little-endian, then the 10-byte NodeID).
static bool parseAdvNodeId(const NimBLEAdvertisedDevice* dev, uint8_t out[mesh::NODE_ID_LEN]) {
  if (!dev->haveManufacturerData()) return false;
  std::string md = dev->getManufacturerData();
  if (md.size() < 2 + mesh::NODE_ID_LEN) return false;
  if ((uint8_t)md[0] != 0xED || (uint8_t)md[1] != 0xBE) return false;
  memcpy(out, md.data() + 2, mesh::NODE_ID_LEN);
  return true;
}

// True if any current link (inbound or outbound) already maps to this NodeID.
// Caller must hold g_peersMu.
static bool haveLinkToNodeLocked(const uint8_t id[mesh::NODE_ID_LEN]) {
  for (auto& kv : g_connNode)
    if (memcmp(kv.second.data(), id, mesh::NODE_ID_LEN) == 0) return true;
  return false;
}

// Indication/notification from a peer we dialed: reassemble and feed the relay
// engine exactly like an inbound PACKET_IN write. Runs on the NimBLE host task.
static void clientNotifyCb(NimBLERemoteCharacteristic* chr, uint8_t* data, size_t len, bool /*isNotify*/) {
  uint16_t conn = chr->getClient()->getConnHandle();
  std::vector<uint8_t> dg;
  if (g_reassembler.addFrame(conn, data, len, dg)) onDatagram(dg, conn);
}

// §4.4: when both an inbound and an outbound link reach the same NodeID, the node
// with the larger NodeID drops its outbound link; the smaller keeps it. Called
// after a connection's NodeID is learned.
static void maybeCollapseDuplicate(uint16_t conn) {
  uint16_t drop = 0xFFFF;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    auto self = g_connNode.find(conn);
    if (self == g_connNode.end()) return;
    for (auto& kv : g_connNode) {
      if (kv.first == conn || kv.second != self->second) continue;
      if (memcmp(g_nodeId, self->second.data(), mesh::NODE_ID_LEN) > 0) {
        if (g_clientPacketIn.count(conn)) drop = conn;
        else if (g_clientPacketIn.count(kv.first)) drop = kv.first;
      }
      break;
    }
  }
  if (drop != 0xFFFF) disconnectClientByConn(drop);
}

static void disconnectClientByConn(uint16_t conn) {
  NimBLEClient* c = nullptr;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    auto it = g_clientByConn.find(conn);
    if (it != g_clientByConn.end()) c = it->second;
  }
  if (c) {
    Serial.printf("[central] collapse duplicate: dropping outbound conn=%u\n", conn);
    c->disconnect();  // tables are cleaned up in onDisconnect
  }
}

// --- callbacks ------------------------------------------------------------

class ClientCallbacks : public NimBLEClientCallbacks {
  void onDisconnect(NimBLEClient* c, int reason) override {
    uint16_t conn = c->getConnHandle();
    std::string addr = c->getPeerAddress().toString();
    {
      std::lock_guard<std::mutex> lk(g_peersMu);
      g_peers.erase(std::remove(g_peers.begin(), g_peers.end(), conn), g_peers.end());
      g_connNode.erase(conn);
      g_clientPacketIn.erase(conn);
      g_clientByConn.erase(conn);
      g_knownAddr.erase(addr);
    }
    Serial.printf("[central] link down conn=%u reason=%d\n", conn, reason);
  }
  void onConnectFail(NimBLEClient* c, int reason) override {
    std::lock_guard<std::mutex> lk(g_peersMu);
    g_knownAddr.erase(c->getPeerAddress().toString());
    Serial.printf("[central] connect fail reason=%d\n", reason);
  }
};

class ScanCallbacks : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* dev) override {
    uint8_t advId[mesh::NODE_ID_LEN];
    bool haveId = parseAdvNodeId(dev, advId);
    bool isSidepath = haveId || dev->isAdvertisingService(NimBLEUUID(SERVICE_UUID));
    if (!isSidepath) return;
    if (haveId && memcmp(advId, g_nodeId, mesh::NODE_ID_LEN) == 0) return;  // self
    std::string key = dev->getAddress().toString();
    std::lock_guard<std::mutex> lk(g_peersMu);
    // Record the signal for any identifiable Sidepath peer, even one we're already linked to or can't
    // dial right now — the next ANNOUNCE advertises this RSSI/age for the link.
    if (haveId) {
      std::array<uint8_t, mesh::NODE_ID_LEN> obsKey;
      memcpy(obsKey.data(), advId, mesh::NODE_ID_LEN);
      ScanObs& o = g_neighborObs[obsKey];
      o.rssi = (int8_t)dev->getRSSI();
      o.lastMs = millis();
    }
    if (g_clientPacketIn.size() >= MAX_OUTBOUND_LINKS) return;
    if (g_knownAddr.count(key)) return;                       // already queued/dialing/linked
    if (haveId && haveLinkToNodeLocked(advId)) return;        // already linked to this NodeID
    g_knownAddr.insert(key);
    g_connectQueue.push_back(dev->getAddress());
  }
};

static ClientCallbacks g_clientCb;
static ScanCallbacks g_scanCb;

// --- connect / lifecycle --------------------------------------------------

static void connectToPeer(const NimBLEAddress& addr) {
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    if (g_clientPacketIn.size() >= MAX_OUTBOUND_LINKS) { g_knownAddr.erase(addr.toString()); return; }
  }
  NimBLEScan* scan = NimBLEDevice::getScan();
  if (scan->isScanning()) scan->stop();  // don't dial while scanning

  NimBLEClient* c = NimBLEDevice::getDisconnectedClient();
  if (!c) {
    if (NimBLEDevice::getCreatedClientCount() >= MAX_OUTBOUND_LINKS) {
      std::lock_guard<std::mutex> lk(g_peersMu);
      g_knownAddr.erase(addr.toString());
      return;
    }
    c = NimBLEDevice::createClient();
    c->setClientCallbacks(&g_clientCb, false);
  }

  auto giveUp = [&](const char* why) {
    Serial.printf("[central] %s addr=%s\n", why, addr.toString().c_str());
    c->disconnect();
    std::lock_guard<std::mutex> lk(g_peersMu);
    g_knownAddr.erase(addr.toString());
  };

  if (!c->connect(addr)) { giveUp("connect failed"); return; }
  NimBLERemoteService* svc = c->getService(SERVICE_UUID);
  NimBLERemoteCharacteristic* outc = svc ? svc->getCharacteristic(PACKETOUT_UUID) : nullptr;
  NimBLERemoteCharacteristic* inc  = svc ? svc->getCharacteristic(PACKETIN_UUID)  : nullptr;
  if (!outc || !inc || !outc->canIndicate()) { giveUp("service/characteristics missing"); return; }
  if (!outc->subscribe(false, clientNotifyCb)) { giveUp("subscribe failed"); return; }  // false = indications

  uint16_t conn = c->getConnHandle();
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    g_peers.push_back(conn);
    g_clientPacketIn[conn] = inc;
    g_clientByConn[conn] = c;
  }
  Serial.printf("[central] linked conn=%u addr=%s\n", conn, addr.toString().c_str());
}

static void startCentral() {
  NimBLEScan* scan = NimBLEDevice::getScan();
  scan->setScanCallbacks(&g_scanCb, false);
  scan->setActiveScan(true);
  scan->setInterval(SCAN_INTERVAL);
  scan->setWindow(SCAN_WINDOW);
  scan->start(0, false);  // 0 = scan continuously
  Serial.println("[central] scanning for peers");
}

static void centralTick() {
  NimBLEAddress next;
  bool have = false;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    if (!g_connectQueue.empty()) {
      next = g_connectQueue.front();
      g_connectQueue.erase(g_connectQueue.begin());
      have = true;
    }
  }
  if (have) connectToPeer(next);

  // Keep scanning whenever we still have an outbound slot free (connectToPeer
  // stops the scan while dialing).
  bool slotFree;
  { std::lock_guard<std::mutex> lk(g_peersMu); slotFree = g_clientPacketIn.size() < MAX_OUTBOUND_LINKS; }
  NimBLEScan* scan = NimBLEDevice::getScan();
  if (slotFree && !scan->isScanning()) scan->start(0, false);
}
