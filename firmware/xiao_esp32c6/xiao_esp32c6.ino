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
// initiate connections), source-route forwarding only for TRACE packets where
// this relay is on the selected track, and 1M PHY (the default mesh PHY).

#include <NimBLEDevice.h>
#include <Preferences.h>
#include <esp_random.h>
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#include <mbedtls/bignum.h>
#include <mbedtls/sha512.h>
#include <mbedtls/version.h>

#include <algorithm>
#include <array>
#include <string>
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
static const char*    NODE_PLATFORM = "esp32-c6";     // OS/device string in ANNOUNCE/NODE_INFO
static const char*    NODE_DESCRIPTION = "";          // free-form bio (empty by default)
// name="" => peers derive the deterministic DefaultNodeName from our pubkey.
static const char*    NODE_NAME = "";
// Build-time remote-control admins. Put 32-byte Ed25519 public keys here as
// lowercase/uppercase hex. Runtime admins added with admin.add are stored in NVS.
static const char* ADMIN_PUBKEYS[] = {
#ifdef BLEEDGE_ADMIN_PUBKEY
  BLEEDGE_ADMIN_PUBKEY,
#endif
};

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
static uint32_t g_bootMs = 0;

static std::vector<std::array<uint8_t, mesh::PUBKEY_LEN>> g_builtinAdmins;
static std::vector<std::array<uint8_t, mesh::PUBKEY_LEN>> g_runtimeAdmins;

static uint32_t g_rxPackets = 0;
static uint32_t g_txPackets = 0;
static uint32_t g_txFrames = 0;
static uint32_t g_floodRelays = 0;
static uint32_t g_traceRelays = 0;
static uint32_t g_cmdAccepted = 0;
static uint32_t g_cmdDenied = 0;

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

static String bytesHex(const uint8_t* data, size_t len) {
  String s;
  for (size_t i = 0; i < len; i++) {
    char b[3];
    snprintf(b, sizeof(b), "%02x", data[i]);
    s += b;
  }
  return s;
}

static bool hexNibble(char c, uint8_t& out) {
  if (c >= '0' && c <= '9') { out = c - '0'; return true; }
  if (c >= 'a' && c <= 'f') { out = c - 'a' + 10; return true; }
  if (c >= 'A' && c <= 'F') { out = c - 'A' + 10; return true; }
  return false;
}

static bool parseHexBytes(const String& hex, uint8_t* out, size_t len) {
  if (hex.length() != len * 2) return false;
  for (size_t i = 0; i < len; i++) {
    uint8_t hi, lo;
    if (!hexNibble(hex[i * 2], hi) || !hexNibble(hex[i * 2 + 1], lo)) return false;
    out[i] = (hi << 4) | lo;
  }
  return true;
}

static bool samePub(const std::array<uint8_t, mesh::PUBKEY_LEN>& a, const uint8_t* b) {
  return memcmp(a.data(), b, mesh::PUBKEY_LEN) == 0;
}

static bool containsAdmin(const std::vector<std::array<uint8_t, mesh::PUBKEY_LEN>>& admins,
                          const uint8_t* pub) {
  for (const auto& a : admins) if (samePub(a, pub)) return true;
  return false;
}

static bool isAdminPub(const uint8_t* pub) {
  return containsAdmin(g_builtinAdmins, pub) || containsAdmin(g_runtimeAdmins, pub);
}

static void saveRuntimeAdmins() {
  Preferences prefs;
  prefs.begin("bleadmin", false);
  std::vector<uint8_t> blob;
  blob.reserve(g_runtimeAdmins.size() * mesh::PUBKEY_LEN);
  for (const auto& a : g_runtimeAdmins) blob.insert(blob.end(), a.begin(), a.end());
  if (blob.empty()) prefs.remove("pubkeys");
  else prefs.putBytes("pubkeys", blob.data(), blob.size());
  prefs.end();
}

static void loadAdmins() {
  g_builtinAdmins.clear();
  g_runtimeAdmins.clear();
  for (const char* h : ADMIN_PUBKEYS) {
    if (!h || !*h) continue;
    std::array<uint8_t, mesh::PUBKEY_LEN> pub{};
    if (parseHexBytes(String(h), pub.data(), pub.size()) && !containsAdmin(g_builtinAdmins, pub.data())) {
      g_builtinAdmins.push_back(pub);
    }
  }
  Preferences prefs;
  prefs.begin("bleadmin", true);
  size_t n = prefs.getBytesLength("pubkeys");
  if (n > 0 && n % mesh::PUBKEY_LEN == 0) {
    std::vector<uint8_t> blob(n);
    prefs.getBytes("pubkeys", blob.data(), blob.size());
    for (size_t i = 0; i < blob.size(); i += mesh::PUBKEY_LEN) {
      std::array<uint8_t, mesh::PUBKEY_LEN> pub{};
      memcpy(pub.data(), blob.data() + i, mesh::PUBKEY_LEN);
      if (!containsAdmin(g_builtinAdmins, pub.data()) && !containsAdmin(g_runtimeAdmins, pub.data())) {
        g_runtimeAdmins.push_back(pub);
      }
    }
  }
  prefs.end();
}

