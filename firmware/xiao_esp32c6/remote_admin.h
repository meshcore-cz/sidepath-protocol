// Remote admin commands over BLEEdge Chat v1 DIRECT_TEXT.
//
// This file intentionally keeps chat/admin code separate from the relay core in
// xiao_esp32c6.ino. It handles only datagrams addressed to this node with
// protocol BLEEDGE_CHAT and chat kind DIRECT_TEXT.

#include <mbedtls/bignum.h>
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#include <mbedtls/sha512.h>
#include <mbedtls/version.h>

static const char* ADMIN_PUBKEYS[] = {
#ifdef BLEEDGE_ADMIN_PUBKEY
  BLEEDGE_ADMIN_PUBKEY,
#endif
};

static std::vector<std::array<uint8_t, mesh::PUBKEY_LEN>> g_builtinAdmins;
static std::vector<std::array<uint8_t, mesh::PUBKEY_LEN>> g_runtimeAdmins;

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

static String bytesHex(const uint8_t* data, size_t len) {
  String s;
  for (size_t i = 0; i < len; i++) {
    char b[3];
    snprintf(b, sizeof(b), "%02x", data[i]);
    s += b;
  }
  return s;
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

static String adminCommandHelp() {
  return ", admins, admin.add <pubkey>, admin.remove <pubkey>";
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

static bool handleAdminCommandExtra(const String& cmd, String& reply) {
  if (cmd == "admins" || cmd == "admin") {
    reply = adminsText();
    return true;
  }
  if (cmd.startsWith("admin.add ")) {
    String hex = cmd.substring(strlen("admin.add "));
    hex.trim();
    std::array<uint8_t, mesh::PUBKEY_LEN> pub{};
    if (!parseHexBytes(hex, pub.data(), pub.size())) {
      reply = "error: expected 32-byte public key hex";
      return true;
    }
    if (isAdminPub(pub.data())) {
      reply = "ok: admin already present";
      return true;
    }
    g_runtimeAdmins.push_back(pub);
    saveRuntimeAdmins();
    reply = "ok: admin added";
    return true;
  }
  if (cmd.startsWith("admin.remove ")) {
    String hex = cmd.substring(strlen("admin.remove "));
    hex.trim();
    std::array<uint8_t, mesh::PUBKEY_LEN> pub{};
    if (!parseHexBytes(hex, pub.data(), pub.size())) {
      reply = "error: expected 32-byte public key hex";
      return true;
    }
    if (containsAdmin(g_builtinAdmins, pub.data())) {
      reply = "error: built-in admin cannot be removed at runtime";
      return true;
    }
    size_t before = g_runtimeAdmins.size();
    g_runtimeAdmins.erase(std::remove_if(g_runtimeAdmins.begin(), g_runtimeAdmins.end(),
                                         [&](const auto& a) { return samePub(a, pub.data()); }),
                          g_runtimeAdmins.end());
    if (g_runtimeAdmins.size() == before) reply = "error: admin not found";
    else {
      saveRuntimeAdmins();
      reply = "ok: admin removed";
    }
    return true;
  }
  return false;
}

static size_t cborArgLenAdmin(uint8_t ai) {
  switch (ai) {
    case 24: return 1;
    case 25: return 2;
    case 26: return 4;
    case 27: return 8;
    default: return 0;
  }
}

static uint64_t cborArgValAdmin(const uint8_t* p, uint8_t ai) {
  if (ai < 24) return ai;
  uint64_t v = 0;
  for (size_t i = 0; i < cborArgLenAdmin(ai); i++) v = (v << 8) | p[1 + i];
  return v;
}

static size_t cborItemLenAdmin(const uint8_t* p, size_t avail) {
  if (avail < 1) return 0;
  uint8_t mt = p[0] >> 5, ai = p[0] & 0x1f;
  if (ai > 27 || ai == 31) return 0;
  size_t head = 1 + cborArgLenAdmin(ai);
  if (head > avail) return 0;
  uint64_t arg = cborArgValAdmin(p, ai);
  switch (mt) {
    case 0: case 1: case 7: return head;
    case 2: case 3: return head + arg <= avail ? head + (size_t)arg : 0;
    case 4: {
      size_t pos = head;
      for (uint64_t i = 0; i < arg; i++) {
        size_t l = cborItemLenAdmin(p + pos, avail - pos);
        if (!l) return 0;
        pos += l;
      }
      return pos;
    }
    case 5: {
      size_t pos = head;
      for (uint64_t i = 0; i < arg * 2; i++) {
        size_t l = cborItemLenAdmin(p + pos, avail - pos);
        if (!l) return 0;
        pos += l;
      }
      return pos;
    }
    default: return 0;
  }
}

static bool cborUintAdmin(const uint8_t* p, size_t len, uint64_t& out) {
  if (len < 1 || (p[0] >> 5) != 0) return false;
  uint8_t ai = p[0] & 0x1f;
  size_t head = 1 + cborArgLenAdmin(ai);
  if (ai > 27 || head > len) return false;
  out = cborArgValAdmin(p, ai);
  return true;
}

static bool cborReadBstrAdmin(const uint8_t* p, size_t len, const uint8_t*& data, size_t& outLen) {
  if (len < 1 || (p[0] >> 5) != 2) return false;
  uint8_t ai = p[0] & 0x1f;
  size_t head = 1 + cborArgLenAdmin(ai);
  if (head > len) return false;
  uint64_t n = cborArgValAdmin(p, ai);
  if (head + n > len) return false;
  data = p + head;
  outLen = (size_t)n;
  return true;
}

static bool cborReadTextAdmin(const uint8_t* p, size_t len, String& out) {
  if (len < 1 || (p[0] >> 5) != 3) return false;
  uint8_t ai = p[0] & 0x1f;
  size_t head = 1 + cborArgLenAdmin(ai);
  if (head > len) return false;
  uint64_t n = cborArgValAdmin(p, ai);
  if (head + n > len) return false;
  out = "";
  for (size_t i = 0; i < (size_t)n; i++) out += (char)p[head + i];
  return true;
}

static void cborEmitUintAdmin(std::vector<uint8_t>& o, uint64_t v) {
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

static void cborEmitIntAdmin(std::vector<uint8_t>& o, int64_t v) {
  if (v >= 0) { cborEmitUintAdmin(o, (uint64_t)v); return; }
  uint64_t n = (uint64_t)(-1 - v);
  if (n < 24) o.push_back(0x20 | (uint8_t)n);
  else { o.push_back(0x38); o.push_back((uint8_t)n); }
}

static void cborEmitBstrAdmin(std::vector<uint8_t>& o, const uint8_t* d, size_t n) {
  if (n < 24) o.push_back(0x40 | (uint8_t)n);
  else if (n < 0x100) { o.push_back(0x58); o.push_back((uint8_t)n); }
  else { o.push_back(0x59); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n); }
  o.insert(o.end(), d, d + n);
}

static void cborEmitTextAdmin(std::vector<uint8_t>& o, const String& s) {
  size_t n = s.length();
  if (n < 24) o.push_back(0x60 | (uint8_t)n);
  else if (n < 0x100) { o.push_back(0x78); o.push_back((uint8_t)n); }
  else { o.push_back(0x79); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n); }
  o.insert(o.end(), (const uint8_t*)s.c_str(), (const uint8_t*)s.c_str() + n);
}

struct DirectChatBody {
  const uint8_t* senderPub = nullptr;
  size_t senderPubLen = 0;
  const uint8_t* nonce = nullptr;
  size_t nonceLen = 0;
  const uint8_t* ciphertext = nullptr;
  size_t ciphertextLen = 0;
};

static bool parseDirectChatPayload(const uint8_t* payload, size_t len, DirectChatBody& body) {
  if (len < 1 || (payload[0] >> 5) != 5) return false;
  uint8_t count = payload[0] & 0x1f;
  if (count >= 24) return false;
  const uint8_t* chatBody = nullptr;
  size_t chatBodyLen = 0;
  uint8_t version = 0, kind = 0;
  size_t pos = 1;
  for (uint8_t i = 0; i < count; i++) {
    size_t kl = cborItemLenAdmin(payload + pos, len - pos);
    if (!kl) return false;
    size_t v = pos + kl;
    size_t vl = cborItemLenAdmin(payload + v, len - v);
    if (!vl) return false;
    uint64_t key = 0, u = 0;
    if (!cborUintAdmin(payload + pos, kl, key)) return false;
    if (key == 1 && cborUintAdmin(payload + v, vl, u)) version = (uint8_t)u;
    else if (key == 2 && cborUintAdmin(payload + v, vl, u)) kind = (uint8_t)u;
    else if (key == 3) { chatBody = payload + v; chatBodyLen = vl; }
    pos = v + vl;
  }
  if (version != 1 || kind != 2 || !chatBody || (chatBody[0] >> 5) != 5) return false;
  count = chatBody[0] & 0x1f;
  if (count >= 24) return false;
  pos = 1;
  for (uint8_t i = 0; i < count; i++) {
    size_t kl = cborItemLenAdmin(chatBody + pos, chatBodyLen - pos);
    if (!kl) return false;
    size_t v = pos + kl;
    size_t vl = cborItemLenAdmin(chatBody + v, chatBodyLen - v);
    if (!vl) return false;
    uint64_t key = 0;
    if (!cborUintAdmin(chatBody + pos, kl, key)) return false;
    if (key == 1) cborReadBstrAdmin(chatBody + v, vl, body.senderPub, body.senderPubLen);
    else if (key == 2) cborReadBstrAdmin(chatBody + v, vl, body.nonce, body.nonceLen);
    else if (key == 3) cborReadBstrAdmin(chatBody + v, vl, body.ciphertext, body.ciphertextLen);
    pos = v + vl;
  }
  return body.senderPubLen == mesh::PUBKEY_LEN && body.nonceLen == 12 && body.ciphertextLen >= 16;
}

static void mpiReadLEAdmin(mbedtls_mpi* X, const uint8_t* le, size_t len) {
  std::vector<uint8_t> be(len);
  for (size_t i = 0; i < len; i++) be[len - 1 - i] = le[i];
  mbedtls_mpi_read_binary(X, be.data(), be.size());
}

static void mpiWriteLEAdmin(const mbedtls_mpi* X, uint8_t* le, size_t len) {
  std::vector<uint8_t> be(len);
  mbedtls_mpi_write_binary(X, be.data(), be.size());
  for (size_t i = 0; i < len; i++) le[i] = be[len - 1 - i];
}

static void curve25519PrimeAdmin(mbedtls_mpi* P) {
  mbedtls_mpi_lset(P, 1);
  mbedtls_mpi_shift_l(P, 255);
  mbedtls_mpi_sub_int(P, P, 19);
}

static bool ed25519PubToX25519Admin(const uint8_t edPub[32], uint8_t out[32]) {
  uint8_t yLE[32];
  memcpy(yLE, edPub, 32);
  yLE[31] &= 0x7f;
  mbedtls_mpi P, Y, One, Num, Den, Inv, U;
  mbedtls_mpi_init(&P); mbedtls_mpi_init(&Y); mbedtls_mpi_init(&One);
  mbedtls_mpi_init(&Num); mbedtls_mpi_init(&Den); mbedtls_mpi_init(&Inv); mbedtls_mpi_init(&U);
  bool ok = true;
  curve25519PrimeAdmin(&P);
  mpiReadLEAdmin(&Y, yLE, 32);
  mbedtls_mpi_lset(&One, 1);
  if (mbedtls_mpi_add_mpi(&Num, &One, &Y) != 0 || mbedtls_mpi_mod_mpi(&Num, &Num, &P) != 0) ok = false;
  if (mbedtls_mpi_sub_mpi(&Den, &One, &Y) != 0 || mbedtls_mpi_mod_mpi(&Den, &Den, &P) != 0) ok = false;
  if (ok && mbedtls_mpi_inv_mod(&Inv, &Den, &P) != 0) ok = false;
  if (ok && (mbedtls_mpi_mul_mpi(&U, &Num, &Inv) != 0 || mbedtls_mpi_mod_mpi(&U, &U, &P) != 0)) ok = false;
  if (ok) mpiWriteLEAdmin(&U, out, 32);
  mbedtls_mpi_free(&P); mbedtls_mpi_free(&Y); mbedtls_mpi_free(&One);
  mbedtls_mpi_free(&Num); mbedtls_mpi_free(&Den); mbedtls_mpi_free(&Inv); mbedtls_mpi_free(&U);
  return ok;
}

static void ed25519SeedToX25519PrivAdmin(const uint8_t seed[32], uint8_t out[32]) {
  uint8_t hash[64];
#if MBEDTLS_VERSION_MAJOR >= 3
  mbedtls_sha512(seed, 32, hash, 0);
#else
  mbedtls_sha512_ret(seed, 32, hash, 0);
#endif
  memcpy(out, hash, 32);
  out[0] &= 248; out[31] &= 127; out[31] |= 64;
}

static void mpiAddModAdmin(mbedtls_mpi* R, const mbedtls_mpi* A, const mbedtls_mpi* B, const mbedtls_mpi* P) {
  mbedtls_mpi_add_mpi(R, A, B); mbedtls_mpi_mod_mpi(R, R, P);
}
static void mpiSubModAdmin(mbedtls_mpi* R, const mbedtls_mpi* A, const mbedtls_mpi* B, const mbedtls_mpi* P) {
  mbedtls_mpi_sub_mpi(R, A, B); mbedtls_mpi_mod_mpi(R, R, P);
}
static void mpiMulModAdmin(mbedtls_mpi* R, const mbedtls_mpi* A, const mbedtls_mpi* B, const mbedtls_mpi* P) {
  mbedtls_mpi_mul_mpi(R, A, B); mbedtls_mpi_mod_mpi(R, R, P);
}

static bool x25519ScalarMultAdmin(const uint8_t scalarIn[32], const uint8_t uIn[32], uint8_t out[32]) {
  uint8_t scalar[32], uMasked[32];
  memcpy(scalar, scalarIn, 32);
  scalar[0] &= 248; scalar[31] &= 127; scalar[31] |= 64;
  memcpy(uMasked, uIn, 32);
  uMasked[31] &= 0x7f;
  mbedtls_mpi P, K, X1, X2, Z2, X3, Z3, A24, A, AA, B, BB, E, C, D, DA, CB, T1, T2, Inv, Res;
  mbedtls_mpi* all[] = {&P,&K,&X1,&X2,&Z2,&X3,&Z3,&A24,&A,&AA,&B,&BB,&E,&C,&D,&DA,&CB,&T1,&T2,&Inv,&Res};
  for (auto m : all) mbedtls_mpi_init(m);
  curve25519PrimeAdmin(&P);
  mpiReadLEAdmin(&K, scalar, 32);
  mpiReadLEAdmin(&X1, uMasked, 32);
  mbedtls_mpi_lset(&X2, 1); mbedtls_mpi_lset(&Z2, 0);
  mbedtls_mpi_copy(&X3, &X1); mbedtls_mpi_lset(&Z3, 1); mbedtls_mpi_lset(&A24, 121665);
  int swap = 0;
  for (int t = 254; t >= 0; t--) {
    int kt = mbedtls_mpi_get_bit(&K, t);
    swap ^= kt;
    if (swap) { mbedtls_mpi_swap(&X2, &X3); mbedtls_mpi_swap(&Z2, &Z3); }
    swap = kt;
    mpiAddModAdmin(&A, &X2, &Z2, &P); mpiMulModAdmin(&AA, &A, &A, &P);
    mpiSubModAdmin(&B, &X2, &Z2, &P); mpiMulModAdmin(&BB, &B, &B, &P);
    mpiSubModAdmin(&E, &AA, &BB, &P); mpiAddModAdmin(&C, &X3, &Z3, &P);
    mpiSubModAdmin(&D, &X3, &Z3, &P); mpiMulModAdmin(&DA, &D, &A, &P);
    mpiMulModAdmin(&CB, &C, &B, &P); mpiAddModAdmin(&T1, &DA, &CB, &P);
    mpiMulModAdmin(&X3, &T1, &T1, &P); mpiSubModAdmin(&T2, &DA, &CB, &P);
    mpiMulModAdmin(&T2, &T2, &T2, &P); mpiMulModAdmin(&Z3, &X1, &T2, &P);
    mpiMulModAdmin(&X2, &AA, &BB, &P); mpiMulModAdmin(&T1, &A24, &E, &P);
    mpiAddModAdmin(&T1, &AA, &T1, &P); mpiMulModAdmin(&Z2, &E, &T1, &P);
  }
  if (swap) { mbedtls_mpi_swap(&X2, &X3); mbedtls_mpi_swap(&Z2, &Z3); }
  bool ok = mbedtls_mpi_inv_mod(&Inv, &Z2, &P) == 0 &&
            mbedtls_mpi_mul_mpi(&Res, &X2, &Inv) == 0 &&
            mbedtls_mpi_mod_mpi(&Res, &Res, &P) == 0;
  if (ok) mpiWriteLEAdmin(&Res, out, 32);
  for (auto m : all) mbedtls_mpi_free(m);
  return ok;
}

static bool hmacSha256Admin(const uint8_t* key, size_t keyLen, const uint8_t* data, size_t dataLen, uint8_t out[32]) {
  const mbedtls_md_info_t* info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  return info && mbedtls_md_hmac(info, key, keyLen, data, dataLen, out) == 0;
}

static void hkdfSha256Admin(const uint8_t* ikm, size_t ikmLen, const uint8_t* info, size_t infoLen, uint8_t out[32]) {
  uint8_t salt[32] = {0}, prk[32];
  hmacSha256Admin(salt, sizeof(salt), ikm, ikmLen, prk);
  std::vector<uint8_t> expand(info, info + infoLen);
  expand.push_back(1);
  hmacSha256Admin(prk, sizeof(prk), expand.data(), expand.size(), out);
}

static int comparePubAdmin(const uint8_t* a, const uint8_t* b) {
  for (size_t i = 0; i < mesh::PUBKEY_LEN; i++) if (a[i] != b[i]) return (int)a[i] - (int)b[i];
  return 0;
}

static bool chatKeyForPeerAdmin(const uint8_t peerEdPub[32], uint8_t key[32]) {
  uint8_t myPriv[32], peerXPub[32], secret[32];
  ed25519SeedToX25519PrivAdmin(g_seed, myPriv);
  if (!ed25519PubToX25519Admin(peerEdPub, peerXPub)) return false;
  if (!x25519ScalarMultAdmin(myPriv, peerXPub, secret)) return false;
  std::vector<uint8_t> info;
  const char prefix[] = "BLEEDGE-CHAT-DIRECT-V1";
  info.insert(info.end(), prefix, prefix + sizeof(prefix));
  const uint8_t* low = g_pubKey;
  const uint8_t* high = peerEdPub;
  if (comparePubAdmin(low, high) > 0) { low = peerEdPub; high = g_pubKey; }
  info.insert(info.end(), low, low + mesh::PUBKEY_LEN);
  info.insert(info.end(), high, high + mesh::PUBKEY_LEN);
  hkdfSha256Admin(secret, sizeof(secret), info.data(), info.size(), key);
  return true;
}

static void directAADAdmin(const uint8_t datagramId[mesh::DATAGRAM_ID_LEN],
                           const uint8_t source[mesh::NODE_ID_LEN],
                           const uint8_t dest[mesh::NODE_ID_LEN],
                           const uint8_t senderPub[mesh::PUBKEY_LEN],
                           std::vector<uint8_t>& aad) {
  aad.clear();
  const char prefix[] = "BLEEDGE-CHAT-DIRECT-AAD-V1";
  aad.insert(aad.end(), prefix, prefix + sizeof(prefix));
  aad.insert(aad.end(), datagramId, datagramId + mesh::DATAGRAM_ID_LEN);
  aad.insert(aad.end(), source, source + mesh::NODE_ID_LEN);
  aad.insert(aad.end(), dest, dest + mesh::NODE_ID_LEN);
  aad.insert(aad.end(), senderPub, senderPub + mesh::PUBKEY_LEN);
  aad.push_back((uint8_t)mesh::PROTO_BLEEDGE_CHAT);
  aad.push_back((uint8_t)(mesh::PROTO_BLEEDGE_CHAT >> 8));
  aad.push_back(1);
  aad.push_back(2);
}

static bool openDirectTextAdmin(const mesh::DatagramHeader& h, String& text,
                                std::array<uint8_t, mesh::PUBKEY_LEN>& senderPub) {
  DirectChatBody body;
  if (!parseDirectChatPayload(h.payload, h.payloadLen, body)) return false;
  if (memcmp(body.senderPub, h.source, mesh::NODE_ID_LEN) != 0) return false;
  memcpy(senderPub.data(), body.senderPub, mesh::PUBKEY_LEN);
  uint8_t key[32];
  if (!chatKeyForPeerAdmin(body.senderPub, key)) return false;
  size_t msgLen = body.ciphertextLen - 16;
  const uint8_t* tag = body.ciphertext + msgLen;
  std::vector<uint8_t> aad;
  directAADAdmin(h.id, h.source, h.destination, body.senderPub, aad);
  std::vector<uint8_t> pt(msgLen);
  mbedtls_gcm_context gcm;
  mbedtls_gcm_init(&gcm);
  bool ok = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key, 256) == 0 &&
            mbedtls_gcm_auth_decrypt(&gcm, msgLen, body.nonce, body.nonceLen,
                                     aad.data(), aad.size(), tag, 16,
                                     body.ciphertext, pt.data()) == 0;
  mbedtls_gcm_free(&gcm);
  if (!ok || pt.empty() || (pt[0] >> 5) != 5) return false;
  uint8_t count = pt[0] & 0x1f;
  size_t pos = 1;
  for (uint8_t i = 0; i < count; i++) {
    size_t kl = cborItemLenAdmin(pt.data() + pos, pt.size() - pos);
    if (!kl) return false;
    size_t v = pos + kl;
    size_t vl = cborItemLenAdmin(pt.data() + v, pt.size() - v);
    if (!vl) return false;
    uint64_t keyNum = 0;
    if (cborUintAdmin(pt.data() + pos, kl, keyNum) && keyNum == 2) {
      return cborReadTextAdmin(pt.data() + v, vl, text);
    }
    pos = v + vl;
  }
  return false;
}

