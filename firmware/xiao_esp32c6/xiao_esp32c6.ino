// BLEEdge v3 relay firmware for Seeed Studio XIAO ESP32-C6.
//
// Peripheral/server relay: phones and nodes connect to this board, write v2
// GATT frames to PACKET_IN, and receive relayed frames from PACKET_OUT.

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

static const char* SERVICE_UUID   = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a001";
static const char* NODEINFO_UUID  = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a002";
static const char* PACKETIN_UUID  = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a003";
static const char* PACKETOUT_UUID = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a004";

static const uint16_t RELAY_CAPS = mesh::CAP_SENDER | mesh::CAP_RECEIVER | mesh::CAP_RELAY;
static const char* NODE_PLATFORM = "esp32-c6";
static const char* NODE_NAME = "";
static const char* NODE_DESCRIPTION = "";
static const size_t FRAME_MTU = 200;
static const uint32_t ANNOUNCE_INTERVAL_MS = 15000;
static const int LED_PIN = 15;
static const bool LED_ACTIVE_LOW = true;
static const uint32_t LED_BLINK_MS = 60;

static uint8_t g_nodeId[mesh::NODE_ID_LEN];
static uint8_t g_seed[32];
static uint8_t g_pubKey[mesh::PUBKEY_LEN];
static uint8_t g_privKey[64];
static uint64_t g_epoch = 1;

static NimBLECharacteristic* g_packetOut = nullptr;
static mesh::DedupCache g_dedup;
static mesh::Reassembler g_reassembler;

static std::mutex g_peersMu;
static std::vector<uint16_t> g_peers;
static std::map<uint16_t, std::array<uint8_t, mesh::NODE_ID_LEN>> g_connNode;

static uint32_t g_announceSeq = 0;
static uint32_t g_lastAnnounceMs = 0;
static uint32_t g_ledOffMs = 0;
static uint32_t g_bootMs = 0;
static uint32_t g_rxDatagrams = 0;
static uint32_t g_txDatagrams = 0;
static uint32_t g_txFrames = 0;
static uint32_t g_floodRelays = 0;
static uint32_t g_sourceRelays = 0;
static uint32_t g_cmdAccepted = 0;
static uint32_t g_cmdDenied = 0;
static bool g_clockSet = false;
static int64_t g_clockBaseUnix = 0;
static uint32_t g_clockBaseMillis = 0;
static String g_serialLine;

static bool handleRemoteAdminChat(const std::vector<uint8_t>& dg, const mesh::DatagramHeader& h, uint16_t sender);
static void loadAdmins();
static bool handleAdminCommandExtra(const String& cmd, String& reply);
static String adminCommandHelp();

static inline void ledSet(bool on) {
  digitalWrite(LED_PIN, (LED_ACTIVE_LOW ? !on : on) ? HIGH : LOW);
}

