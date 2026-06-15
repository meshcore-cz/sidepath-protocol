#include "mesh.h"

#include <string.h>
#ifdef ESP_PLATFORM
#include <esp_random.h>
#endif

namespace mesh {

uint32_t crc32_ieee(const uint8_t* data, size_t len) {
  uint32_t crc = 0xFFFFFFFF;
  for (size_t i = 0; i < len; i++) {
    crc ^= data[i];
    for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320 & (uint32_t)(-(int32_t)(crc & 1)));
  }
  return ~crc;
}

static size_t cborArgLen(uint8_t ai) {
  switch (ai) {
    case 24: return 1;
    case 25: return 2;
    case 26: return 4;
    case 27: return 8;
    default: return 0;
  }
}

static uint64_t cborArgVal(const uint8_t* p, uint8_t ai) {
  if (ai < 24) return ai;
  uint64_t v = 0;
  for (size_t i = 0; i < cborArgLen(ai); i++) v = (v << 8) | p[1 + i];
  return v;
}

static size_t cborItemLen(const uint8_t* p, size_t avail) {
  if (avail < 1) return 0;
  uint8_t mt = p[0] >> 5, ai = p[0] & 0x1f;
  if (ai > 27 && ai != 31) return 0;
  if (ai == 31) return 0;  // indefinite forms are not used by Sidepath.
  size_t head = 1 + cborArgLen(ai);
  if (head > avail) return 0;
  uint64_t arg = cborArgVal(p, ai);
  switch (mt) {
    case 0: case 1: case 7:
      return head;
    case 2: case 3:
      return (head + arg <= avail) ? head + (size_t)arg : 0;
    case 4: {
      size_t pos = head;
      for (uint64_t i = 0; i < arg; i++) {
        size_t l = cborItemLen(p + pos, avail - pos);
        if (!l) return 0;
        pos += l;
      }
      return pos;
    }
    case 5: {
      size_t pos = head;
      for (uint64_t i = 0; i < arg * 2; i++) {
        size_t l = cborItemLen(p + pos, avail - pos);
        if (!l) return 0;
        pos += l;
      }
      return pos;
    }
    default:
      return 0;
  }
}

static bool cborUint(const uint8_t* p, size_t len, uint64_t& out) {
  if (len < 1 || (p[0] >> 5) != 0) return false;
  uint8_t ai = p[0] & 0x1f;
  size_t head = 1 + cborArgLen(ai);
  if (ai > 27 || head > len) return false;
  out = cborArgVal(p, ai);
  return true;
}

static void emitUint(std::vector<uint8_t>& o, uint64_t v) {
  if (v < 24) o.push_back((uint8_t)v);
  else if (v < 0x100) { o.push_back(0x18); o.push_back((uint8_t)v); }
  else if (v < 0x10000) { o.push_back(0x19); o.push_back((uint8_t)(v >> 8)); o.push_back((uint8_t)v); }
  else if (v < 0x100000000ULL) {
    o.push_back(0x1a);
    o.push_back((uint8_t)(v >> 24)); o.push_back((uint8_t)(v >> 16));
    o.push_back((uint8_t)(v >> 8)); o.push_back((uint8_t)v);
  } else {
    o.push_back(0x1b);
    for (int i = 7; i >= 0; i--) o.push_back((uint8_t)(v >> (8 * i)));
  }
}

static void emitInt(std::vector<uint8_t>& o, int64_t v) {
  if (v >= 0) { emitUint(o, (uint64_t)v); return; }
  uint64_t n = (uint64_t)(-1 - v);
  if (n < 24) o.push_back(0x20 | (uint8_t)n);
  else if (n < 0x100) { o.push_back(0x38); o.push_back((uint8_t)n); }
  else if (n < 0x10000) { o.push_back(0x39); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n); }
  else { o.push_back(0x3a); o.push_back((uint8_t)(n >> 24)); o.push_back((uint8_t)(n >> 16)); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n); }
}

static void emitBstr(std::vector<uint8_t>& o, const uint8_t* d, size_t n) {
  if (n < 24) o.push_back(0x40 | (uint8_t)n);
  else if (n < 0x100) { o.push_back(0x58); o.push_back((uint8_t)n); }
  else { o.push_back(0x59); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n); }
  o.insert(o.end(), d, d + n);
}