static void buildChatEnvelopeAdmin(const uint8_t senderPub[mesh::PUBKEY_LEN],
                                   const uint8_t nonce[12],
                                   const std::vector<uint8_t>& ciphertext,
                                   std::vector<uint8_t>& payload) {
  std::vector<uint8_t> body;
  body.push_back(0xa3);
  cborEmitUintAdmin(body, 1); cborEmitBstrAdmin(body, senderPub, mesh::PUBKEY_LEN);
  cborEmitUintAdmin(body, 2); cborEmitBstrAdmin(body, nonce, 12);
  cborEmitUintAdmin(body, 3); cborEmitBstrAdmin(body, ciphertext.data(), ciphertext.size());
  payload.clear();
  payload.push_back(0xa3);
  cborEmitUintAdmin(payload, 1); cborEmitUintAdmin(payload, 1);
  cborEmitUintAdmin(payload, 2); cborEmitUintAdmin(payload, 2);
  cborEmitUintAdmin(payload, 3); payload.insert(payload.end(), body.begin(), body.end());
}

static bool sealDirectTextAdmin(const uint8_t datagramId[mesh::DATAGRAM_ID_LEN],
                                const uint8_t destNode[mesh::NODE_ID_LEN],
                                const uint8_t recipientPub[mesh::PUBKEY_LEN],
                                const String& text,
                                std::vector<uint8_t>& payload) {
  uint8_t key[32];
  if (!chatKeyForPeerAdmin(recipientPub, key)) return false;
  std::vector<uint8_t> plain;
  plain.push_back(0xa2);
  cborEmitUintAdmin(plain, 1); cborEmitIntAdmin(plain, internalUnixTime());
  cborEmitUintAdmin(plain, 2); cborEmitTextAdmin(plain, text);
  uint8_t nonce[12];
  esp_fill_random(nonce, sizeof(nonce));
  std::vector<uint8_t> aad;
  directAADAdmin(datagramId, g_nodeId, destNode, g_pubKey, aad);
  std::vector<uint8_t> ct(plain.size());
  uint8_t tag[16];
  mbedtls_gcm_context gcm;
  mbedtls_gcm_init(&gcm);
  bool ok = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, key, 256) == 0 &&
            mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, plain.size(),
                                      nonce, sizeof(nonce), aad.data(), aad.size(),
                                      plain.data(), ct.data(), sizeof(tag), tag) == 0;
  mbedtls_gcm_free(&gcm);
  if (!ok) return false;
  ct.insert(ct.end(), tag, tag + sizeof(tag));
  buildChatEnvelopeAdmin(g_pubKey, nonce, ct, payload);
  return true;
}