static bool parseHexSeed(const char* hex, uint8_t out[32]) {
  if (!hex || strlen(hex) != 64) return false;
  for (size_t i = 0; i < 32; i++) {
    auto nib = [](char c) -> int {
      if (c >= '0' && c <= '9') return c - '0';
      if (c >= 'a' && c <= 'f') return c - 'a' + 10;
      if (c >= 'A' && c <= 'F') return c - 'A' + 10;
      return -1;
    };
    int hi = nib(hex[i * 2]), lo = nib(hex[i * 2 + 1]);
    if (hi < 0 || lo < 0) return false;
    out[i] = (uint8_t)((hi << 4) | lo);
  }
  return true;
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

static bool isLeapYear(int y) {
  return (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0);
}

static int daysBeforeMonth(int y, int m) {
  static const int days[] = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
  int d = days[m - 1];
  if (m > 2 && isLeapYear(y)) d++;
  return d;
}

static bool parse2(const String& s, int pos, int& out) {
  if (pos + 1 >= (int)s.length() || !isDigit(s[pos]) || !isDigit(s[pos + 1])) return false;
  out = (s[pos] - '0') * 10 + (s[pos + 1] - '0');
  return true;
}

static bool parse4(const String& s, int pos, int& out) {
  if (pos + 3 >= (int)s.length()) return false;
  out = 0;
  for (int i = 0; i < 4; i++) {
    if (!isDigit(s[pos + i])) return false;
    out = out * 10 + (s[pos + i] - '0');
  }
  return true;
}

static bool parseIsoUtc(const String& iso, int64_t& unixOut) {
  // Accepted form: YYYY-MM-DDTHH:MM:SSZ
  if (iso.length() != 20 || iso[4] != '-' || iso[7] != '-' ||
      (iso[10] != 'T' && iso[10] != 't') || iso[13] != ':' ||
      iso[16] != ':' || iso[19] != 'Z') {
    return false;
  }
  int y, mo, d, h, mi, sec;
  if (!parse4(iso, 0, y) || !parse2(iso, 5, mo) || !parse2(iso, 8, d) ||
      !parse2(iso, 11, h) || !parse2(iso, 14, mi) || !parse2(iso, 17, sec)) {
    return false;
  }
  if (y < 1970 || mo < 1 || mo > 12 || d < 1 || h > 23 || mi > 59 || sec > 59) return false;
  static const int mdays[] = {31,28,31,30,31,30,31,31,30,31,30,31};
  int maxDay = mdays[mo - 1] + ((mo == 2 && isLeapYear(y)) ? 1 : 0);
  if (d > maxDay) return false;

  int64_t days = 0;
  for (int yr = 1970; yr < y; yr++) days += isLeapYear(yr) ? 366 : 365;
  days += daysBeforeMonth(y, mo) + (d - 1);
  unixOut = days * 86400 + h * 3600 + mi * 60 + sec;
  return true;
}

static int64_t internalUnixTime() {
  if (!g_clockSet) return 0;
  return g_clockBaseUnix + (int64_t)((uint32_t)(millis() - g_clockBaseMillis) / 1000);
}

static String isoUtcFromUnix(int64_t t) {
  if (t <= 0) return "unset";
  int64_t days = t / 86400;
  int64_t rem = t % 86400;
  int h = rem / 3600;
  rem %= 3600;
  int mi = rem / 60;
  int sec = rem % 60;
  int y = 1970;
  while (true) {
    int yd = isLeapYear(y) ? 366 : 365;
    if (days < yd) break;
    days -= yd;
    y++;
  }
  int mo = 1;
  static const int mdays[] = {31,28,31,30,31,30,31,31,30,31,30,31};
  while (true) {
    int dm = mdays[mo - 1] + ((mo == 2 && isLeapYear(y)) ? 1 : 0);
    if (days < dm) break;
    days -= dm;
    mo++;
  }
  char buf[25];
  snprintf(buf, sizeof(buf), "%04d-%02d-%02dT%02d:%02d:%02dZ", y, mo, (int)days + 1, h, mi, sec);
  return String(buf);
}

static String statsText() {
  uint32_t peers = 0, neighbors = 0;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    peers = g_peers.size();
    neighbors = g_connNode.size();
  }
  String out;
  out += "stats\n";
  out += "uptime_s=" + String((millis() - g_bootMs) / 1000) + "\n";
  out += "peers=" + String(peers) + "\n";
  out += "neighbors=" + String(neighbors) + "\n";
  out += "rx_datagrams=" + String(g_rxDatagrams) + "\n";
  out += "tx_datagrams=" + String(g_txDatagrams) + "\n";
  out += "tx_frames=" + String(g_txFrames) + "\n";
  out += "flood_relays=" + String(g_floodRelays) + "\n";
  out += "source_relays=" + String(g_sourceRelays) + "\n";
  out += "announces=" + String(g_announceSeq) + "\n";
  out += "cmd_accepted=" + String(g_cmdAccepted) + "\n";
  out += "cmd_denied=" + String(g_cmdDenied) + "\n";
  out += "clock=" + isoUtcFromUnix(internalUnixTime());
  return out;
}