static void cborEmitUint(std::vector<uint8_t>& o, uint64_t v) {
  if (v < 24) {
    o.push_back((uint8_t)v);
  } else if (v < 0x100) {
    o.push_back(0x18); o.push_back((uint8_t)v);
  } else if (v < 0x10000) {
    o.push_back(0x19); o.push_back((uint8_t)(v >> 8)); o.push_back((uint8_t)v);
  } else {
    o.push_back(0x1a);
    o.push_back((uint8_t)(v >> 24)); o.push_back((uint8_t)(v >> 16));
    o.push_back((uint8_t)(v >> 8));  o.push_back((uint8_t)v);
  }
}

// Emits a signed CBOR integer (major type 0 for >=0, major type 1 for negative). Needed so a
// negative trace tag round-trips to Kotlin's CBORObject.AsInt32() (an unsigned encoding of a
// value > 2^31 would throw there).
static void cborEmitInt(std::vector<uint8_t>& o, int32_t v) {
  if (v >= 0) {
    cborEmitUint(o, (uint64_t)v);
    return;
  }
  uint32_t n = (uint32_t)(-(int64_t)v - 1);  // CBOR negative: encodes -(n+1)
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

static void cborEmitBstr(std::vector<uint8_t>& o, const uint8_t* d, size_t n) {
  if (n < 24) {
    o.push_back(0x40 | (uint8_t)n);
  } else if (n < 0x100) {
    o.push_back(0x58); o.push_back((uint8_t)n);
  } else {
    o.push_back(0x59); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n);
  }
  o.insert(o.end(), d, d + n);
}

static bool cborReadBstr(const uint8_t*& p, const uint8_t* end,
                         const uint8_t*& data, size_t& len) {
  if (p >= end || ((*p >> 5) != 2)) return false;
  uint8_t ai = *p++ & 0x1f;
  if (ai < 24) {
    len = ai;
  } else if (ai == 24 && p < end) {
    len = *p++;
  } else if (ai == 25 && p + 1 < end) {
    len = ((size_t)p[0] << 8) | p[1];
    p += 2;
  } else {
    return false;
  }
  if (p + len > end) return false;
  data = p;
  p += len;
  return true;
}

static bool parseChatEnvelope(const uint8_t* data, size_t len,
                              const uint8_t*& senderPub, size_t& senderPubLen,
                              const uint8_t*& iv, size_t& ivLen,
                              const uint8_t*& ct, size_t& ctLen) {
  senderPub = nullptr; senderPubLen = 0;
  iv = nullptr; ivLen = 0;
  ct = nullptr; ctLen = 0;
  const uint8_t* p = data;
  const uint8_t* end = data + len;
  if (p >= end || ((*p >> 5) != 5)) return false;
  uint8_t count = *p++ & 0x1f;
  if (count >= 24) return false;
  for (uint8_t i = 0; i < count; i++) {
    if (p >= end || ((*p >> 5) != 0)) return false;
    uint8_t key = *p++ & 0x1f;
    const uint8_t* b = nullptr;
    size_t blen = 0;
    if (!cborReadBstr(p, end, b, blen)) return false;
    if (key == 1) { senderPub = b; senderPubLen = blen; }
    else if (key == 2) { iv = b; ivLen = blen; }
    else if (key == 3) { ct = b; ctLen = blen; }
  }
  return senderPubLen == mesh::PUBKEY_LEN && ivLen == 12 && ctLen >= 16;
}

static void mpiReadLE(mbedtls_mpi* X, const uint8_t* le, size_t len) {
  std::vector<uint8_t> be(len);
  for (size_t i = 0; i < len; i++) be[len - 1 - i] = le[i];
  mbedtls_mpi_read_binary(X, be.data(), be.size());
}

static void mpiWriteLE(const mbedtls_mpi* X, uint8_t* le, size_t len) {
  std::vector<uint8_t> be(len);
  mbedtls_mpi_write_binary(X, be.data(), be.size());
  for (size_t i = 0; i < len; i++) le[i] = be[len - 1 - i];
}

static void curve25519Prime(mbedtls_mpi* P) {
  mbedtls_mpi_lset(P, 1);
  mbedtls_mpi_shift_l(P, 255);
  mbedtls_mpi_sub_int(P, P, 19);
}