static void buildChatDatagramAdmin(const uint8_t id[mesh::DATAGRAM_ID_LEN],
                                   const uint8_t destNode[mesh::NODE_ID_LEN],
                                   uint16_t flags,
                                   const std::vector<uint8_t>& payload,
                                   std::vector<uint8_t>& dg) {
  dg.clear();
  dg.push_back(flags ? 0xa8 : 0xa7);
  cborEmitUintAdmin(dg, mesh::KEY_VERSION); cborEmitUintAdmin(dg, mesh::DATAGRAM_VERSION);
  cborEmitUintAdmin(dg, mesh::KEY_ID); cborEmitBstrAdmin(dg, id, mesh::DATAGRAM_ID_LEN);
  cborEmitUintAdmin(dg, mesh::KEY_SOURCE); cborEmitBstrAdmin(dg, g_nodeId, mesh::NODE_ID_LEN);
  cborEmitUintAdmin(dg, mesh::KEY_DEST); cborEmitBstrAdmin(dg, destNode, mesh::NODE_ID_LEN);
  cborEmitUintAdmin(dg, mesh::KEY_TTL); cborEmitUintAdmin(dg, 1);
  cborEmitUintAdmin(dg, mesh::KEY_PROTOCOL); cborEmitUintAdmin(dg, mesh::PROTO_BLEEDGE_CHAT);
  if (flags) { cborEmitUintAdmin(dg, mesh::KEY_FLAGS); cborEmitUintAdmin(dg, flags); }
  cborEmitUintAdmin(dg, mesh::KEY_PAYLOAD); cborEmitBstrAdmin(dg, payload.data(), payload.size());
}

