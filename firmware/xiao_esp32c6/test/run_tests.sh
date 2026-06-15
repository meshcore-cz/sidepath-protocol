#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
bin="$(mktemp -t sidepath_v3_host_test.XXXXXX)"
trap 'rm -f "$bin"' EXIT

echo "==> compiling v3 host harness"
ed="$here/../src/ed25519"
c++ -std=c++17 -I"$here" -I"$here/.." -I"$ed" \
  "$here/host_test.cpp" "$here/../mesh.cpp" \
  "$ed/fe.c" "$ed/ge.c" "$ed/sc.c" "$ed/sha512.c" \
  "$ed/keypair.c" "$ed/sign.c" "$ed/verify.c" \
  -o "$bin"

echo "==> crc32"
[[ "$("$bin" --crc 01020304)" == "b63cfbcd" ]]

echo "==> frame version"
frame="$("$bin" --frag 010203 60 | head -n1)"
[[ "${frame:0:2}" == "02" ]]

echo "==> v1 announce parse (no neighbors)"
seed="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
self="03a107bff3ce10be1d70"
announce="$("$bin" --announce "$seed" 1 7)"
parsed="$("$bin" "$announce" "$self" | head -n1)"
[[ "$parsed" == ok=1* ]]
[[ "$parsed" == *"protocol=0"* ]]
[[ "$parsed" == *"ttl=5"* ]]

echo "==> v3 announce parse (with neighbor_info)"
# A neighbor id triggers the v3 path: the bare list goes empty and a neighbor_info section is carried.
announce3="$("$bin" --announce "$seed" 1 7 00112233445566778899)"
parsed3="$("$bin" "$announce3" "$self" | head -n1)"
[[ "$parsed3" == ok=1* ]]
[[ "$parsed3" == *"protocol=0"* ]]
[[ "$parsed3" == *"ttl=5"* ]]
# The v3 body carries the neighbor_info entry, so it must be longer than the v1 body.
[[ "${#announce3}" -gt "${#announce}" ]]

echo "ok"