static bool ed25519PubToX25519(const uint8_t edPub[32], uint8_t out[32]) {
  uint8_t yLE[32];
  memcpy(yLE, edPub, 32);
  yLE[31] &= 0x7f;

  mbedtls_mpi P, Y, One, Num, Den, Inv, U;
  mbedtls_mpi_init(&P); mbedtls_mpi_init(&Y); mbedtls_mpi_init(&One);
  mbedtls_mpi_init(&Num); mbedtls_mpi_init(&Den); mbedtls_mpi_init(&Inv); mbedtls_mpi_init(&U);
  bool ok = true;
  curve25519Prime(&P);
  mpiReadLE(&Y, yLE, 32);
  mbedtls_mpi_lset(&One, 1);
  if (mbedtls_mpi_add_mpi(&Num, &One, &Y) != 0 || mbedtls_mpi_mod_mpi(&Num, &Num, &P) != 0) ok = false;
  if (mbedtls_mpi_sub_mpi(&Den, &One, &Y) != 0 || mbedtls_mpi_mod_mpi(&Den, &Den, &P) != 0) ok = false;
  if (ok && mbedtls_mpi_inv_mod(&Inv, &Den, &P) != 0) ok = false;
  if (ok && (mbedtls_mpi_mul_mpi(&U, &Num, &Inv) != 0 || mbedtls_mpi_mod_mpi(&U, &U, &P) != 0)) ok = false;
  if (ok) mpiWriteLE(&U, out, 32);
  mbedtls_mpi_free(&P); mbedtls_mpi_free(&Y); mbedtls_mpi_free(&One);
  mbedtls_mpi_free(&Num); mbedtls_mpi_free(&Den); mbedtls_mpi_free(&Inv); mbedtls_mpi_free(&U);
  return ok;
}

static void ed25519SeedToX25519Priv(const uint8_t seed[32], uint8_t out[32]) {
  uint8_t hash[64];
#if MBEDTLS_VERSION_MAJOR >= 3
  mbedtls_sha512(seed, 32, hash, 0);
#else
  mbedtls_sha512_ret(seed, 32, hash, 0);
#endif
  memcpy(out, hash, 32);
  out[0] &= 248;
  out[31] &= 127;
  out[31] |= 64;
}

static void mpiAddMod(mbedtls_mpi* R, const mbedtls_mpi* A, const mbedtls_mpi* B, const mbedtls_mpi* P) {
  mbedtls_mpi_add_mpi(R, A, B);
  mbedtls_mpi_mod_mpi(R, R, P);
}

static void mpiSubMod(mbedtls_mpi* R, const mbedtls_mpi* A, const mbedtls_mpi* B, const mbedtls_mpi* P) {
  mbedtls_mpi_sub_mpi(R, A, B);
  mbedtls_mpi_mod_mpi(R, R, P);
}

static void mpiMulMod(mbedtls_mpi* R, const mbedtls_mpi* A, const mbedtls_mpi* B, const mbedtls_mpi* P) {
  mbedtls_mpi_mul_mpi(R, A, B);
  mbedtls_mpi_mod_mpi(R, R, P);
}

static bool x25519ScalarMult(const uint8_t scalarIn[32], const uint8_t uIn[32], uint8_t out[32]) {
  uint8_t scalar[32], uMasked[32];
  memcpy(scalar, scalarIn, 32);
  scalar[0] &= 248;
  scalar[31] &= 127;
  scalar[31] |= 64;
  memcpy(uMasked, uIn, 32);
  uMasked[31] &= 0x7f;

  mbedtls_mpi P, K, X1, X2, Z2, X3, Z3, A24, A, AA, B, BB, E, C, D, DA, CB, T1, T2, Inv, Res;
  mbedtls_mpi* all[] = {&P,&K,&X1,&X2,&Z2,&X3,&Z3,&A24,&A,&AA,&B,&BB,&E,&C,&D,&DA,&CB,&T1,&T2,&Inv,&Res};
  for (auto m : all) mbedtls_mpi_init(m);
  curve25519Prime(&P);
  mpiReadLE(&K, scalar, 32);
  mpiReadLE(&X1, uMasked, 32);
  mbedtls_mpi_lset(&X2, 1);
  mbedtls_mpi_lset(&Z2, 0);
  mbedtls_mpi_copy(&X3, &X1);
  mbedtls_mpi_lset(&Z3, 1);
  mbedtls_mpi_lset(&A24, 121665);

  int swap = 0;
  for (int t = 254; t >= 0; t--) {
    int kt = mbedtls_mpi_get_bit(&K, t);
    swap ^= kt;
    if (swap) {
      mbedtls_mpi_swap(&X2, &X3);
      mbedtls_mpi_swap(&Z2, &Z3);
    }
    swap = kt;

    mpiAddMod(&A, &X2, &Z2, &P);
    mpiMulMod(&AA, &A, &A, &P);
    mpiSubMod(&B, &X2, &Z2, &P);
    mpiMulMod(&BB, &B, &B, &P);
    mpiSubMod(&E, &AA, &BB, &P);
    mpiAddMod(&C, &X3, &Z3, &P);
    mpiSubMod(&D, &X3, &Z3, &P);
    mpiMulMod(&DA, &D, &A, &P);
    mpiMulMod(&CB, &C, &B, &P);
    mpiAddMod(&T1, &DA, &CB, &P);
    mpiMulMod(&X3, &T1, &T1, &P);
    mpiSubMod(&T2, &DA, &CB, &P);
    mpiMulMod(&T2, &T2, &T2, &P);
    mpiMulMod(&Z3, &X1, &T2, &P);
    mpiMulMod(&X2, &AA, &BB, &P);
    mpiMulMod(&T1, &A24, &E, &P);
    mpiAddMod(&T1, &AA, &T1, &P);
    mpiMulMod(&Z2, &E, &T1, &P);
  }
  if (swap) {
    mbedtls_mpi_swap(&X2, &X3);
    mbedtls_mpi_swap(&Z2, &Z3);
  }
  bool ok = mbedtls_mpi_inv_mod(&Inv, &Z2, &P) == 0 &&
            mbedtls_mpi_mul_mpi(&Res, &X2, &Inv) == 0 &&
            mbedtls_mpi_mod_mpi(&Res, &Res, &P) == 0;
  if (ok) mpiWriteLE(&Res, out, 32);
  for (auto m : all) mbedtls_mpi_free(m);
  return ok;
}