static void emitTstr(std::vector<uint8_t>& o, const char* s) {
  size_t n = s ? strlen(s) : 0;
  if (n < 24) o.push_back(0x60 | (uint8_t)n);
  else if (n < 0x100) { o.push_back(0x78); o.push_back((uint8_t)n); }
  else { o.push_back(0x79); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n); }
  if (n) o.insert(o.end(), s, s + n);
}

static void emitArrayHeader(std::vector<uint8_t>& o, size_t n) {
  if (n < 24) o.push_back(0x80 | (uint8_t)n);
  else { o.push_back(0x98); o.push_back((uint8_t)n); }
}

static void emitMapHeader(std::vector<uint8_t>& o, size_t n) {
  if (n < 24) o.push_back(0xa0 | (uint8_t)n);
  else { o.push_back(0xb8); o.push_back((uint8_t)n); }
}

static void appendLE16(std::vector<uint8_t>& o, uint16_t v) {
  o.push_back((uint8_t)v); o.push_back((uint8_t)(v >> 8));
}

static void appendLE32(std::vector<uint8_t>& o, uint32_t v) {
  for (int i = 0; i < 4; i++) o.push_back((uint8_t)(v >> (8 * i)));
}

static void appendLE64(std::vector<uint8_t>& o, uint64_t v) {
  for (int i = 0; i < 8; i++) o.push_back((uint8_t)(v >> (8 * i)));
}

static void appendString16(std::vector<uint8_t>& o, const char* s) {
  uint16_t n = s ? (uint16_t)strlen(s) : 0;
  appendLE16(o, n);
  if (n) o.insert(o.end(), s, s + n);
}

DedupCache::DedupCache(size_t capacity, uint32_t ttlMs) : ttlMs_(ttlMs), next_(0) {
  entries_.assign(capacity ? capacity : 1, Entry{});
}

bool DedupCache::seenOrAdd(const uint8_t id[DATAGRAM_ID_LEN]) {
  uint32_t now = millis();
  for (auto& e : entries_) {
    if (e.used && memcmp(e.id, id, DATAGRAM_ID_LEN) == 0 && (now - e.seenMs) < ttlMs_) {
      e.seenMs = now;
      return true;
    }
  }
  Entry& slot = entries_[next_];
  next_ = (next_ + 1) % entries_.size();
  memcpy(slot.id, id, DATAGRAM_ID_LEN);
  slot.seenMs = now;
  slot.used = true;
  return false;
}

bool Reassembler::addFrame(uint16_t peerLink, const uint8_t* raw, size_t len, std::vector<uint8_t>& out) {
  if (len < FRAME_HEADER || raw[0] != FRAME_VERSION) return false;
  const uint8_t* tid = raw + 1;
  uint8_t idx = raw[17], count = raw[18];
  uint32_t crc = ((uint32_t)raw[19] << 24) | ((uint32_t)raw[20] << 16) | ((uint32_t)raw[21] << 8) | raw[22];
  if (count == 0 || idx >= count) return false;
  const uint8_t* data = raw + FRAME_HEADER;
  size_t dlen = len - FRAME_HEADER;

  Assembly* a = nullptr;
  for (auto& s : slots_) {
    if (s.used && s.peer == peerLink && memcmp(s.transferId, tid, TRANSFER_ID_LEN) == 0) { a = &s; break; }
  }
  if (!a) {
    for (auto& s : slots_) if (!s.used) { a = &s; break; }
    if (!a) {
      a = &slots_[0];
      for (auto& s : slots_) if (s.lastMs < a->lastMs) a = &s;
    }
    a->used = true;
    a->peer = peerLink;
    memcpy(a->transferId, tid, TRANSFER_ID_LEN);
    a->count = count;
    a->crc = crc;
    a->frags.assign(count, {});
    a->have.assign(count, false);
  }
  if (a->count != count || a->crc != crc) { a->used = false; return false; }
  a->lastMs = millis();
  if (!a->have[idx]) {
    a->frags[idx].assign(data, data + dlen);
    a->have[idx] = true;
  }
  for (uint8_t i = 0; i < a->count; i++) if (!a->have[i]) return false;

  out.clear();
  for (uint8_t i = 0; i < a->count; i++) out.insert(out.end(), a->frags[i].begin(), a->frags[i].end());
  bool ok = crc32_ieee(out.data(), out.size()) == a->crc;
  a->used = false;
  a->frags.clear();
  a->have.clear();
  if (!ok) out.clear();
  return ok;
}

