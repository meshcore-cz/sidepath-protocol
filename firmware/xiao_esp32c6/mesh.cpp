#include "mesh.h"

#include <string.h>

namespace mesh {

// ---- CRC32 (IEEE / reflected, poly 0xEDB88320) — matches Go crc32.ChecksumIEEE.
uint32_t crc32_ieee(const uint8_t* data, size_t len) {
  uint32_t crc = 0xFFFFFFFF;
  for (size_t i = 0; i < len; i++) {
    crc ^= data[i];
    for (int k = 0; k < 8; k++) {
      crc = (crc >> 1) ^ (0xEDB88320 & (uint32_t)(-(int32_t)(crc & 1)));
    }
  }
  return ~crc;
}

// ---- minimal CBOR helpers ---------------------------------------------------

// Number of "argument" bytes that follow the initial byte for additional-info `ai`.
static size_t cborArgLen(uint8_t ai) {
  switch (ai) {
    case 24: return 1;
    case 25: return 2;
    case 26: return 4;
    case 27: return 8;
    default: return 0;  // ai < 24 → value is inline
  }
}

static uint64_t cborArgVal(const uint8_t* p, uint8_t ai) {
  if (ai < 24) return ai;
  uint64_t v = 0;
  for (size_t i = 0; i < cborArgLen(ai); i++) v = (v << 8) | p[1 + i];
  return v;
}

// Total byte length of the CBOR item starting at p (0 = malformed/overflow).
static size_t cborItemLen(const uint8_t* p, size_t avail) {
  if (avail < 1) return 0;
  uint8_t mt = p[0] >> 5;
  uint8_t ai = p[0] & 0x1f;
  if (ai > 27 && ai != 31) return 0;  // 28..30 reserved
  size_t head = 1 + cborArgLen(ai);
  if (head > avail) return 0;
  uint64_t arg = cborArgVal(p, ai);
  switch (mt) {
    case 0: case 1: case 7:  // uint, negint, simple/float
      return head;
    case 2: case 3:          // byte string, text string
      if (head + arg > avail) return 0;
      return head + (size_t)arg;
    case 4: {                // array of `arg` items
      size_t pos = head;
      for (uint64_t i = 0; i < arg; i++) {
        size_t l = cborItemLen(p + pos, avail - pos);
        if (!l) return 0;
        pos += l;
      }
      return pos;
    }
    case 5: {                // map of `arg` pairs
      size_t pos = head;
      for (uint64_t i = 0; i < arg * 2; i++) {
        size_t l = cborItemLen(p + pos, avail - pos);
        if (!l) return 0;
        pos += l;
      }
      return pos;
    }
    default: return 0;
  }
}

// Reads a small (single-major-type-0) unsigned int value.
static uint8_t cborSmallUint(const uint8_t* p) {
  uint8_t ai = p[0] & 0x1f;
  if (ai < 24) return ai;
  if (ai == 24) return p[1];
  return 0;  // larger not expected for the fields we read
}

static void emitUint(std::vector<uint8_t>& o, uint64_t v) {
  if (v < 24) {
    o.push_back((uint8_t)v);
  } else if (v < 0x100) {
    o.push_back(0x18); o.push_back((uint8_t)v);
  } else if (v < 0x10000) {
    o.push_back(0x19); o.push_back((uint8_t)(v >> 8)); o.push_back((uint8_t)v);
  } else if (v < 0x100000000ULL) {
    o.push_back(0x1a);
    o.push_back((uint8_t)(v >> 24)); o.push_back((uint8_t)(v >> 16));
    o.push_back((uint8_t)(v >> 8));  o.push_back((uint8_t)v);
  } else {
    o.push_back(0x1b);
    for (int i = 7; i >= 0; i--) o.push_back((uint8_t)(v >> (8 * i)));
  }
}

static void emitBstr(std::vector<uint8_t>& o, const uint8_t* d, size_t n) {
  if (n < 24) {
    o.push_back(0x40 | (uint8_t)n);
  } else if (n < 0x100) {
    o.push_back(0x58); o.push_back((uint8_t)n);
  } else {
    o.push_back(0x59); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n);
  }
  o.insert(o.end(), d, d + n);
}

static void emitInt(std::vector<uint8_t>& o, int64_t v) {
  if (v >= 0) {
    emitUint(o, (uint64_t)v);
    return;
  }
  uint64_t n = (uint64_t)(-1 - v);
  if (n < 24) {
    o.push_back(0x20 | (uint8_t)n);
  } else if (n < 0x100) {
    o.push_back(0x38); o.push_back((uint8_t)n);
  } else if (n < 0x10000) {
    o.push_back(0x39); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n);
  } else {
    o.push_back(0x3a);
    o.push_back((uint8_t)(n >> 24)); o.push_back((uint8_t)(n >> 16));
    o.push_back((uint8_t)(n >> 8));  o.push_back((uint8_t)n);
  }
}

