#!/usr/bin/env bash
# Host unit test for the relay logic (no ESP-IDF required).
set -euo pipefail
cd "$(dirname "$0")"

CC="${CC:-cc}"
out="$(mktemp -d)/relay_test"
"$CC" -std=c11 -Wall -Wextra -I../main relay_test.c ../main/relay.c -o "$out"
"$out"