void Reassembler::reap() {
  uint32_t now = millis();
  for (auto& s : slots_) {
    if (s.used && (now - s.lastMs) > timeoutMs_) {
      s.used = false;
      s.frags.clear();
      s.have.clear();
    }
  }
}

static bool readNodeArray(const uint8_t* val, size_t vl, const uint8_t selfId[NODE_ID_LEN],
                          uint8_t& count, bool& containsSelf, uint8_t last[NODE_ID_LEN],
                          bool& hasLast, uint8_t cursor, bool routeMode,
                          bool& currentIsSelf, uint8_t nextHop[NODE_ID_LEN], bool& hasNext,
                          bool& endsHere) {
  if ((val[0] >> 5) != 4) return false;
  uint8_t ai = val[0] & 0x1f;
  if (ai >= 24) return false;
  count = ai;
  size_t p = 1;
  for (uint8_t i = 0; i < count; i++) {
    if (p >= vl) return false;
    size_t el = cborItemLen(val + p, vl - p);
    if (!el) return false;
    if (val[p] == (0x40 | NODE_ID_LEN) && p + 1 + NODE_ID_LEN <= vl) {
      const uint8_t* id = val + p + 1;
      if (memcmp(id, selfId, NODE_ID_LEN) == 0) containsSelf = true;
      memcpy(last, id, NODE_ID_LEN); hasLast = true;
      if (routeMode && i == cursor && memcmp(id, selfId, NODE_ID_LEN) == 0) currentIsSelf = true;
      if (routeMode && i == (uint8_t)(cursor + 1)) { memcpy(nextHop, id, NODE_ID_LEN); hasNext = true; }
    }
    p += el;
  }
  if (routeMode) endsHere = currentIsSelf && ((uint16_t)cursor + 1 >= count);
  return true;
}

static void parseControl(DatagramHeader& h) {
  if (h.protocol != PROTO_SIDEPATH_CONTROL || h.payload == nullptr || h.payloadLen < 1) return;
  const uint8_t* p = h.payload;
  size_t len = h.payloadLen;
  if ((p[0] >> 5) != 5) return;
  uint8_t n = p[0] & 0x1f;
  if (n >= 24) return;
  size_t pos = 1;
  for (uint8_t i = 0; i < n; i++) {
    size_t kl = cborItemLen(p + pos, len - pos);
    if (!kl) return;
    size_t v = pos + kl;
    size_t vl = cborItemLen(p + v, len - v);
    if (!vl) return;
    uint64_t key = 0, val = 0;
    if (cborUint(p + pos, kl, key) && key == 1 && cborUint(p + v, vl, val)) h.controlKind = (uint8_t)val;
    pos = v + vl;
  }
}