// Emits a CBOR text string (major type 3). Used for the description field so it
// decodes into a Go `string` / Kotlin String (a byte string would not).
static void emitTstr(std::vector<uint8_t>& o, const char* s, size_t n) {
  if (n < 24) {
    o.push_back(0x60 | (uint8_t)n);
  } else if (n < 0x100) {
    o.push_back(0x78); o.push_back((uint8_t)n);
  } else {
    o.push_back(0x79); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n);
  }
  o.insert(o.end(), s, s + n);
}

// ---- DedupCache -------------------------------------------------------------

DedupCache::DedupCache(size_t capacity, uint32_t ttlMs) : ttlMs_(ttlMs), next_(0) {
  entries_.assign(capacity ? capacity : 1, Entry{});
}

bool DedupCache::seenOrAdd(const uint8_t id[PACKET_ID_LEN]) {
  uint32_t now = millis();
  for (auto& e : entries_) {
    if (e.used && memcmp(e.id, id, PACKET_ID_LEN) == 0 && (now - e.seenMs) < ttlMs_) {
      e.seenMs = now;
      return true;
    }
  }
  Entry& slot = entries_[next_];
  next_ = (next_ + 1) % entries_.size();
  memcpy(slot.id, id, PACKET_ID_LEN);
  slot.seenMs = now;
  slot.used = true;
  return false;
}

// ---- Reassembler ------------------------------------------------------------

