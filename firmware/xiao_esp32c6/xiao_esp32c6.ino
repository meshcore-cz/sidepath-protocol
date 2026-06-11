// BLEEdge relay firmware for the Seeed Studio XIAO ESP32-C6.
//
// Acts as a multi-connection GATT-server "relay hub": phones/nodes connect to it
// as GATT clients (exactly how they connect to each other) and it floods frames
// between all connected peers, applying the same dedup / TTL / loop / trace rules
// as the rest of the mesh. This lets two nodes that can't reach each other directly
// communicate through the ESP32.
//
// Requires:  arduino-esp32 core >= 3.0.0  and  NimBLE-Arduino >= 2.1.0
// Board:     "XIAO_ESP32C6"
//
// Limitations (see README.md): peripheral/server role only (it does not scan or
// initiate connections), FLOOD-mode packets only (no source-route forwarding),
// and 1M PHY (the default mesh PHY).

#include <NimBLEDevice.h>
#include <Preferences.h>
#include <esp_random.h>

#include <algorithm>
#include <array>
#include <map>
#include <mutex>
#include <vector>

#include "mesh.h"
#include "src/ed25519/ed25519.h"

// ---- BLEEdge GATT UUIDs (must match core / Android / Go) ---------------------
static const char* SERVICE_UUID   = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a001";
static const char* NODEINFO_UUID  = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a002";
static const char* PACKETIN_UUID  = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a003";
static const char* PACKETOUT_UUID = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a004";

static const uint8_t  PROTOCOL_VERSION = mesh::PROTOCOL_VERSION;  // 2: Ed25519 + signed ANNOUNCE
static const uint8_t  RELAY_CAPS = mesh::CAP_SENDER | mesh::CAP_RECEIVER | mesh::CAP_RELAY;
static const char*    NODE_DESCRIPTION = "esp32-c6";  // diagnostic label in ANNOUNCE/NODE_INFO

// Onboard user LED — blinks once per ANNOUNCE (advert) as a heartbeat.
// XIAO ESP32-C6: user LED is GPIO15 and active-LOW (drive LOW = on).
static const int      LED_PIN = 15;
static const bool     LED_ACTIVE_LOW = true;
static const uint32_t LED_BLINK_MS = 60;

static inline void ledSet(bool on) {
  digitalWrite(LED_PIN, (LED_ACTIVE_LOW ? !on : on) ? HIGH : LOW);
}
// Conservative fragment size: a frame is FRAGMENT_MTU bytes, so each peer's ATT
// MTU must be >= FRAGMENT_MTU + 3. 200 is safe for any peer negotiating >= 203.
static const size_t   FRAGMENT_MTU = 200;
static const uint32_t ANNOUNCE_INTERVAL_MS = 15000;

// ---- node state -------------------------------------------------------------
static uint8_t g_nodeId[mesh::NODE_ID_LEN];      // = g_pubKey[:8]
static uint8_t g_seed[32];                       // Ed25519 32-byte seed (persisted in NVS)
static uint8_t g_pubKey[mesh::PUBKEY_LEN];       // Ed25519 public key (node identity)
static uint8_t g_privKey[64];                    // orlp expanded private key

static NimBLECharacteristic* g_packetOut = nullptr;
static mesh::DedupCache  g_dedup;
static mesh::Reassembler g_reassembler;

static std::mutex g_peersMu;
static std::vector<uint16_t> g_peers;  // connected central conn handles
// Direct-neighbor NodeID learned per connection (from received packets), used to
// populate our ANNOUNCE neighbor list so we appear correctly in the mesh topology.
static std::map<uint16_t, std::array<uint8_t, mesh::NODE_ID_LEN>> g_connNode;

static uint32_t g_announceSeq = 0;
static uint32_t g_lastAnnounceMs = 0;
static uint32_t g_ledOffMs = 0;  // when to turn the heartbeat LED back off (0 = off)

// ---- helpers ----------------------------------------------------------------

// Loads the Ed25519 identity seed from NVS, or generates and persists a new one,
// then derives the keypair. NodeID = pubkey[:8] (MeshCore-compatible identity).
static void loadOrCreateIdentity() {
  Preferences prefs;
  prefs.begin("bleedge", false);
  size_t n = prefs.getBytesLength("seed");
  if (n == sizeof(g_seed)) {
    prefs.getBytes("seed", g_seed, sizeof(g_seed));
  } else {
    esp_fill_random(g_seed, sizeof(g_seed));
    prefs.putBytes("seed", g_seed, sizeof(g_seed));
    Serial.println("[relay] generated new Ed25519 identity seed");
  }
  prefs.end();

  ed25519_create_keypair(g_pubKey, g_privKey, g_seed);
  memcpy(g_nodeId, g_pubKey, mesh::NODE_ID_LEN);
}