static String handleAdminCommand(String cmd) {
  cmd.trim();
  if (cmd == "help" || cmd == "?") {
    return "commands: help, sensors, stats, clock, clock.set <YYYY-MM-DDTHH:MM:SSZ>" + adminCommandHelp();
  }
  if (cmd == "sensors") return "temperature_c=" + String(temperatureRead(), 2);
  if (cmd == "stats") return statsText();
  if (cmd == "clock") {
    int64_t now = internalUnixTime();
    if (!g_clockSet) return "clock=unset";
    char unixBuf[32];
    snprintf(unixBuf, sizeof(unixBuf), "%lld", (long long)now);
    return "clock=" + isoUtcFromUnix(now) + " unix=" + String(unixBuf);
  }
  if (cmd.startsWith("clock.set ")) {
    String iso = cmd.substring(strlen("clock.set "));
    iso.trim();
    int64_t unixTs = 0;
    if (!parseIsoUtc(iso, unixTs)) {
      return "error: expected clock.set <YYYY-MM-DDTHH:MM:SSZ>";
    }
    g_clockSet = true;
    g_clockBaseUnix = unixTs;
    g_clockBaseMillis = millis();
    return "ok: clock=" + isoUtcFromUnix(internalUnixTime());
  }
  String extra;
  if (handleAdminCommandExtra(cmd, extra)) return extra;
  return "error: unknown command; try help";
}

static void processSerialAdmin() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\r') continue;
    if (c == '\n') {
      if (g_serialLine.length() > 0) {
        Serial.println(handleAdminCommand(g_serialLine));
        g_serialLine = "";
      }
    } else if (g_serialLine.length() < 160) {
      g_serialLine += c;
    }
  }
}

static void loadOrCreateIdentityAndEpoch() {
#ifdef BLEEDGE_NODE_SEED_HEX
  if (parseHexSeed(BLEEDGE_NODE_SEED_HEX, g_seed)) {
    Serial.println("[relay] using build-time identity seed");
  } else
#endif
  {
    Preferences prefs;
    prefs.begin("bleedge", false);
    if (prefs.getBytesLength("seed") == sizeof(g_seed)) {
      prefs.getBytes("seed", g_seed, sizeof(g_seed));
    } else {
      esp_fill_random(g_seed, sizeof(g_seed));
      prefs.putBytes("seed", g_seed, sizeof(g_seed));
      Serial.println("[relay] generated new Ed25519 identity seed");
    }
    g_epoch = prefs.getULong64("epoch", 0) + 1;
    prefs.putULong64("epoch", g_epoch);
    prefs.end();
  }
  ed25519_create_keypair(g_pubKey, g_privKey, g_seed);
  memcpy(g_nodeId, g_pubKey, mesh::NODE_ID_LEN);
}

static void sendFramesToConn(const std::vector<uint8_t>& dg, uint16_t conn) {
  std::vector<std::vector<uint8_t>> frames;
  mesh::fragment(dg.data(), dg.size(), FRAME_MTU, frames);
  g_txDatagrams++;
  for (const auto& f : frames) {
    g_packetOut->notify(f.data(), f.size(), conn);
    g_txFrames++;
  }
}

static void floodToPeers(const std::vector<uint8_t>& dg, uint16_t exclude) {
  std::vector<std::vector<uint8_t>> frames;
  mesh::fragment(dg.data(), dg.size(), FRAME_MTU, frames);
  g_txDatagrams++;
  std::vector<uint16_t> targets;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    targets = g_peers;
  }
  for (uint16_t h : targets) {
    if (h == exclude) continue;
    for (const auto& f : frames) {
      g_packetOut->notify(f.data(), f.size(), h);
      g_txFrames++;
    }
  }
}

static bool sendToNode(const std::vector<uint8_t>& dg, const uint8_t nodeId[mesh::NODE_ID_LEN]) {
  uint16_t target = 0xFFFF;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    for (auto& kv : g_connNode) {
      if (memcmp(kv.second.data(), nodeId, mesh::NODE_ID_LEN) == 0) {
        target = kv.first;
        break;
      }
    }
  }
  if (target == 0xFFFF) return false;
  sendFramesToConn(dg, target);
  return true;
}