static void sendEncryptedAdminReply(uint16_t conn, const uint8_t destNode[mesh::NODE_ID_LEN],
                                    const uint8_t recipientPub[mesh::PUBKEY_LEN],
                                    const String& text) {
  uint8_t id[mesh::DATAGRAM_ID_LEN];
  esp_fill_random(id, sizeof(id));
  std::vector<uint8_t> payload;
  if (!sealDirectTextAdmin(id, destNode, recipientPub, text, payload)) {
    Serial.println("[admin] failed to seal reply");
    return;
  }
  std::vector<uint8_t> dg;
  buildChatDatagramAdmin(id, destNode, mesh::FLAG_ACK_REQUESTED, payload, dg);
  g_dedup.seenOrAdd(id);
  sendFramesToConn(dg, conn);
}

static void sendAckAdmin(uint16_t conn, const uint8_t destNode[mesh::NODE_ID_LEN],
                         const uint8_t ackedId[mesh::DATAGRAM_ID_LEN]) {
  std::vector<uint8_t> body;
  body.push_back(0xa1);
  cborEmitUintAdmin(body, 1); cborEmitBstrAdmin(body, ackedId, mesh::DATAGRAM_ID_LEN);
  std::vector<uint8_t> ctrl;
  ctrl.push_back(0xa2);
  cborEmitUintAdmin(ctrl, 1); cborEmitUintAdmin(ctrl, mesh::CONTROL_ACK);
  cborEmitUintAdmin(ctrl, 2); ctrl.insert(ctrl.end(), body.begin(), body.end());
  uint8_t id[mesh::DATAGRAM_ID_LEN];
  esp_fill_random(id, sizeof(id));
  std::vector<uint8_t> dg;
  dg.push_back(0xa7);
  cborEmitUintAdmin(dg, mesh::KEY_VERSION); cborEmitUintAdmin(dg, mesh::DATAGRAM_VERSION);
  cborEmitUintAdmin(dg, mesh::KEY_ID); cborEmitBstrAdmin(dg, id, mesh::DATAGRAM_ID_LEN);
  cborEmitUintAdmin(dg, mesh::KEY_SOURCE); cborEmitBstrAdmin(dg, g_nodeId, mesh::NODE_ID_LEN);
  cborEmitUintAdmin(dg, mesh::KEY_DEST); cborEmitBstrAdmin(dg, destNode, mesh::NODE_ID_LEN);
  cborEmitUintAdmin(dg, mesh::KEY_TTL); cborEmitUintAdmin(dg, 1);
  cborEmitUintAdmin(dg, mesh::KEY_PROTOCOL); cborEmitUintAdmin(dg, mesh::PROTO_BLEEDGE_CONTROL);
  cborEmitUintAdmin(dg, mesh::KEY_PAYLOAD); cborEmitBstrAdmin(dg, ctrl.data(), ctrl.size());
  g_dedup.seenOrAdd(id);
  sendFramesToConn(dg, conn);
}