DatagramHeader parseDatagram(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN]) {
  DatagramHeader h;
  if (len < 1 || (dg[0] >> 5) != 5) return h;
  uint8_t ai = dg[0] & 0x1f;
  if (ai >= 24) return h;
  const uint8_t* routeVal = nullptr;
  size_t routeLen = 0;
  const uint8_t* pathVal = nullptr;
  size_t pathLen = 0;
  size_t p = 1;
  for (uint8_t i = 0; i < ai; i++) {
    size_t kl = cborItemLen(dg + p, len - p);
    if (!kl) return h;
    size_t v = p + kl;
    size_t vl = cborItemLen(dg + v, len - v);
    if (!vl) return h;
    uint64_t key = 0, u = 0;
    if (!cborUint(dg + p, kl, key)) return h;
    switch (key) {
      case KEY_VERSION:
        if (!cborUint(dg + v, vl, u) || u != DATAGRAM_VERSION) return h;
        break;
      case KEY_ID:
        if (dg[v] == (0x40 | DATAGRAM_ID_LEN) && v + 1 + DATAGRAM_ID_LEN <= len) memcpy(h.id, dg + v + 1, DATAGRAM_ID_LEN);
        else return h;
        break;
      case KEY_SOURCE:
        if (dg[v] == (0x40 | NODE_ID_LEN) && v + 1 + NODE_ID_LEN <= len) { memcpy(h.source, dg + v + 1, NODE_ID_LEN); h.hasSource = true; }
        else return h;
        break;
      case KEY_DEST:
        if (dg[v] == (0x40 | NODE_ID_LEN) && v + 1 + NODE_ID_LEN <= len) { memcpy(h.destination, dg + v + 1, NODE_ID_LEN); h.hasDestination = true; }
        else return h;
        break;
      case KEY_TTL:
        if (!cborUint(dg + v, vl, u)) return h;
        h.ttl = (uint8_t)u;
        break;
      case KEY_ROUTE:
        h.sourceRouted = true;
        routeVal = dg + v;
        routeLen = vl;
        break;
      case KEY_ROUTE_CURSOR:
        if (!cborUint(dg + v, vl, u)) return h;
        h.routeCursor = (uint8_t)u;
        break;
      case KEY_PATH: {
        pathVal = dg + v;
        pathLen = vl;
        break;
      }
      case KEY_PROTOCOL:
        if (!cborUint(dg + v, vl, u)) return h;
        h.protocol = (uint16_t)u;
        break;
      case KEY_FLAGS:
        if (!cborUint(dg + v, vl, u)) return h;
        h.flags = (uint16_t)u;
        break;
      case KEY_PAYLOAD: {
        if ((dg[v] >> 5) != 2) return h;
        uint8_t bai = dg[v] & 0x1f;
        size_t head = 1 + cborArgLen(bai);
        uint64_t bl = cborArgVal(dg + v, bai);
        if (v + head + bl > len) return h;
        h.payload = dg + v + head;
        h.payloadLen = (size_t)bl;
        break;
      }
    }
    p = v + vl;
  }
  if (pathVal) {
    bool dummyCur = false, dummyNext = false, dummyEnd = false;
    uint8_t next[NODE_ID_LEN];
    if (!readNodeArray(pathVal, pathLen, selfId, h.pathLen, h.pathContainsSelf, h.lastHop, h.hasLastHop,
                       0, false, dummyCur, next, dummyNext, dummyEnd)) return DatagramHeader{};
  }
  if (routeVal) {
    bool routeContainsSelf = false;
    uint8_t ignoredLast[NODE_ID_LEN];
    bool ignoredHasLast = false;
    if (!readNodeArray(routeVal, routeLen, selfId, h.routeLen, routeContainsSelf, ignoredLast, ignoredHasLast,
                       h.routeCursor, true, h.routeCurrentIsSelf, h.nextHop, h.hasNextHop, h.routeEndsHere)) {
      return DatagramHeader{};
    }
  }
  h.ok = h.hasSource && h.hasDestination && h.ttl > 0 && h.ttl <= MAX_TTL;
  parseControl(h);
  return h;
}

bool directNeighbor(const DatagramHeader& h, uint8_t out[NODE_ID_LEN]) {
  if (h.hasLastHop) { memcpy(out, h.lastHop, NODE_ID_LEN); return true; }
  if (h.hasSource) { memcpy(out, h.source, NODE_ID_LEN); return true; }
  return false;
}

static void appendPath(const uint8_t* val, size_t vl, const uint8_t selfId[NODE_ID_LEN],
                       std::vector<uint8_t>& out) {
  uint8_t cnt = 0;
  bool isArray = val && ((val[0] >> 5) == 4) && ((val[0] & 0x1f) < 24);
  if (isArray) cnt = val[0] & 0x1f;
  emitArrayHeader(out, cnt + 1);
  if (isArray) out.insert(out.end(), val + 1, val + vl);
  emitBstr(out, selfId, NODE_ID_LEN);
}