static bool hmacSha256(const uint8_t* key, size_t keyLen, const uint8_t* data, size_t dataLen, uint8_t out[32]) {
  const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  return info && mbedtls_md_hmac(info, key, keyLen, data, dataLen, out) == 0;
}

static bool deriveChatKey(const uint8_t secret[32], uint8_t key[32]) {
  uint8_t salt[32] = {0};
  uint8_t prk[32];
  const char info[] = "bleedge-chat-v1";
  uint8_t expand[sizeof(info)];
  memcpy(expand, info, sizeof(info) - 1);
  expand[sizeof(info) - 1] = 0x01;
  return hmacSha256(salt, sizeof(salt), secret, 32, prk) &&
         hmacSha256(prk, sizeof(prk), expand, sizeof(expand), key);
}

static bool chatKeyForPeer(const uint8_t peerEdPub[32], uint8_t key[32]) {
  uint8_t myPriv[32], peerXPub[32], secret[32];
  ed25519SeedToX25519Priv(g_seed, myPriv);
  if (!ed25519PubToX25519(peerEdPub, peerXPub)) return false;
  if (!x25519ScalarMult(myPriv, peerXPub, secret)) return false;
  return deriveChatKey(secret, key);
}

static bool openChat(const uint8_t* envelope, size_t envelopeLen, String& plain,
                     std::array<uint8_t, mesh::PUBKEY_LEN>& senderPub) {
  const uint8_t* envSenderPub;
  const uint8_t* envIV;
  const uint8_t* envCT;
  size_t envSenderPubLen, envIVLen, envCTLen;
  if (!parseChatEnvelope(envelope, envelopeLen, envSenderPub, envSenderPubLen, envIV, envIVLen, envCT, envCTLen)) return false;
  memcpy(senderPub.data(), envSenderPub, mesh::PUBKEY_LEN);
  uint8_t key[32];
  if (!chatKeyForPeer(envSenderPub, key)) return false;
  size_t msgLen = envCTLen - 16;
  const uint8_t* tag = envCT + msgLen;
  std::vector<uint8_t> pt(msgLen);
  mbedtls_gcm_context gcm;
  mbedtls_gcm_init(&gcm);
  bool ok = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key, 256) == 0 &&
            mbedtls_gcm_auth_decrypt(&gcm, msgLen, envIV, envIVLen, nullptr, 0,
                                     tag, 16, envCT, pt.data()) == 0;
  mbedtls_gcm_free(&gcm);
  if (!ok) return false;
  plain = "";
  for (uint8_t b : pt) plain += (char)b;
  return true;
}

static bool sealChat(const String& plain, const uint8_t recipientPub[32], std::vector<uint8_t>& envelope) {
  uint8_t key[32];
  if (!chatKeyForPeer(recipientPub, key)) return false;
  uint8_t iv[12];
  esp_fill_random(iv, sizeof(iv));
  std::vector<uint8_t> ct(plain.length());
  uint8_t tag[16];
  mbedtls_gcm_context gcm;
  mbedtls_gcm_init(&gcm);
  bool ok = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key, 256) == 0 &&
            mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, plain.length(),
                                      iv, sizeof(iv), nullptr, 0,
                                      (const uint8_t*)plain.c_str(), ct.data(),
                                      sizeof(tag), tag) == 0;
  mbedtls_gcm_free(&gcm);
  if (!ok) return false;

  std::vector<uint8_t> ctTag;
  ctTag.reserve(ct.size() + sizeof(tag));
  ctTag.insert(ctTag.end(), ct.begin(), ct.end());
  ctTag.insert(ctTag.end(), tag, tag + sizeof(tag));
  envelope.clear();
  envelope.push_back(0xa3);
  envelope.push_back(1); cborEmitBstr(envelope, g_pubKey, mesh::PUBKEY_LEN);
  envelope.push_back(2); cborEmitBstr(envelope, iv, sizeof(iv));
  envelope.push_back(3); cborEmitBstr(envelope, ctTag.data(), ctTag.size());
  return true;
}