static String nodeIdHex() {
  String s;
  for (size_t i = 0; i < mesh::NODE_ID_LEN; i++) {
    char b[3];
    snprintf(b, sizeof(b), "%02x", g_nodeId[i]);
    s += b;
  }
  return s;
}

// Sends all frames of `pkt` to every connected peer except `exclude`.
static void floodToPeers(const std::vector<uint8_t>& pkt, const uint8_t pid[mesh::PACKET_ID_LEN],
                         uint16_t exclude) {
  std::vector<std::vector<uint8_t>> frames;
  mesh::fragment(pkt.data(), pkt.size(), FRAGMENT_MTU, pid, frames);

  std::vector<uint16_t> targets;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    targets = g_peers;
  }
  for (uint16_t h : targets) {
    if (h == exclude) continue;
    for (const auto& f : frames) {
      g_packetOut->notify(f.data(), f.size(), h);
    }
  }
}

// Called for each reassembled packet arriving on PACKET_IN from `sender`.
static void onPacket(const std::vector<uint8_t>& pkt, uint16_t sender) {
  mesh::PacketHeader h = mesh::parseHeader(pkt.data(), pkt.size(), g_nodeId);
  if (!h.ok) {
    Serial.println("[relay] drop: malformed packet");
    return;
  }
  // Learn the directly-connected peer (last trace hop, or source if fresh) so we
  // advertise it as a neighbor. Done before dedup so duplicates still teach us.
  uint8_t nb[mesh::NODE_ID_LEN];
  if (mesh::directNeighbor(h, nb) && memcmp(nb, g_nodeId, mesh::NODE_ID_LEN) != 0) {
    std::array<uint8_t, mesh::NODE_ID_LEN> id;
    memcpy(id.data(), nb, mesh::NODE_ID_LEN);
    std::lock_guard<std::mutex> lk(g_peersMu);
    g_connNode[sender] = id;
  }
  if (h.mode != mesh::MODE_FLOOD) {
    Serial.println("[relay] drop: non-flood packet (source-route not supported)");
    return;
  }
  if (g_dedup.seenOrAdd(h.id)) {
    return;  // duplicate — silently drop (this is the common, expected case)
  }
  if (h.traceContainsSelf) {
    Serial.println("[relay] drop: loop (self already in trace)");
    return;
  }
  if (h.ttl < 2) {
    Serial.println("[relay] drop: ttl exhausted");
    return;
  }

  std::vector<uint8_t> fwd;
  if (!mesh::buildForward(pkt.data(), pkt.size(), g_nodeId, h.ttl - 1, fwd)) {
    Serial.println("[relay] drop: build-forward failed");
    return;
  }
  Serial.printf("[relay] forward type=%u ttl=%u->%u size=%u\n", h.type, h.ttl, h.ttl - 1,
                (unsigned)fwd.size());
  floodToPeers(fwd, h.id, sender);
}

static void sendAnnounce() {
  uint8_t pid[mesh::PACKET_ID_LEN];
  esp_fill_random(pid, sizeof(pid));

  // Snapshot learned neighbor NodeIDs into a contiguous buffer for the ANNOUNCE.
  std::vector<uint8_t> neighbors;
  size_t nPeers;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    nPeers = g_peers.size();
    for (auto& kv : g_connNode)
      neighbors.insert(neighbors.end(), kv.second.begin(), kv.second.end());
  }
  size_t nNeighbors = neighbors.size() / mesh::NODE_ID_LEN;

  uint32_t seq = ++g_announceSeq;
  uint32_t ts = millis() / 1000;

  // Sign the canonical announce message with our Ed25519 identity.
  std::vector<uint8_t> signedMsg;
  mesh::announceSignedMessage(g_pubKey, ts, RELAY_CAPS, seq, neighbors.data(), nNeighbors, signedMsg);
  uint8_t sig[mesh::SIG_LEN];
  ed25519_sign(sig, signedMsg.data(), signedMsg.size(), g_pubKey, g_privKey);

  std::vector<uint8_t> pkt;
  mesh::buildAnnounce(g_nodeId, RELAY_CAPS, seq, ts, pid,
                      neighbors.data(), nNeighbors, g_pubKey, sig, NODE_DESCRIPTION, pkt);

  // Heartbeat: blink the onboard LED once per advert (turned off in loop()).
  ledSet(true);
  g_ledOffMs = millis() + LED_BLINK_MS;

  // Mark our own packet so a flood echo isn't re-flooded back out.
  g_dedup.seenOrAdd(pid);

  Serial.printf("[relay] ANNOUNCE seq=%u -> %u peers, %u neighbors\n", g_announceSeq,
                (unsigned)nPeers, (unsigned)nNeighbors);
  floodToPeers(pkt, pid, 0xFFFF /* no exclusion */);
}