static bool buildForwardCommon(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN],
                               uint8_t newTtl, bool updateCursor, uint8_t newRouteCursor,
                               std::vector<uint8_t>& out) {
  out.clear();
  if (len < 1 || (dg[0] >> 5) != 5) return false;
  uint8_t n = dg[0] & 0x1f;
  if (n >= 24) return false;
  bool sawPath = false, sawCursor = false;
  emitMapHeader(out, n);
  size_t mapHead = 0, p = 1;
  for (uint8_t i = 0; i < n; i++) {
    size_t kl = cborItemLen(dg + p, len - p);
    if (!kl) return false;
    size_t v = p + kl;
    size_t vl = cborItemLen(dg + v, len - v);
    if (!vl) return false;
    uint64_t key = 0;
    if (!cborUint(dg + p, kl, key)) return false;
    if (key == KEY_TTL) {
      out.insert(out.end(), dg + p, dg + p + kl);
      emitUint(out, newTtl);
    } else if (key == KEY_ROUTE_CURSOR && updateCursor) {
      sawCursor = true;
      out.insert(out.end(), dg + p, dg + p + kl);
      emitUint(out, newRouteCursor);
    } else if (key == KEY_PATH) {
      sawPath = true;
      out.insert(out.end(), dg + p, dg + p + kl);
      appendPath(dg + v, vl, selfId, out);
    } else {
      out.insert(out.end(), dg + p, dg + v + vl);
    }
    p = v + vl;
  }
  if (!sawPath || (updateCursor && !sawCursor)) {
    if (n + (sawPath ? 0 : 1) + ((updateCursor && !sawCursor) ? 1 : 0) >= 24) return false;
    out[mapHead] = 0xa0 | (uint8_t)(n + (sawPath ? 0 : 1) + ((updateCursor && !sawCursor) ? 1 : 0));
    if (updateCursor && !sawCursor) { emitUint(out, KEY_ROUTE_CURSOR); emitUint(out, newRouteCursor); }
    if (!sawPath) { emitUint(out, KEY_PATH); appendPath(nullptr, 0, selfId, out); }
  }
  return true;
}

bool buildFloodForward(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN],
                       uint8_t newTtl, std::vector<uint8_t>& out) {
  return buildForwardCommon(dg, len, selfId, newTtl, false, 0, out);
}

bool buildSourceRouteForward(const uint8_t* dg, size_t len, const uint8_t selfId[NODE_ID_LEN],
                             uint8_t newTtl, uint8_t newRouteCursor,
                             std::vector<uint8_t>& out) {
  return buildForwardCommon(dg, len, selfId, newTtl, true, newRouteCursor, out);
}

void announceSignedMessage(const uint8_t pubKey[PUBKEY_LEN], uint64_t epoch, uint32_t seq,
                           int64_t timestamp, uint16_t caps, uint8_t version,
                           const uint8_t* neighbors, size_t neighborCount,
                           const AnnounceNeighborInfo* infos, size_t infoCount,
                           const char* name, const char* description, const char* platform,
                           std::vector<uint8_t>& out) {
  out.clear();
  const char prefix[] = "SIDEPATH-ANNOUNCE-V1";
  out.insert(out.end(), prefix, prefix + sizeof(prefix));  // includes trailing NUL.
  out.push_back(version);
  out.insert(out.end(), pubKey, pubKey + PUBKEY_LEN);
  appendLE64(out, epoch);
  appendLE32(out, seq);
  appendLE64(out, (uint64_t)timestamp);
  appendLE16(out, caps);
  appendLE16(out, (uint16_t)neighborCount);
  if (neighbors && neighborCount) out.insert(out.end(), neighbors, neighbors + neighborCount * NODE_ID_LEN);
  appendString16(out, name ? name : "");
  appendString16(out, description ? description : "");
  appendString16(out, platform ? platform : "");
  // v2 appends a bridges section before neighbor_info; this node bridges nothing, so count is 0.
  if (version >= 2) appendLE16(out, 0);
  // v3 appends the neighbor_info section (§8.8): nbrinfo_count[2 LE] then per entry (23 bytes)
  // id[10] | rssi[1 int8] | tx_phy[1] | rx_phy[1] | dir[1] | age_s[4 LE] | transport[1] |
  // rssi_ewma[1 int8] | quality_q8[1] | latency_ms[2 LE] | queue_q8[1]. Entries are sorted, unique.
  if (version >= 3) {
    appendLE16(out, (uint16_t)infoCount);
    for (size_t i = 0; i < infoCount; i++) {
      out.insert(out.end(), infos[i].id, infos[i].id + NODE_ID_LEN);
      out.push_back((uint8_t)infos[i].rssi);
      out.push_back(infos[i].txPhy);
      out.push_back(infos[i].rxPhy);
      out.push_back(infos[i].dir);
      appendLE32(out, infos[i].ageS);
      out.push_back(infos[i].transport);
      out.push_back((uint8_t)infos[i].rssiEwma);
      out.push_back(infos[i].qualityQ8);
      appendLE16(out, infos[i].latencyMs);
      out.push_back(infos[i].queueQ8);
    }
  }
}