// Sends all frames of `pkt` to every connected peer except `exclude`.
static void floodToPeers(const std::vector<uint8_t>& pkt, const uint8_t pid[mesh::PACKET_ID_LEN],
                         uint16_t exclude) {
  std::vector<std::vector<uint8_t>> frames;
  mesh::fragment(pkt.data(), pkt.size(), FRAGMENT_MTU, pid, frames);
  g_txPackets++;

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

static bool sendToNode(const std::vector<uint8_t>& pkt, const uint8_t pid[mesh::PACKET_ID_LEN],
                       const uint8_t nodeId[mesh::NODE_ID_LEN]) {
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

  std::vector<std::vector<uint8_t>> frames;
  mesh::fragment(pkt.data(), pkt.size(), FRAGMENT_MTU, pid, frames);
  g_txPackets++;
  for (const auto& f : frames) {
    g_packetOut->notify(f.data(), f.size(), target);
    g_txFrames++;
  }
  return true;
}

static bool isTracePayload(uint8_t payloadType) {
  return payloadType == mesh::PAYLOAD_TRACE_REQUEST ||
         payloadType == mesh::PAYLOAD_TRACE_RESPONSE;
}

static void sendPacketToConn(const std::vector<uint8_t>& pkt, const uint8_t pid[mesh::PACKET_ID_LEN],
                             uint16_t conn) {
  std::vector<std::vector<uint8_t>> frames;
  mesh::fragment(pkt.data(), pkt.size(), FRAGMENT_MTU, pid, frames);
  g_txPackets++;
  for (const auto& f : frames) {
    g_packetOut->notify(f.data(), f.size(), conn);
    g_txFrames++;
  }
}

// Builds a 12-key CBOR packet this relay originates back to a node (FLOOD, ttl 3, no route/
// trace). Used for encrypted replies, ACKs, and typing hints.
static void buildSelfPacket(uint8_t pktType, uint8_t payloadType,
                            const uint8_t destNode[mesh::NODE_ID_LEN],
                            const uint8_t* payload, size_t payloadLen,
                            const uint8_t pid[mesh::PACKET_ID_LEN],
                            std::vector<uint8_t>& pkt) {
  pkt.clear();
  pkt.push_back(0xac);  // map(12), keys 1..12
  pkt.push_back(mesh::KEY_VERSION);      cborEmitUint(pkt, mesh::PROTOCOL_VERSION);
  pkt.push_back(mesh::KEY_TYPE);         cborEmitUint(pkt, pktType);
  pkt.push_back(mesh::KEY_ID);           cborEmitBstr(pkt, pid, mesh::PACKET_ID_LEN);
  pkt.push_back(mesh::KEY_SOURCE);       cborEmitBstr(pkt, g_nodeId, mesh::NODE_ID_LEN);
  pkt.push_back(mesh::KEY_DEST);         cborEmitBstr(pkt, destNode, mesh::NODE_ID_LEN);
  pkt.push_back(mesh::KEY_MODE);         cborEmitUint(pkt, mesh::MODE_FLOOD);
  pkt.push_back(mesh::KEY_TTL);          cborEmitUint(pkt, 3);
  pkt.push_back(mesh::KEY_ROUTE_CURSOR); cborEmitUint(pkt, 0);
  pkt.push_back(mesh::KEY_ROUTE);        pkt.push_back(0xf6);  // null
  pkt.push_back(mesh::KEY_TRACE);        pkt.push_back(0xf6);  // null
  pkt.push_back(mesh::KEY_PAYLOAD_TYPE); cborEmitUint(pkt, payloadType);
  pkt.push_back(mesh::KEY_PAYLOAD);      cborEmitBstr(pkt, payload, payloadLen);
}

static void sendEncryptedReply(uint16_t conn, const uint8_t destNode[mesh::NODE_ID_LEN],
                               const uint8_t recipientPub[mesh::PUBKEY_LEN], const String& text) {
  std::vector<uint8_t> envelope;
  if (!sealChat(text, recipientPub, envelope)) {
    Serial.println("[cmd] failed to seal reply");
    return;
  }
  uint8_t pid[mesh::PACKET_ID_LEN];
  esp_fill_random(pid, sizeof(pid));
  std::vector<uint8_t> pkt;
  buildSelfPacket(mesh::TYPE_DATA, mesh::PAYLOAD_CHAT_ENCRYPTED, destNode,
                  envelope.data(), envelope.size(), pid, pkt);
  g_dedup.seenOrAdd(pid);
  sendPacketToConn(pkt, pid, conn);
}

// ACKs a received DATA message back to its source. The ACK payload is the acked packet's id
// (matches core.Router.buildAck), so the sender flips the message to "delivered".
static void sendAckFor(uint16_t conn, const uint8_t source[mesh::NODE_ID_LEN],
                       const uint8_t ackedId[mesh::PACKET_ID_LEN]) {
  uint8_t pid[mesh::PACKET_ID_LEN];
  esp_fill_random(pid, sizeof(pid));
  std::vector<uint8_t> pkt;
  // payload_type mirrors the Kotlin/Go ACK default (TEXT).
  buildSelfPacket(mesh::TYPE_ACK, mesh::PAYLOAD_TEXT, source, ackedId, mesh::PACKET_ID_LEN, pid, pkt);
  g_dedup.seenOrAdd(pid);
  sendPacketToConn(pkt, pid, conn);
}

// Sends an ephemeral "typing" hint so the sender sees activity while we do the slow
// X25519/verify before replying. Empty payload, never ACKed.
static void sendTypingHint(uint16_t conn, const uint8_t destNode[mesh::NODE_ID_LEN]) {
  uint8_t pid[mesh::PACKET_ID_LEN];
  esp_fill_random(pid, sizeof(pid));
  uint8_t dummy = 0;  // valid pointer for a zero-length CBOR byte string
  std::vector<uint8_t> pkt;
  buildSelfPacket(mesh::TYPE_DATA, mesh::PAYLOAD_TYPING, destNode, &dummy, 0, pid, pkt);
  g_dedup.seenOrAdd(pid);
  sendPacketToConn(pkt, pid, conn);
}

// Answers a TRACE_REQUEST whose source-route ends at this relay (we're the traced node). Builds
// a minimal TraceResult ({1:tag, 2:auth, 4:[ourNodeId], 8:"rssi"}) and floods it back to the
// originator so the requester's trace completes (and its RTT timer stops) instead of hanging.
// The trace tag/auth are the first 8 bytes (LE) of the request payload (MeshCore TRACE prefix).
static void sendTraceResponse(uint16_t conn, const mesh::PacketHeader& h) {
  if (!h.hasSource || h.payload == nullptr || h.payloadLen < 8) {
    return;
  }
  int32_t tag = (int32_t)((uint32_t)h.payload[0] | ((uint32_t)h.payload[1] << 8) |
                          ((uint32_t)h.payload[2] << 16) | ((uint32_t)h.payload[3] << 24));
  int32_t auth = (int32_t)((uint32_t)h.payload[4] | ((uint32_t)h.payload[5] << 8) |
                           ((uint32_t)h.payload[6] << 16) | ((uint32_t)h.payload[7] << 24));

  std::vector<uint8_t> result;
  result.push_back(0xa4);                                            // map(4): keys 1,2,4,8
  result.push_back(1); cborEmitInt(result, tag);                    // tag (signed)
  result.push_back(2); cborEmitInt(result, auth);                   // auth_code
  result.push_back(4);                                              // forward_nodes
  result.push_back(0x81);                                           // array(1)
  cborEmitBstr(result, g_nodeId, mesh::NODE_ID_LEN);
  result.push_back(8);                                              // metric = "rssi"
  const char metric[] = "rssi";
  result.push_back(0x60 | 4);
  result.insert(result.end(), metric, metric + 4);

  uint8_t pid[mesh::PACKET_ID_LEN];
  esp_fill_random(pid, sizeof(pid));
  std::vector<uint8_t> pkt;
  buildSelfPacket(mesh::TYPE_DATA, mesh::PAYLOAD_TRACE_RESPONSE, h.source,
                  result.data(), result.size(), pid, pkt);
  g_dedup.seenOrAdd(pid);
  sendPacketToConn(pkt, pid, conn);
  Serial.println("[relay] trace endpoint: responded");
}

static String adminsText() {
  String out = "admins:\n";
  for (size_t i = 0; i < g_builtinAdmins.size(); i++) {
    out += "built-in ";
    out += bytesHex(g_builtinAdmins[i].data(), mesh::PUBKEY_LEN);
    out += "\n";
  }
  for (size_t i = 0; i < g_runtimeAdmins.size(); i++) {
    out += "runtime  ";
    out += bytesHex(g_runtimeAdmins[i].data(), mesh::PUBKEY_LEN);
    out += "\n";
  }
  if (g_builtinAdmins.empty() && g_runtimeAdmins.empty()) out += "(none)\n";
  return out;
}

static String statsText() {
  uint32_t neighbors = 0;
  uint32_t peers = 0;
  {
    std::lock_guard<std::mutex> lk(g_peersMu);
    neighbors = g_connNode.size();
    peers = g_peers.size();
  }
  String out;
  out += "stats\n";
  out += "uptime_s=" + String((millis() - g_bootMs) / 1000) + "\n";
  out += "peers=" + String(peers) + "\n";
  out += "neighbors=" + String(neighbors) + "\n";
  out += "rx_packets=" + String(g_rxPackets) + "\n";
  out += "tx_packets=" + String(g_txPackets) + "\n";
  out += "tx_frames=" + String(g_txFrames) + "\n";
  out += "flood_relays=" + String(g_floodRelays) + "\n";
  out += "trace_relays=" + String(g_traceRelays) + "\n";
  out += "announces=" + String(g_announceSeq) + "\n";
  out += "cmd_accepted=" + String(g_cmdAccepted) + "\n";
  out += "cmd_denied=" + String(g_cmdDenied) + "\n";
  out += "free_heap=" + String(ESP.getFreeHeap()) + "\n";
  out += "noise_floor=n/a\n";
  out += "air_time=n/a";
  return out;
}

static String handleAdminCommand(const String& cmd) {
  if (cmd == "help" || cmd == "?") {
    return "commands: help, sensors, stats, admins, admin.add <pubkey>, admin.remove <pubkey>";
  }
  if (cmd == "sensors") {
    return "temperature_c=" + String(temperatureRead(), 2);
  }
  if (cmd == "stats") {
    return statsText();
  }
  if (cmd == "admins" || cmd == "admin") {
    return adminsText();
  }
  if (cmd.startsWith("admin.add ")) {
    String hex = cmd.substring(strlen("admin.add "));
    hex.trim();
    std::array<uint8_t, mesh::PUBKEY_LEN> pub{};
    if (!parseHexBytes(hex, pub.data(), pub.size())) return "error: expected 32-byte public key hex";
    if (isAdminPub(pub.data())) return "ok: admin already present";
    g_runtimeAdmins.push_back(pub);
    saveRuntimeAdmins();
    return "ok: admin added";
  }
  if (cmd.startsWith("admin.remove ")) {
    String hex = cmd.substring(strlen("admin.remove "));
    hex.trim();
    std::array<uint8_t, mesh::PUBKEY_LEN> pub{};
    if (!parseHexBytes(hex, pub.data(), pub.size())) return "error: expected 32-byte public key hex";
    if (containsAdmin(g_builtinAdmins, pub.data())) return "error: built-in admin cannot be removed at runtime";
    size_t before = g_runtimeAdmins.size();
    g_runtimeAdmins.erase(std::remove_if(g_runtimeAdmins.begin(), g_runtimeAdmins.end(),
                                         [&](const auto& a) { return samePub(a, pub.data()); }),
                          g_runtimeAdmins.end());
    if (g_runtimeAdmins.size() == before) return "error: admin not found";
    saveRuntimeAdmins();
    return "ok: admin removed";
  }
  return "error: unknown command; try help";
}

static bool handleRemoteCommand(const mesh::PacketHeader& h, uint16_t sender) {
  if (h.type != mesh::TYPE_DATA || h.payloadType != mesh::PAYLOAD_CHAT_ENCRYPTED ||
      !h.hasDestination || memcmp(h.destination, g_nodeId, mesh::NODE_ID_LEN) != 0 ||
      h.payload == nullptr || h.payloadLen == 0) {
    return false;
  }
  // It's a DM addressed to us. Acknowledge delivery right away (so the sender's message flips
  // to "delivered"), then send a typing hint — verifying the key + X25519 below is slow, so
  // this tells the sender something is happening before the reply lands.
  if (h.hasSource) {
    sendAckFor(sender, h.source, h.id);
    sendTypingHint(sender, h.source);
  }
  const uint8_t* envSenderPub;
  const uint8_t* envIV;
  const uint8_t* envCT;
  size_t envSenderPubLen, envIVLen, envCTLen;
  if (!parseChatEnvelope(h.payload, h.payloadLen, envSenderPub, envSenderPubLen, envIV, envIVLen, envCT, envCTLen)) return true;

  if (!isAdminPub(envSenderPub)) {
    g_cmdDenied++;
    sendEncryptedReply(sender, h.source, envSenderPub, "not authenticated");
    return true;
  }

  std::array<uint8_t, mesh::PUBKEY_LEN> senderPub{};
  String cmd;
  if (!openChat(h.payload, h.payloadLen, cmd, senderPub)) {
    sendEncryptedReply(sender, h.source, envSenderPub, "bad encrypted message");
    return true;
  }
  cmd.trim();
  g_cmdAccepted++;
  String reply = handleAdminCommand(cmd);
  sendEncryptedReply(sender, h.source, envSenderPub, reply);
  Serial.printf("[cmd] admin=%s cmd=%s\n", bytesHex(envSenderPub, 4).c_str(), cmd.c_str());
  return true;
}

// Called for each reassembled packet arriving on PACKET_IN from `sender`.
static void onPacket(const std::vector<uint8_t>& pkt, uint16_t sender) {
  mesh::PacketHeader h = mesh::parseHeader(pkt.data(), pkt.size(), g_nodeId);
  if (!h.ok) {
    Serial.println("[relay] drop: malformed packet");
    return;
  }
  g_rxPackets++;
  // Learn the directly-connected peer (last trace hop, or source if fresh) so we
  // advertise it as a neighbor. Done before dedup so duplicates still teach us.
  uint8_t nb[mesh::NODE_ID_LEN];
  if (mesh::directNeighbor(h, nb) && memcmp(nb, g_nodeId, mesh::NODE_ID_LEN) != 0) {
    std::array<uint8_t, mesh::NODE_ID_LEN> id;
    memcpy(id.data(), nb, mesh::NODE_ID_LEN);
    std::lock_guard<std::mutex> lk(g_peersMu);
    g_connNode[sender] = id;
  }
  if (g_dedup.seenOrAdd(h.id)) {
    return;  // duplicate — silently drop (this is the common, expected case)
  }
  if (h.traceContainsSelf) {
    Serial.println("[relay] drop: loop (self already in trace)");
    return;
  }
  if (h.ttl == 0) {
    Serial.println("[relay] drop: ttl exhausted");
    return;
  }
  if (handleRemoteCommand(h, sender)) {
    return;
  }
  if (h.mode == mesh::MODE_FLOOD) {
    if (isTracePayload(h.payloadType)) {
      Serial.println("[relay] drop: TRACE flood is not relayed");
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
    Serial.printf("[relay] flood type=%u ttl=%u->%u size=%u\n", h.type, h.ttl, h.ttl - 1,
                  (unsigned)fwd.size());
    g_floodRelays++;
    floodToPeers(fwd, h.id, sender);
    return;
  }

  if (h.mode == mesh::MODE_SOURCE_ROUTE) {
    if (!isTracePayload(h.payloadType)) {
      Serial.println("[relay] drop: non-TRACE source-route not supported");
      return;
    }
    if (!h.routeCurrentIsSelf) {
      Serial.println("[relay] drop: source-route not addressed to this relay");
      return;
    }
    if (h.routeEndsHere || !h.hasNextHop) {
      // The trace was addressed to us (we're the final hop): answer it instead of dropping,
      // so the requester's trace doesn't hang forever.
      sendTraceResponse(sender, h);
      return;
    }
    if (h.ttl < 2) {
      Serial.println("[relay] drop: ttl exhausted");
      return;
    }
    std::vector<uint8_t> fwd;
    if (!mesh::buildTraceSourceRouteForward(pkt.data(), pkt.size(), g_nodeId,
                                            h.ttl - 1, h.routeCursor + 1,
                                            0, fwd)) {
      Serial.println("[relay] drop: trace source-route forward failed");
      return;
    }
    if (!sendToNode(fwd, h.id, h.nextHop)) {
      Serial.println("[relay] drop: trace next-hop not connected");
      return;
    }
    Serial.printf("[relay] trace source-route ttl=%u->%u cursor=%u->%u size=%u\n",
                  h.ttl, h.ttl - 1, h.routeCursor, h.routeCursor + 1, (unsigned)fwd.size());
    g_traceRelays++;
    return;
  }

  Serial.println("[relay] drop: unsupported routing mode");
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
                      neighbors.data(), nNeighbors, g_pubKey, sig,
                      NODE_DESCRIPTION, NODE_NAME, NODE_PLATFORM, pkt);

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
  g_bootMs = millis();
  pinMode(LED_PIN, OUTPUT);
  ledSet(false);
  loadOrCreateIdentity();
  loadAdmins();
  Serial.printf("\nBLEEdge relay  node=%s  phy=1m\n", nodeIdHex().c_str());
  Serial.printf("[cmd] admins built-in=%u runtime=%u\n",
                (unsigned)g_builtinAdmins.size(), (unsigned)g_runtimeAdmins.size());

  NimBLEDevice::init("BLEEdge");
  NimBLEDevice::setMTU(247);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  server->advertiseOnDisconnect(true);

  NimBLEService* svc = server->createService(SERVICE_UUID);

  // NODE_INFO (read): version(1)+pubkey(32)+caps(1)+descLen|desc|nameLen|name|platLen|platform
  std::vector<uint8_t> nodeInfo;
  nodeInfo.push_back(PROTOCOL_VERSION);
  nodeInfo.insert(nodeInfo.end(), g_pubKey, g_pubKey + mesh::PUBKEY_LEN);
  nodeInfo.push_back(RELAY_CAPS);
  auto appendStr = [&](const char* s) {
    size_t n = strlen(s);
    if (n > 255) n = 255;
    nodeInfo.push_back((uint8_t)n);
    nodeInfo.insert(nodeInfo.end(), s, s + n);
  };
  appendStr(NODE_DESCRIPTION);
  appendStr(NODE_NAME);
  appendStr(NODE_PLATFORM);
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