// ---- NimBLE callbacks -------------------------------------------------------

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
    {
      std::lock_guard<std::mutex> lk(g_peersMu);
      g_peers.push_back(info.getConnHandle());
    }
    Serial.printf("[relay] peer connected handle=%u\n", info.getConnHandle());
    NimBLEDevice::startAdvertising();  // keep advertising for additional peers
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& info, int reason) override {
    {
      std::lock_guard<std::mutex> lk(g_peersMu);
      uint16_t h = info.getConnHandle();
      g_peers.erase(std::remove(g_peers.begin(), g_peers.end(), h), g_peers.end());
      g_connNode.erase(h);
    }
    Serial.printf("[relay] peer disconnected handle=%u reason=%d\n", info.getConnHandle(), reason);
    NimBLEDevice::startAdvertising();
  }
};

class PacketInCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& info) override {
    NimBLEAttValue val = chr->getValue();
    std::vector<uint8_t> pkt;
    if (g_reassembler.addFrame(val.data(), val.length(), pkt)) {
      onPacket(pkt, info.getConnHandle());
    }
  }
};

// ---- Arduino entry points ---------------------------------------------------

void setup() {
  Serial.begin(115200);
  delay(200);
  pinMode(LED_PIN, OUTPUT);
  ledSet(false);
  loadOrCreateIdentity();
  Serial.printf("\nBLEEdge relay  node=%s  phy=1m\n", nodeIdHex().c_str());

  NimBLEDevice::init("BLEEdge");
  NimBLEDevice::setMTU(247);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  server->advertiseOnDisconnect(true);

  NimBLEService* svc = server->createService(SERVICE_UUID);

  // NODE_INFO (read): version(1) + pubkey(32) + caps(1) + descLen(1) + desc(descLen)
  size_t descLen = strlen(NODE_DESCRIPTION);
  if (descLen > 255) descLen = 255;
  std::vector<uint8_t> nodeInfo;
  nodeInfo.push_back(PROTOCOL_VERSION);
  nodeInfo.insert(nodeInfo.end(), g_pubKey, g_pubKey + mesh::PUBKEY_LEN);
  nodeInfo.push_back(RELAY_CAPS);
  nodeInfo.push_back((uint8_t)descLen);
  nodeInfo.insert(nodeInfo.end(), NODE_DESCRIPTION, NODE_DESCRIPTION + descLen);
  NimBLECharacteristic* ni = svc->createCharacteristic(NODEINFO_UUID, NIMBLE_PROPERTY::READ);
  ni->setValue(nodeInfo.data(), nodeInfo.size());

  // PACKET_IN (write / write-no-response)
  NimBLECharacteristic* pin =
      svc->createCharacteristic(PACKETIN_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  pin->setCallbacks(new PacketInCallbacks());

  // PACKET_OUT (notify) — NimBLE auto-adds the CCCD descriptor.
  g_packetOut = svc->createCharacteristic(PACKETOUT_UUID, NIMBLE_PROPERTY::NOTIFY);

  svc->start();

  // Advertising: service UUID in the primary advert; manufacturer data (company
  // 0xBEED + NodeID) in the scan response, mirroring the Android legacy advertiser
  // so a 1M-PHY scanner discovers us by either UUID or manufacturer data.
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();

  NimBLEAdvertisementData advData;
  advData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  advData.addServiceUUID(NimBLEUUID(SERVICE_UUID));
  adv->setAdvertisementData(advData);

  uint8_t mfg[2 + mesh::NODE_ID_LEN];
  mfg[0] = 0xED;  // company id 0xBEED, little-endian
  mfg[1] = 0xBE;
  memcpy(mfg + 2, g_nodeId, mesh::NODE_ID_LEN);
  NimBLEAdvertisementData scanData;
  scanData.setManufacturerData(std::string(reinterpret_cast<char*>(mfg), sizeof(mfg)));
  scanData.setName("BLEEdge");
  adv->setScanResponseData(scanData);

  adv->start();
  Serial.println("[relay] advertising started (1M legacy)");
}

void loop() {
  uint32_t now = millis();
  if (now - g_lastAnnounceMs >= ANNOUNCE_INTERVAL_MS) {
    g_lastAnnounceMs = now;
    sendAnnounce();
  }
  if (g_ledOffMs != 0 && now >= g_ledOffMs) {
    ledSet(false);
    g_ledOffMs = 0;
  }
  g_reassembler.reap();
  delay(50);
}