// Emits one CBOR neighbor_info entry {1:id, [2:rssi,3:txPhy,4:rxPhy,5:dir,6:ageS, 7:transport,
// 8:rssiEwma,9:qualityQ8,10:latencyMs,11:queueQ8]} into [o]. Keys 2-11 are omitted when zero,
// matching the Go encoder's omitempty (decoders default missing keys to 0).
static void emitNeighborInfo(std::vector<uint8_t>& o, const AnnounceNeighborInfo& n) {
  uint8_t fields = 1;  // key 1 (id) is always present
  if (n.rssi != 0) fields++;
  if (n.txPhy != 0) fields++;
  if (n.rxPhy != 0) fields++;
  if (n.dir != 0) fields++;
  if (n.ageS != 0) fields++;
  if (n.transport != 0) fields++;
  if (n.rssiEwma != 0) fields++;
  if (n.qualityQ8 != 0) fields++;
  if (n.latencyMs != 0) fields++;
  if (n.queueQ8 != 0) fields++;
  emitMapHeader(o, fields);
  emitUint(o, 1); emitBstr(o, n.id, NODE_ID_LEN);
  if (n.rssi != 0) { emitUint(o, 2); emitInt(o, n.rssi); }
  if (n.txPhy != 0) { emitUint(o, 3); emitUint(o, n.txPhy); }
  if (n.rxPhy != 0) { emitUint(o, 4); emitUint(o, n.rxPhy); }
  if (n.dir != 0) { emitUint(o, 5); emitUint(o, n.dir); }
  if (n.ageS != 0) { emitUint(o, 6); emitUint(o, n.ageS); }
  if (n.transport != 0) { emitUint(o, 7); emitUint(o, n.transport); }
  if (n.rssiEwma != 0) { emitUint(o, 8); emitInt(o, n.rssiEwma); }
  if (n.qualityQ8 != 0) { emitUint(o, 9); emitUint(o, n.qualityQ8); }
  if (n.latencyMs != 0) { emitUint(o, 10); emitUint(o, n.latencyMs); }
  if (n.queueQ8 != 0) { emitUint(o, 11); emitUint(o, n.queueQ8); }
}

void buildAnnounce(const uint8_t selfId[NODE_ID_LEN], uint16_t caps, uint64_t epoch,
                   uint32_t seq, int64_t unixSeconds, const uint8_t datagramId[DATAGRAM_ID_LEN],
                   uint8_t version, const uint8_t* neighbors, size_t neighborCount,
                   const AnnounceNeighborInfo* infos, size_t infoCount,
                   const uint8_t pubKey[PUBKEY_LEN], const uint8_t signature[SIG_LEN],
                   const char* name, const char* description, const char* platform,
                   std::vector<uint8_t>& out) {
  std::vector<uint8_t> body;
  // v3 adds key 13 (neighbor_info); v1/v2 keep the 11-key body. The bare list (key 7) stays present
  // but empty on v3. This node bridges nothing, so key 12 is omitted on every version (omitempty).
  emitMapHeader(body, version >= 3 ? 12 : 11);
  emitUint(body, 1); emitUint(body, version);
  emitUint(body, 2); emitBstr(body, pubKey, PUBKEY_LEN);
  emitUint(body, 3); emitUint(body, epoch);
  emitUint(body, 4); emitUint(body, seq);
  emitUint(body, 5); emitInt(body, unixSeconds);
  emitUint(body, 6); emitUint(body, caps);
  emitUint(body, 7); emitArrayHeader(body, neighborCount);
  for (size_t i = 0; i < neighborCount; i++) emitBstr(body, neighbors + i * NODE_ID_LEN, NODE_ID_LEN);
  emitUint(body, 8); emitTstr(body, name ? name : "");
  emitUint(body, 9); emitTstr(body, description ? description : "");
  emitUint(body, 10); emitTstr(body, platform ? platform : "");
  emitUint(body, 11); emitBstr(body, signature, SIG_LEN);
  if (version >= 3) {
    emitUint(body, 13); emitArrayHeader(body, infoCount);
    for (size_t i = 0; i < infoCount; i++) emitNeighborInfo(body, infos[i]);
  }

  std::vector<uint8_t> ctrl;
  emitMapHeader(ctrl, 2);
  emitUint(ctrl, 1); emitUint(ctrl, CONTROL_ANNOUNCE);
  emitUint(ctrl, 2); ctrl.insert(ctrl.end(), body.begin(), body.end());

  uint8_t zero[NODE_ID_LEN] = {0};
  out.clear();
  emitMapHeader(out, 7);
  emitUint(out, KEY_VERSION); emitUint(out, DATAGRAM_VERSION);
  emitUint(out, KEY_ID); emitBstr(out, datagramId, DATAGRAM_ID_LEN);
  emitUint(out, KEY_SOURCE); emitBstr(out, selfId, NODE_ID_LEN);
  emitUint(out, KEY_DEST); emitBstr(out, zero, NODE_ID_LEN);
  emitUint(out, KEY_TTL); emitUint(out, ANNOUNCE_TTL);
  emitUint(out, KEY_PROTOCOL); emitUint(out, PROTO_SIDEPATH_CONTROL);
  emitUint(out, KEY_PAYLOAD); emitBstr(out, ctrl.data(), ctrl.size());
}