#include "remote_admin.h"

static bool isBroadcast(const uint8_t id[mesh::NODE_ID_LEN]) {
  for (size_t i = 0; i < mesh::NODE_ID_LEN; i++) if (id[i] != 0) return false;
  return true;
}

static void learnNeighbor(const mesh::DatagramHeader& h, uint16_t sender) {
  uint8_t nb[mesh::NODE_ID_LEN];
  if (!mesh::directNeighbor(h, nb)) return;
  if (memcmp(nb, g_nodeId, mesh::NODE_ID_LEN) == 0) return;
  std::array<uint8_t, mesh::NODE_ID_LEN> id;
  memcpy(id.data(), nb, mesh::NODE_ID_LEN);
  std::lock_guard<std::mutex> lk(g_peersMu);
  g_connNode[sender] = id;
}

static void onDatagram(const std::vector<uint8_t>& dg, uint16_t sender) {
  mesh::DatagramHeader h = mesh::parseDatagram(dg.data(), dg.size(), g_nodeId);
  if (!h.ok) {
    Serial.println("[relay] drop: malformed datagram");
    return;
  }
  g_rxDatagrams++;
  learnNeighbor(h, sender);
  if (g_dedup.seenOrAdd(h.id)) return;
  if (h.pathContainsSelf) {
    Serial.println("[relay] drop: loop");
    return;
  }
  if (h.ttl == 0 || h.ttl > mesh::MAX_TTL) {
    Serial.println("[relay] drop: bad ttl");
    return;
  }
  if (handleRemoteAdminChat(dg, h, sender)) {
    return;
  }

  bool addressedHere = h.hasDestination && memcmp(h.destination, g_nodeId, mesh::NODE_ID_LEN) == 0;
  if (!h.sourceRouted) {
    if (h.ttl <= 1) return;
    if (addressedHere && !isBroadcast(h.destination)) return;
    std::vector<uint8_t> fwd;
    if (!mesh::buildFloodForward(dg.data(), dg.size(), g_nodeId, h.ttl - 1, fwd)) {
      Serial.println("[relay] drop: flood forward build failed");
      return;
    }
    g_floodRelays++;
    floodToPeers(fwd, sender);
    return;
  }

  if (!h.routeCurrentIsSelf) {
    Serial.println("[relay] drop: not next route hop");
    return;
  }
  if (h.ttl != h.routeLen - h.routeCursor) {
    Serial.println("[relay] drop: source-route ttl mismatch");
    return;
  }
  if (h.routeEndsHere || !h.hasNextHop) {
    Serial.println("[relay] source-route delivered locally (no app handler)");
    return;
  }
  std::vector<uint8_t> fwd;
  if (!mesh::buildSourceRouteForward(dg.data(), dg.size(), g_nodeId, h.ttl - 1, h.routeCursor + 1, fwd)) {
    Serial.println("[relay] drop: source-route forward build failed");
    return;
  }
  if (!sendToNode(fwd, h.nextHop)) {
    Serial.println("[relay] drop: next hop not connected");
    return;
  }
  g_sourceRelays++;
}

