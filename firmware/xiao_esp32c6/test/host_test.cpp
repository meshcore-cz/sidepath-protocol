// Host harness for Sidepath v3 firmware mesh logic.
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
            "usage: %s <datagram-hex> <selfid-hex>\n"
            "       %s --src-fwd <datagram-hex> <selfid-hex>\n"
            "       %s --crc <hex>\n"
            "       %s --frag <hex> <mtu>\n"
            "       %s --announce <seedhex> <epoch> <seq> [neighborhex ...]\n",
            argv[0], argv[0], argv[0], argv[0], argv[0]);
    return 2;
  }

  if (std::string(argv[1]) == "--crc") {
    auto d = fromHex(argv[2]);
    printf("%08x\n", mesh::crc32_ieee(d.data(), d.size()));
    return 0;
  }
  if (std::string(argv[1]) == "--frag") {
    auto d = fromHex(argv[2]);
    size_t mtu = (size_t)atoi(argv[3]);
    std::vector<std::vector<uint8_t>> frames;
    mesh::fragment(d.data(), d.size(), mtu, frames);
    for (auto& f : frames) printHex(f);
    return 0;
  }
  if (std::string(argv[1]) == "--announce") {
    auto seed = fromHex(argv[2]);
    uint64_t epoch = strtoull(argv[3], nullptr, 10);
    uint32_t seq = (uint32_t)strtoul(argv[4], nullptr, 10);
    std::vector<uint8_t> neighbors;
    for (int i = 5; i < argc; i++) {
      auto nb = fromHex(argv[i]);
      neighbors.insert(neighbors.end(), nb.begin(), nb.end());
    }
    uint8_t pub[32], priv[64];
    ed25519_create_keypair(pub, priv, seed.data());
    size_t nCount = neighbors.size() / mesh::NODE_ID_LEN;
    // Mirror sendAnnounce: emit v3 with a neighbor_info section when neighbors are given (each tagged
    // outbound, 1M PHY, no RSSI/age sample), else v1 with the bare list. Verifiers accept both.
    std::vector<mesh::AnnounceNeighborInfo> infos;
    for (size_t i = 0; i < nCount; i++) {
      mesh::AnnounceNeighborInfo ni{};
      for (size_t k = 0; k < mesh::NODE_ID_LEN; k++) ni.id[k] = neighbors[i * mesh::NODE_ID_LEN + k];
      ni.txPhy = mesh::PHY_1M;
      ni.rxPhy = mesh::PHY_1M;
      ni.dir = mesh::CONN_DIR_OUT;
      ni.rssi = (int8_t)(-50 - (int)i);  // exercise negative-RSSI CBOR + signed-byte encoding
      ni.ageS = 12 + (uint32_t)i;        // exercise the age field
      infos.push_back(ni);
    }
    uint8_t version = nCount ? mesh::ANNOUNCE_VERSION : 1;
    const uint8_t* bare = version >= 3 ? nullptr : neighbors.data();
    size_t bareCount = version >= 3 ? 0 : nCount;
    std::vector<uint8_t> msg;
    mesh::announceSignedMessage(pub, epoch, seq, 1700000000, mesh::CAP_RECEIVER | mesh::CAP_RELAY, version,
                                bare, bareCount, infos.data(), infos.size(), "", "", "esp32-c6", msg);
    uint8_t sig[64];
    ed25519_sign(sig, msg.data(), msg.size(), pub, priv);
    uint8_t id[mesh::DATAGRAM_ID_LEN] = {0};
    std::vector<uint8_t> out;
    mesh::buildAnnounce(pub, mesh::CAP_RECEIVER | mesh::CAP_RELAY, epoch, seq, 1700000000,
                        id, version, bare, bareCount, infos.data(), infos.size(),
                        pub, sig, "", "", "esp32-c6", out);
    printHex(out);
    return 0;
  }

  bool srcFwd = std::string(argv[1]) == "--src-fwd";
  auto dg = fromHex(srcFwd ? argv[2] : argv[1]);
  auto self = fromHex(srcFwd ? argv[3] : argv[2]);
  if (self.size() != mesh::NODE_ID_LEN) {
    fprintf(stderr, "selfid must be %zu bytes\n", mesh::NODE_ID_LEN);
    return 2;
  }

  mesh::DatagramHeader h = mesh::parseDatagram(dg.data(), dg.size(), self.data());
  if (!h.ok) {
    fprintf(stderr, "PARSE_FAILED\n");
    return 1;
  }
  printf("ok=1 protocol=%u ttl=%u source_routed=%d loop=%d id=", h.protocol, h.ttl,
         h.sourceRouted ? 1 : 0, h.pathContainsSelf ? 1 : 0);
  for (size_t i = 0; i < mesh::DATAGRAM_ID_LEN; i++) printf("%02x", h.id[i]);
  uint8_t nb[mesh::NODE_ID_LEN];
  printf(" neighbor=");
  if (mesh::directNeighbor(h, nb)) {
    for (size_t i = 0; i < mesh::NODE_ID_LEN; i++) printf("%02x", nb[i]);
  } else {
    printf("none");
  }
  printf("\n");

  std::vector<uint8_t> fwd;
  bool ok = srcFwd
      ? mesh::buildSourceRouteForward(dg.data(), dg.size(), self.data(), h.ttl - 1, h.routeCursor + 1, fwd)
      : mesh::buildFloodForward(dg.data(), dg.size(), self.data(), h.ttl - 1, fwd);
  if (!ok) {
    fprintf(stderr, "BUILD_FAILED\n");
    return 1;
  }
  printHex(fwd);
  return 0;
}