void buildNodeInfo(const uint8_t pubKey[PUBKEY_LEN], uint16_t caps, std::vector<uint8_t>& out) {
  out.clear();
  out.push_back(NODE_INFO_VERSION);
  out.insert(out.end(), pubKey, pubKey + PUBKEY_LEN);
  out.push_back((uint8_t)caps);
  out.push_back((uint8_t)(caps >> 8));
}

// Finds the value of integer [wantKey] in a CBOR map [m], returning a pointer/len into [m]. Used to
// walk the datagram envelope, the control message, and the trace body (all keyasint maps).
static bool findMapValue(const uint8_t* m, size_t len, uint64_t wantKey,
                         const uint8_t** valOut, size_t* valLenOut) {
  if (len < 1 || (m[0] >> 5) != 5) return false;  // major type 5 = map
  uint8_t ai = m[0] & 0x1f;
  if (ai >= 24) return false;
  size_t p = 1;
  for (uint8_t i = 0; i < ai; i++) {
    size_t kl = cborItemLen(m + p, len - p);
    if (!kl) return false;
    size_t v = p + kl;
    size_t vl = cborItemLen(m + v, len - v);
    if (!vl) return false;
    uint64_t key = 0;
    if (cborUint(m + p, kl, key) && key == wantKey) {
      *valOut = m + v;
      *valLenOut = vl;
      return true;
    }
    p = v + vl;
  }
  return false;
}

// Reads a CBOR array of NODE_ID_LEN byte strings at [arr], appending each to [out].
static void collectNodes(const uint8_t* arr, size_t len,
                         std::vector<std::array<uint8_t, NODE_ID_LEN>>& out) {
  if (!arr || len < 1 || (arr[0] >> 5) != 4) return;  // major type 4 = array
  uint8_t ai = arr[0] & 0x1f;
  if (ai >= 24) return;
  size_t pos = 1;
  for (uint8_t i = 0; i < ai; i++) {
    if (pos >= len || arr[pos] != (0x40 | NODE_ID_LEN) || pos + 1 + NODE_ID_LEN > len) return;
    std::array<uint8_t, NODE_ID_LEN> id;
    memcpy(id.data(), arr + pos + 1, NODE_ID_LEN);
    out.push_back(id);
    pos += 1 + NODE_ID_LEN;
  }
}