static bool handleRemoteAdminChat(const std::vector<uint8_t>& dg, const mesh::DatagramHeader& h, uint16_t sender) {
  (void)dg;
  if (h.protocol != mesh::PROTO_BLEEDGE_CHAT || !h.hasDestination ||
      memcmp(h.destination, g_nodeId, mesh::NODE_ID_LEN) != 0 ||
      h.payload == nullptr || h.payloadLen == 0) {
    return false;
  }
  DirectChatBody parsed;
  if (!parseDirectChatPayload(h.payload, h.payloadLen, parsed)) return false;
  if (h.flags & mesh::FLAG_ACK_REQUESTED) sendAckAdmin(sender, h.source, h.id);

  std::array<uint8_t, mesh::PUBKEY_LEN> senderPub{};
  String cmd;
  if (!openDirectTextAdmin(h, cmd, senderPub)) {
    g_cmdDenied++;
    return true;
  }
  if (!isAdminPub(senderPub.data())) {
    g_cmdDenied++;
    sendEncryptedAdminReply(sender, h.source, senderPub.data(), "not authenticated");
    return true;
  }
  cmd.trim();
  g_cmdAccepted++;
  String reply = handleAdminCommand(cmd);
  sendEncryptedAdminReply(sender, h.source, senderPub.data(), reply);
  Serial.printf("[admin] from=%s cmd=%s\n", bytesHex(senderPub.data(), 4).c_str(), cmd.c_str());
  return true;
}