static void sendAnnounce() {
  uint8_t id[mesh::DATAGRAM_ID_LEN];
  esp_fill_random(id, sizeof(id));

  std::vector<std::array<uint8_t, mesh::NODE_ID_LEN>> unique;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    for (auto& kv : g_connNode) unique.push_back(kv.second);
  }
  std::sort(unique.begin(), unique.end(), [](const auto& a, const auto& b) {
    return memcmp(a.data(), b.data(), mesh::NODE_ID_LEN) < 0;
  });
  unique.erase(std::unique(unique.begin(), unique.end(), [](const auto& a, const auto& b) {
    return memcmp(a.data(), b.data(), mesh::NODE_ID_LEN) == 0;
  }), unique.end());

  std::vector<uint8_t> neighbors;
  for (auto& nb : unique) neighbors.insert(neighbors.end(), nb.begin(), nb.end());

  uint32_t seq = ++g_announceSeq;
  int64_t ts = internalUnixTime();
  std::vector<uint8_t> signedMsg;
  mesh::announceSignedMessage(g_pubKey, g_epoch, seq, ts, RELAY_CAPS,
                              neighbors.data(), unique.size(), NODE_NAME, NODE_DESCRIPTION,
                              NODE_PLATFORM, signedMsg);
  uint8_t sig[mesh::SIG_LEN];
  ed25519_sign(sig, signedMsg.data(), signedMsg.size(), g_pubKey, g_privKey);

  std::vector<uint8_t> dg;
  mesh::buildAnnounce(g_nodeId, RELAY_CAPS, g_epoch, seq, ts, id, neighbors.data(), unique.size(),
                      g_pubKey, sig, NODE_NAME, NODE_DESCRIPTION, NODE_PLATFORM, dg);
  g_dedup.seenOrAdd(id);
  ledSet(true);
  g_ledOffMs = millis() + LED_BLINK_MS;
  Serial.printf("[relay] ANNOUNCE epoch=%llu seq=%u neighbors=%u\n",
                (unsigned long long)g_epoch, seq, (unsigned)unique.size());
  floodToPeers(dg, 0xFFFF);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo& info) override {
    {
      std::lock_guard<std::mutex> lk(g_peersMu);
      g_peers.push_back(info.getConnHandle());
    }
    Serial.printf("[relay] peer connected handle=%u\n", info.getConnHandle());
    NimBLEDevice::startAdvertising();
  }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo& info, int reason) override {
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
    std::vector<uint8_t> dg;
    if (g_reassembler.addFrame(info.getConnHandle(), val.data(), val.length(), dg)) {
      onDatagram(dg, info.getConnHandle());
    }
  }
};

void setup() {
  Serial.begin(115200);
  delay(200);
  g_bootMs = millis();
  pinMode(LED_PIN, OUTPUT);
  ledSet(false);
  loadOrCreateIdentityAndEpoch();
  loadAdmins();
  Serial.printf("\nBLEEdge v3 relay node=%s phy=1m epoch=%llu\n",
                nodeIdHex().c_str(), (unsigned long long)g_epoch);

  NimBLEDevice::init("BLEEdge");
  NimBLEDevice::setMTU(247);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  server->advertiseOnDisconnect(true);

  NimBLEService* svc = server->createService(SERVICE_UUID);

  std::vector<uint8_t> nodeInfo;
  mesh::buildNodeInfo(g_pubKey, RELAY_CAPS, nodeInfo);
  NimBLECharacteristic* ni = svc->createCharacteristic(NODEINFO_UUID, NIMBLE_PROPERTY::READ);
  ni->setValue(nodeInfo.data(), nodeInfo.size());

  NimBLECharacteristic* pin =
      svc->createCharacteristic(PACKETIN_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  pin->setCallbacks(new PacketInCallbacks());

  g_packetOut = svc->createCharacteristic(PACKETOUT_UUID, NIMBLE_PROPERTY::NOTIFY);
  svc->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  NimBLEAdvertisementData advData;
  advData.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  advData.addServiceUUID(NimBLEUUID(SERVICE_UUID));
  adv->setAdvertisementData(advData);

  uint8_t mfg[2 + mesh::NODE_ID_LEN];
  mfg[0] = 0xED;
  mfg[1] = 0xBE;
  memcpy(mfg + 2, g_nodeId, mesh::NODE_ID_LEN);
  NimBLEAdvertisementData scanData;
  scanData.setManufacturerData(std::string(reinterpret_cast<char*>(mfg), sizeof(mfg)));
  scanData.setName("BLEEdge");
  adv->setScanResponseData(scanData);
  adv->start();
  Serial.println("[relay] advertising started");
}

void loop() {
  uint32_t now = millis();
  processSerialAdmin();
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