bool buildTraceResponse(const DatagramHeader& req, const uint8_t* reqDg, size_t reqLen,
                        const uint8_t selfId[NODE_ID_LEN], const uint8_t datagramId[DATAGRAM_ID_LEN],
                        std::vector<uint8_t>& out, uint32_t& tagOut) {
  if (!req.payload || req.payloadLen < 1) return false;
  // Control message map → body (key 2) → trace request fields: tag (key 1), metric (key 2).
  const uint8_t* body = nullptr; size_t bodyLen = 0;
  if (!findMapValue(req.payload, req.payloadLen, 2, &body, &bodyLen)) return false;
  const uint8_t* tagVal = nullptr; size_t tagValLen = 0;
  uint64_t tag = 0, metric = 1;  // metric default = RSSI_DBM (1)
  if (!findMapValue(body, bodyLen, 1, &tagVal, &tagValLen) || !cborUint(tagVal, tagValLen, tag)) return false;
  const uint8_t* metVal = nullptr; size_t metValLen = 0;
  if (findMapValue(body, bodyLen, 2, &metVal, &metValLen)) cborUint(metVal, metValLen, metric);
  tagOut = (uint32_t)tag;

  // Forward path = the relays the request crossed (KEY_PATH); the destination never records itself.
  std::vector<std::array<uint8_t, NODE_ID_LEN>> path;
  const uint8_t* pathArr = nullptr; size_t pathArrLen = 0;
  if (findMapValue(reqDg, reqLen, KEY_PATH, &pathArr, &pathArrLen)) collectNodes(pathArr, pathArrLen, path);

  // Return route = reversed relays + the originator.
  std::vector<std::array<uint8_t, NODE_ID_LEN>> route;
  for (size_t i = path.size(); i-- > 0;) route.push_back(path[i]);
  std::array<uint8_t, NODE_ID_LEN> src;
  memcpy(src.data(), req.source, NODE_ID_LEN);
  route.push_back(src);
  if (route.size() > MAX_ROUTE_HOPS) return false;

  // TraceResponseBody: {1:tag, 2:metric, 3:forwardPath, 4:forwardSamples=[], 5:returnSamples=[]}.
  std::vector<uint8_t> tbody;
  emitMapHeader(tbody, 5);
  emitUint(tbody, 1); emitUint(tbody, tag);
  emitUint(tbody, 2); emitUint(tbody, metric);
  emitUint(tbody, 3); emitArrayHeader(tbody, path.size());
  for (auto& n : path) emitBstr(tbody, n.data(), NODE_ID_LEN);
  emitUint(tbody, 4); emitArrayHeader(tbody, 0);
  emitUint(tbody, 5); emitArrayHeader(tbody, 0);

  std::vector<uint8_t> ctrl;
  emitMapHeader(ctrl, 2);
  emitUint(ctrl, 1); emitUint(ctrl, CONTROL_TRACE_RESPONSE);
  emitUint(ctrl, 2); ctrl.insert(ctrl.end(), tbody.begin(), tbody.end());

  out.clear();
  emitMapHeader(out, 9);
  emitUint(out, KEY_VERSION); emitUint(out, DATAGRAM_VERSION);
  emitUint(out, KEY_ID); emitBstr(out, datagramId, DATAGRAM_ID_LEN);
  emitUint(out, KEY_SOURCE); emitBstr(out, selfId, NODE_ID_LEN);
  emitUint(out, KEY_DEST); emitBstr(out, req.source, NODE_ID_LEN);
  emitUint(out, KEY_TTL); emitUint(out, route.size());
  emitUint(out, KEY_ROUTE); emitArrayHeader(out, route.size());
  for (auto& n : route) emitBstr(out, n.data(), NODE_ID_LEN);
  emitUint(out, KEY_ROUTE_CURSOR); emitUint(out, 0);
  emitUint(out, KEY_PROTOCOL); emitUint(out, PROTO_SIDEPATH_CONTROL);
  emitUint(out, KEY_PAYLOAD); emitBstr(out, ctrl.data(), ctrl.size());
  return true;
}

void randomTransferId(uint8_t out[TRANSFER_ID_LEN]) {
#ifdef ESP_PLATFORM
  esp_fill_random(out, TRANSFER_ID_LEN);
#else
  for (size_t i = 0; i < TRANSFER_ID_LEN; i++) out[i] = (uint8_t)(0xA0 + i);
#endif
}

void fragment(const uint8_t* dg, size_t len, size_t mtu, std::vector<std::vector<uint8_t>>& frames) {
  frames.clear();
  size_t maxData = (mtu > FRAME_HEADER) ? (mtu - FRAME_HEADER) : 1;
  uint32_t crc = crc32_ieee(dg, len);
  uint8_t tid[TRANSFER_ID_LEN];
  randomTransferId(tid);
  size_t nFrames = (len + maxData - 1) / maxData;
  if (nFrames == 0) nFrames = 1;
  for (size_t idx = 0; idx < nFrames; idx++) {
    size_t start = idx * maxData;
    size_t end = start + maxData;
    if (end > len) end = len;
    std::vector<uint8_t> f;
    f.reserve(FRAME_HEADER + (end - start));
    f.push_back(FRAME_VERSION);
    f.insert(f.end(), tid, tid + TRANSFER_ID_LEN);
    f.push_back((uint8_t)idx);
    f.push_back((uint8_t)nFrames);
    f.push_back((uint8_t)(crc >> 24)); f.push_back((uint8_t)(crc >> 16));
    f.push_back((uint8_t)(crc >> 8)); f.push_back((uint8_t)crc);
    f.insert(f.end(), dg + start, dg + end);
    frames.push_back(std::move(f));
  }
}

}  // namespace mesh
