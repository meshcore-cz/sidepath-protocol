// Host harness for the firmware mesh logic. Reads a packet (hex) and a self
// NodeID (hex) from argv, runs parseHeader + buildForward, and prints the parsed
// fields plus the transformed (forwarded) packet as hex. Cross-validated against
// the Go reference encoder by test/crosscheck.sh.
//
// Build: c++ -std=c++17 -I. -I.. host_test.cpp ../mesh.cpp -o host_test
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "mesh.h"
#include "ed25519.h"

static std::vector<uint8_t> fromHex(const char* s) {
  std::vector<uint8_t> out;
  for (const char* p = s; p[0] && p[1]; p += 2) {
    auto nib = [](char c) -> int {
      if (c >= '0' && c <= '9') return c - '0';
      if (c >= 'a' && c <= 'f') return c - 'a' + 10;
      if (c >= 'A' && c <= 'F') return c - 'A' + 10;
      return 0;
    };
    out.push_back((uint8_t)((nib(p[0]) << 4) | nib(p[1])));
  }
  return out;
}

static void printHex(const std::vector<uint8_t>& v) {
  for (uint8_t b : v) printf("%02x", b);
  printf("\n");
}

int main(int argc, char** argv) {
  if (argc < 3) {
    fprintf(stderr,
            "usage: %s <packet-hex> <selfid-hex>\n"
            "       %s --trace-fwd <packet-hex> <selfid-hex>\n"
            "       %s --crc  <hex>\n"
            "       %s --frag <hex> <mtu> <packetid-hex>\n",
            argv[0], argv[0], argv[0], argv[0]);
    return 2;
  }

  if (std::string(argv[1]) == "--crc") {
    std::vector<uint8_t> d = fromHex(argv[2]);
    printf("%08x\n", mesh::crc32_ieee(d.data(), d.size()));
    return 0;
  }
  if (std::string(argv[1]) == "--frag") {
    std::vector<uint8_t> d = fromHex(argv[2]);
    size_t mtu = (size_t)atoi(argv[3]);
    std::vector<uint8_t> pid = fromHex(argv[4]);
    std::vector<std::vector<uint8_t>> frames;
    mesh::fragment(d.data(), d.size(), mtu, pid.data(), frames);
    for (auto& f : frames) printHex(f);
    return 0;
  }
  if (std::string(argv[1]) == "--announce") {
    // --announce <seedhex> <caps> <seq> [neighborhex ...]
    // Derives the Ed25519 keypair from the 32-byte seed, signs, and builds a v2
    // signed ANNOUNCE. NodeID = pubkey[:8]. Timestamp is fixed at 1700000000.
    std::vector<uint8_t> seed = fromHex(argv[2]);
    uint8_t caps = (uint8_t)atoi(argv[3]);
    uint32_t seq = (uint32_t)atol(argv[4]);
    std::vector<uint8_t> neighbors;
    for (int i = 5; i < argc; i++) {
      std::vector<uint8_t> nb = fromHex(argv[i]);
      neighbors.insert(neighbors.end(), nb.begin(), nb.end());
    }
    uint8_t pub[32], priv[64];
    ed25519_create_keypair(pub, priv, seed.data());
    size_t nCount = neighbors.size() / mesh::NODE_ID_LEN;

    std::vector<uint8_t> msg;
    mesh::announceSignedMessage(pub, 1700000000u, caps, seq, neighbors.data(), nCount, msg);
    uint8_t sig[64];
    ed25519_sign(sig, msg.data(), msg.size(), pub, priv);

    uint8_t pid[mesh::PACKET_ID_LEN] = {0};
    std::vector<uint8_t> out;
    mesh::buildAnnounce(pub /* selfId = pubkey[:8] */, caps, seq, 1700000000, pid,
                        neighbors.data(), nCount, pub, sig, "esp32-c6", out);
    printHex(out);
    return 0;
  }
  if (std::string(argv[1]) == "--trace-fwd") {
    std::vector<uint8_t> pkt = fromHex(argv[2]);
    std::vector<uint8_t> self = fromHex(argv[3]);
    mesh::PacketHeader h = mesh::parseHeader(pkt.data(), pkt.size(), self.data());
    if (!h.ok) {
      fprintf(stderr, "PARSE_FAILED\n");
      return 1;
    }
    std::vector<uint8_t> fwd;
    if (!mesh::buildTraceSourceRouteForward(pkt.data(), pkt.size(), self.data(),
                                            h.ttl - 1, h.routeCursor + 1, 0, fwd)) {
      fprintf(stderr, "BUILD_TRACE_FAILED\n");
      return 1;
    }
    printHex(fwd);
    return 0;
  }

  std::vector<uint8_t> pkt = fromHex(argv[1]);
  std::vector<uint8_t> self = fromHex(argv[2]);
  if (self.size() != mesh::NODE_ID_LEN) {
    fprintf(stderr, "selfid must be 8 bytes\n");
    return 2;
  }

  mesh::PacketHeader h = mesh::parseHeader(pkt.data(), pkt.size(), self.data());
  if (!h.ok) {
    fprintf(stderr, "PARSE_FAILED\n");
    return 1;
  }
  printf("ok=1 type=%u mode=%u ttl=%u loop=%d id=", h.type, h.mode, h.ttl, h.traceContainsSelf ? 1 : 0);
  for (size_t i = 0; i < mesh::PACKET_ID_LEN; i++) printf("%02x", h.id[i]);
  uint8_t nb[mesh::NODE_ID_LEN];
  printf(" neighbor=");
  if (mesh::directNeighbor(h, nb)) {
    for (size_t i = 0; i < mesh::NODE_ID_LEN; i++) printf("%02x", nb[i]);
  } else {
    printf("none");
  }
  printf("\n");

  std::vector<uint8_t> fwd;
  if (!mesh::buildForward(pkt.data(), pkt.size(), self.data(), h.ttl - 1, fwd)) {
    fprintf(stderr, "BUILD_FAILED\n");
    return 1;
  }
  printHex(fwd);
  return 0;
}
