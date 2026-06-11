# BLEEdge relay firmware — Seeed Studio XIAO ESP32-C6

Minimal relay node for the BLEEdge mesh. The ESP32-C6 runs as a **multi-connection
GATT-server "relay hub"**: phones and other nodes connect to it as GATT clients —
exactly the way they connect to each other — and it floods packets between all
connected peers, applying the same routing rules as the rest of the mesh
(dedup, TTL, loop/trace, flood). Two nodes that can't reach each other directly can
talk through the ESP32.

It is wire-compatible with the Go (`core/`, `linux/`, `macos/`) and Android nodes:
same GATT UUIDs, the same 23-byte fragment framing + CRC32, and the same CBOR packet
format with integer keys.

## What it does

- Advertises the BLEEdge service UUID (primary advert) + `0xBEED` manufacturer data
  carrying its NodeID (scan response) so any node discovers it on 1M PHY.
- GATT server with the three BLEEdge characteristics: `NODE_INFO` (read),
  `PACKET_IN` (write), `PACKET_OUT` (notify).
- Reassembles incoming frames, decodes the packet, and for **flood** packets:
  drops duplicates / loops / TTL-exhausted, appends itself to the trace, decrements
  TTL, re-fragments, and notifies every connected peer **except** the sender.
- Sends a periodic `ANNOUNCE` (every 15 s) advertising `relay` capability **and its
  learned neighbors**, so it appears correctly in other nodes' topology. Neighbors
  are learned from received traffic — the last trace hop (or the source of a fresh
  packet) is the directly-connected peer — which works even though every connection
  is inbound (the phones connect to the relay, not the other way around).
- Derives a stable 8-byte NodeID from the chip's eFuse MAC (`E5 C6 <6-byte MAC>`).

## Limitations (intentional, for a "basic" relay)

- **Peripheral/server role only.** It does not scan or initiate connections, so it
  won't link two *other* relays together; it bridges the centrals (phones) that
  connect to it. Adding the central role (scan + GATT client) is the natural next step.
- **FLOOD packets only.** Source-routed packets are not forwarded (logged and dropped).
  This is fine in practice — DATA falls back to flood when no route is known, and
  ANNOUNCEs are always flood.
- **1M PHY.** Matches the mesh default. (The C6 supports Coded PHY; enabling Long
  Range advertising is a future extension.)
- Default max simultaneous connections is set by NimBLE
  (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS`, typically 3). Raise it to bridge more peers.

## Build & flash (Arduino IDE)

1. Install the **arduino-esp32** core **≥ 3.0.0** (Boards Manager → "esp32 by Espressif").
2. Install **NimBLE-Arduino ≥ 2.1.0** (Library Manager → "NimBLE-Arduino").
3. Select board **"XIAO_ESP32C6"**.
4. Open `xiao_esp32c6.ino` (keep `mesh.h` / `mesh.cpp` in the same folder) and Upload.
5. Open Serial Monitor at **115200 baud** — you'll see the node ID, advertising
   status, peer connect/disconnect, and per-packet relay/forward logs.

### arduino-cli

```bash
arduino-cli core install esp32:esp32
arduino-cli lib install "NimBLE-Arduino"
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 firmware/xiao_esp32c6
arduino-cli upload  --fqbn esp32:esp32:XIAO_ESP32C6 -p /dev/ttyACM0 firmware/xiao_esp32c6
```

## Trying it

1. Flash the XIAO and power it.
2. On two Android phones (or a phone + a Mac running `bleedge-macos`), set PHY to
   `1m` and start the service. Place them far enough apart that they don't connect
   directly, but both in range of the XIAO.
3. Each connects to `BLEEdge` (the relay). Send a broadcast message from one — it
   reaches the other via the relay, and the relay shows up in the Topology tab
   (with `relay` capability and a recent "last announce" time).

## Files

| File | Purpose |
|------|---------|
| `xiao_esp32c6.ino` | BLE setup, advertising, GATT server, relay glue (NimBLE) |
| `mesh.h` / `mesh.cpp` | Wire-format logic: CRC32, fragment/reassemble, CBOR parse + relay transcode, ANNOUNCE builder. No BLE deps. |
| `test/` | Host cross-check harness — validates the mesh logic against the Go reference |

## Tests

`mesh.cpp` is pure logic and is unit-tested on the host against the Go reference
implementation (relay transcode round-trip, CRC32, byte-identical fragmentation):

```bash
./firmware/xiao_esp32c6/test/run_tests.sh
```

Requires a host C++17 compiler and the Go toolchain (run from the repo root).