bool Reassembler::addFrame(const uint8_t* raw, size_t len, std::vector<uint8_t>& out) {
  if (len < FRAME_HEADER) return false;
  uint8_t id[PACKET_ID_LEN];
  memcpy(id, raw + 1, PACKET_ID_LEN);
  uint8_t idx = raw[17];
  uint8_t count = raw[18];
  uint32_t crc = ((uint32_t)raw[19] << 24) | ((uint32_t)raw[20] << 16) |
                 ((uint32_t)raw[21] << 8) | raw[22];
  const uint8_t* data = raw + FRAME_HEADER;
  size_t dlen = len - FRAME_HEADER;
  if (count == 0 || idx >= count) return false;

  Assembly* a = nullptr;
  for (auto& s : slots_) {
    if (s.used && memcmp(s.id, id, PACKET_ID_LEN) == 0) { a = &s; break; }
  }
  if (!a) {
    for (auto& s : slots_) if (!s.used) { a = &s; break; }
    if (!a) {  // evict oldest
      a = &slots_[0];
      for (auto& s : slots_) if (s.lastMs < a->lastMs) a = &s;
    }
    a->used = true;
    memcpy(a->id, id, PACKET_ID_LEN);
    a->count = count;
    a->crc = crc;
    a->frags.assign(count, {});
    a->have.assign(count, false);
  }
  a->lastMs = millis();
  if (!a->have[idx]) {
    a->frags[idx].assign(data, data + dlen);
    a->have[idx] = true;
  }
  for (uint8_t i = 0; i < a->count; i++) if (!a->have[i]) return false;  // incomplete

  out.clear();
  for (uint8_t i = 0; i < a->count; i++)
    out.insert(out.end(), a->frags[i].begin(), a->frags[i].end());

  bool ok = (crc32_ieee(out.data(), out.size()) == a->crc);
  a->used = false;
  a->frags.clear();
  a->have.clear();
  if (!ok) { out.clear(); return false; }
  return true;
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

// ---- packet parse / forward -------------------------------------------------

PacketHeader parseHeader(const uint8_t* pkt, size_t len, const uint8_t selfId[NODE_ID_LEN]) {
  PacketHeader h;
  if (len < 1 || (pkt[0] >> 5) != 5) return h;     // must be a map
  uint8_t ai = pkt[0] & 0x1f;
  if (ai >= 24) return h;                           // only small maps expected
  uint8_t n = ai;
  size_t p = 1;
  for (uint8_t i = 0; i < n; i++) {
    if (p >= len) return h;
    uint8_t key = pkt[p];
    size_t kl = cborItemLen(pkt + p, len - p);
    if (!kl) return h;
    size_t v = p + kl;
    size_t vl = cborItemLen(pkt + v, len - v);
    if (!vl) return h;
    switch (key) {
      case KEY_ID:
        if (pkt[v] == 0x50 && v + 1 + PACKET_ID_LEN <= len) memcpy(h.id, pkt + v + 1, PACKET_ID_LEN);
        break;
      case KEY_TYPE: h.type = cborSmallUint(pkt + v); break;
      case KEY_MODE: h.mode = cborSmallUint(pkt + v); break;
      case KEY_TTL:  h.ttl  = cborSmallUint(pkt + v); break;
      case KEY_ROUTE_CURSOR:
        h.routeCursor = cborSmallUint(pkt + v);
        break;
      case KEY_PAYLOAD_TYPE:
        h.payloadType = cborSmallUint(pkt + v);
        break;
      case KEY_SOURCE:
        if (pkt[v] == 0x48 && v + 1 + NODE_ID_LEN <= len) {
          memcpy(h.source, pkt + v + 1, NODE_ID_LEN);
          h.hasSource = true;
        }
        break;
      case KEY_DEST:
        if (pkt[v] == 0x48 && v + 1 + NODE_ID_LEN <= len) {
          memcpy(h.destination, pkt + v + 1, NODE_ID_LEN);
          h.hasDestination = true;
        }
        break;
      case KEY_ROUTE:
        if ((pkt[v] >> 5) == 4) {
          uint8_t cnt = pkt[v] & 0x1f;
          size_t ep = v + 1;
          for (uint8_t e = 0; e < cnt; e++) {
            if (pkt[ep] == 0x48 && ep + 1 + NODE_ID_LEN <= len) {
              if (e == h.routeCursor && memcmp(pkt + ep + 1, selfId, NODE_ID_LEN) == 0) {
                h.routeCurrentIsSelf = true;
              }
              if (e == (uint8_t)(h.routeCursor + 1)) {
                memcpy(h.nextHop, pkt + ep + 1, NODE_ID_LEN);
                h.hasNextHop = true;
              }
            }
            size_t el = cborItemLen(pkt + ep, len - ep);
            if (!el) return h;
            ep += el;
          }
          h.routeEndsHere = h.routeCurrentIsSelf && ((uint16_t)h.routeCursor + 1 >= cnt);
        }
        break;
      case KEY_TRACE:
        if ((pkt[v] >> 5) == 4) {                   // array of byte strings
          uint8_t cnt = pkt[v] & 0x1f;              // trace is always small
          size_t ep = v + 1;
          for (uint8_t e = 0; e < cnt; e++) {
            if (pkt[ep] == 0x48 && ep + 1 + NODE_ID_LEN <= len) {
              if (memcmp(pkt + ep + 1, selfId, NODE_ID_LEN) == 0) h.traceContainsSelf = true;
              memcpy(h.lastHop, pkt + ep + 1, NODE_ID_LEN);  // overwritten → ends on last element
              h.hasLastHop = true;
            }
            size_t el = cborItemLen(pkt + ep, len - ep);
            if (!el) return h;
            ep += el;
          }
        }
        break;
      case KEY_PAYLOAD:
        if ((pkt[v] >> 5) == 2) {
          uint8_t ai = pkt[v] & 0x1f;
          size_t head = 1 + cborArgLen(ai);
          uint64_t arg = cborArgVal(pkt + v, ai);
          if (v + head + arg <= len) {
            h.payload = pkt + v + head;
            h.payloadLen = (size_t)arg;
          }
        }
        break;
    }
    p = v + vl;
  }
  h.ok = true;
  return h;
}

static void appendMetric(const uint8_t* val, size_t vl, int8_t metric,
                         std::vector<uint8_t>& out) {
  bool isArray = (val != nullptr) && ((val[0] >> 5) == 4) && ((val[0] & 0x1f) < 24);
  uint8_t cnt = isArray ? (val[0] & 0x1f) : 0;
  uint16_t newCnt = cnt + 1;
  if (newCnt < 24) {
    out.push_back(0x80 | (uint8_t)newCnt);
  } else {
    out.push_back(0x98);
    out.push_back((uint8_t)newCnt);
  }
  if (isArray) out.insert(out.end(), val + 1, val + vl);
  emitInt(out, metric);
}

bool directNeighbor(const PacketHeader& h, uint8_t out[NODE_ID_LEN]) {
  if (h.hasLastHop) { memcpy(out, h.lastHop, NODE_ID_LEN); return true; }
  if (h.hasSource)  { memcpy(out, h.source, NODE_ID_LEN);  return true; }
  return false;
}

static void appendTrace(const uint8_t* val, size_t vl, const uint8_t selfId[NODE_ID_LEN],
                        std::vector<uint8_t>& out) {
  bool isArray = (val[0] >> 5) == 4 && (val[0] & 0x1f) < 24;
  uint8_t cnt = isArray ? (val[0] & 0x1f) : 0;     // null or empty → 0
  uint16_t newCnt = cnt + 1;
  if (newCnt < 24) {
    out.push_back(0x80 | (uint8_t)newCnt);
  } else {
    out.push_back(0x98);
    out.push_back((uint8_t)newCnt);
  }
  if (isArray) out.insert(out.end(), val + 1, val + vl);  // existing elements
  out.push_back(0x48);                                     // byte string(8)
  out.insert(out.end(), selfId, selfId + NODE_ID_LEN);
}

bool buildForward(const uint8_t* pkt, size_t len, const uint8_t selfId[NODE_ID_LEN],
                  uint8_t newTtl, std::vector<uint8_t>& out) {
  out.clear();
  if (len < 1 || (pkt[0] >> 5) != 5) return false;
  uint8_t n = pkt[0] & 0x1f;
  if (n >= 24) return false;
  out.push_back(pkt[0]);
  size_t p = 1;
  for (uint8_t i = 0; i < n; i++) {
    if (p >= len) return false;
    uint8_t key = pkt[p];
    size_t kl = cborItemLen(pkt + p, len - p);
    if (!kl) return false;
    size_t v = p + kl;
    size_t vl = cborItemLen(pkt + v, len - v);
    if (!vl) return false;
    if (key == KEY_TTL) {
      out.push_back(pkt[p]);                 // key 0x07
      emitUint(out, newTtl);
    } else if (key == KEY_TRACE) {
      out.push_back(pkt[p]);                 // key 0x0a
      appendTrace(pkt + v, vl, selfId, out);
    } else {
      out.insert(out.end(), pkt + p, pkt + v + vl);  // copy verbatim
    }
    p = v + vl;
  }
  return true;
}

bool buildTraceSourceRouteForward(const uint8_t* pkt, size_t len,
                                  const uint8_t selfId[NODE_ID_LEN],
                                  uint8_t newTtl, uint8_t newRouteCursor,
                                  int8_t metric, std::vector<uint8_t>& out) {
  out.clear();
  if (len < 1 || (pkt[0] >> 5) != 5) return false;
  uint8_t n = pkt[0] & 0x1f;
  if (n >= 24) return false;

  bool sawMetric = false;
  size_t mapHead = out.size();
  out.push_back(pkt[0]);  // fixed after we know whether key 14 exists
  size_t p = 1;
  for (uint8_t i = 0; i < n; i++) {
    if (p >= len) return false;
    uint8_t key = pkt[p];
    size_t kl = cborItemLen(pkt + p, len - p);
    if (!kl) return false;
    size_t v = p + kl;
    size_t vl = cborItemLen(pkt + v, len - v);
    if (!vl) return false;
    if (key == KEY_TTL) {
      out.push_back(pkt[p]);
      emitUint(out, newTtl);
    } else if (key == KEY_ROUTE_CURSOR) {
      out.push_back(pkt[p]);
      emitUint(out, newRouteCursor);
    } else if (key == KEY_TRACE) {
      out.push_back(pkt[p]);
      appendTrace(pkt + v, vl, selfId, out);
    } else if (key == KEY_TRACE_METRIC) {
      sawMetric = true;
      out.push_back(pkt[p]);
      appendMetric(pkt + v, vl, metric, out);
    } else {
      out.insert(out.end(), pkt + p, pkt + v + vl);
    }
    p = v + vl;
  }

  if (!sawMetric) {
    if (n + 1 >= 24) return false;
    out.push_back(KEY_TRACE_METRIC);
    appendMetric(nullptr, 0, metric, out);
    out[mapHead] = 0xa0 | (uint8_t)(n + 1);
  }
  return true;
}

void announceSignedMessage(const uint8_t pubKey[PUBKEY_LEN], uint32_t timestamp,
                           uint8_t caps, uint32_t seq,
                           const uint8_t* neighbors, size_t neighborCount,
                           std::vector<uint8_t>& out) {
  out.clear();
  out.insert(out.end(), pubKey, pubKey + PUBKEY_LEN);
  out.push_back(timestamp & 0xff);
  out.push_back((timestamp >> 8) & 0xff);
  out.push_back((timestamp >> 16) & 0xff);
  out.push_back((timestamp >> 24) & 0xff);
  out.push_back(caps);
  out.push_back(seq & 0xff);
  out.push_back((seq >> 8) & 0xff);
  out.push_back((seq >> 16) & 0xff);
  out.push_back((seq >> 24) & 0xff);
  out.push_back((uint8_t)neighborCount);
  out.insert(out.end(), neighbors, neighbors + neighborCount * NODE_ID_LEN);
}

void buildAnnounce(const uint8_t selfId[NODE_ID_LEN], uint8_t caps, uint32_t seq,
                   int64_t unixSeconds, const uint8_t packetId[PACKET_ID_LEN],
                   const uint8_t* neighbors, size_t neighborCount,
                   const uint8_t pubKey[PUBKEY_LEN], const uint8_t signature[SIG_LEN],
                   const char* description, std::vector<uint8_t>& out) {
  // AnnouncePayload map(8): 1:nodeId 2:caps 3:neighbors 4:seq 5:timestamp
  //                         6:pubkey 7:signature 8:description (text, unsigned)
  size_t descLen = description ? strlen(description) : 0;
  std::vector<uint8_t> ap;
  ap.push_back(0xa0 | 8);
  ap.push_back(1); emitBstr(ap, selfId, NODE_ID_LEN);
  ap.push_back(2); emitUint(ap, caps);
  ap.push_back(3);                                 // neighbors array
  if (neighborCount < 24) {
    ap.push_back(0x80 | (uint8_t)neighborCount);
  } else {
    ap.push_back(0x98);
    ap.push_back((uint8_t)neighborCount);
  }
  for (size_t i = 0; i < neighborCount; i++) emitBstr(ap, neighbors + i * NODE_ID_LEN, NODE_ID_LEN);
  ap.push_back(4); emitUint(ap, seq);
  ap.push_back(5); emitUint(ap, (uint64_t)unixSeconds);
  ap.push_back(6); emitBstr(ap, pubKey, PUBKEY_LEN);
  ap.push_back(7); emitBstr(ap, signature, SIG_LEN);
  ap.push_back(8); emitTstr(ap, description ? description : "", descLen);

  // Packet map(12): mirrors a fresh flood packet with empty route/trace.
  uint8_t zero[NODE_ID_LEN] = {0};
  out.clear();
  out.push_back(0xa0 | 12);
  out.push_back(KEY_VERSION);      emitUint(out, PROTOCOL_VERSION);
  out.push_back(KEY_TYPE);         emitUint(out, TYPE_ANNOUNCE);
  out.push_back(KEY_ID);           emitBstr(out, packetId, PACKET_ID_LEN);
  out.push_back(KEY_SOURCE);       emitBstr(out, selfId, NODE_ID_LEN);
  out.push_back(KEY_DEST);         emitBstr(out, zero, NODE_ID_LEN);
  out.push_back(KEY_MODE);         emitUint(out, MODE_FLOOD);
  out.push_back(KEY_TTL);          emitUint(out, 3);
  out.push_back(KEY_ROUTE_CURSOR); emitUint(out, 0);
  out.push_back(KEY_ROUTE);        out.push_back(0xf6);   // null
  out.push_back(KEY_TRACE);        out.push_back(0xf6);   // null
  out.push_back(KEY_PAYLOAD_TYPE); emitUint(out, PAYLOAD_MESH_CORE_RAW);
  out.push_back(KEY_PAYLOAD);      emitBstr(out, ap.data(), ap.size());
}

void fragment(const uint8_t* pkt, size_t len, size_t mtu, const uint8_t packetId[PACKET_ID_LEN],
              std::vector<std::vector<uint8_t>>& frames) {
  frames.clear();
  size_t maxData = (mtu > FRAME_HEADER) ? (mtu - FRAME_HEADER) : 1;
  uint32_t crc = crc32_ieee(pkt, len);
  size_t nFrames = (len + maxData - 1) / maxData;
  if (nFrames == 0) nFrames = 1;
  for (size_t idx = 0; idx < nFrames; idx++) {
    size_t start = idx * maxData;
    size_t end = start + maxData;
    if (end > len) end = len;
    std::vector<uint8_t> f;
    f.reserve(FRAME_HEADER + (end - start));
    f.push_back(1);  // frame version
    f.insert(f.end(), packetId, packetId + PACKET_ID_LEN);
    f.push_back((uint8_t)idx);
    f.push_back((uint8_t)nFrames);
    f.push_back((uint8_t)(crc >> 24)); f.push_back((uint8_t)(crc >> 16));
    f.push_back((uint8_t)(crc >> 8));  f.push_back((uint8_t)crc);
    f.insert(f.end(), pkt + start, pkt + end);
    frames.push_back(std::move(f));
  }
}

}  // namespace mesh
