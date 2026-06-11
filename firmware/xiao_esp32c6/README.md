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
- For **TRACE source-route** packets where its NodeID is the current route hop,
  appends itself to the trace, appends a trace metric sample, advances
  `route_cursor`, and notifies only the connected next hop on the selected track.
  TRACE packets in flood mode are not relayed.
- Sends a periodic `ANNOUNCE` (every 15 s) advertising `relay` capability **and its
  learned neighbors**, so it appears correctly in other nodes' topology. Neighbors
  are learned from received traffic — the last trace hop (or the source of a fresh
  packet) is the directly-connected peer — which works even though every connection
  is inbound (the phones connect to the relay, not the other way around).
- Derives a stable 8-byte NodeID from its persisted Ed25519 public key (`pubkey[:8]`).
- Accepts encrypted direct-message remote-control commands addressed to its NodeID
  from configured admin Ed25519 public keys, and replies by encrypted DM.

## Limitations (intentional, for a "basic" relay)

- **Peripheral/server role only.** It does not scan or initiate connections, so it
  won't link two *other* relays together; it bridges the centrals (phones) that
  connect to it. Adding the central role (scan + GATT client) is the natural next step.
- **TRACE-only source routing.** Ordinary source-routed DATA is not forwarded
  (logged and dropped). TRACE source-route packets are forwarded only when this
  relay is on the selected track and the next hop is currently connected.
- **1M PHY.** Matches the mesh default. (The C6 supports Coded PHY; enabling Long
  Range advertising is a future extension.)
- Default max simultaneous connections is set by NimBLE
  (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS`, typically 3). Raise it to bridge more peers.

## Build & flash (Arduino IDE)

1. Install the **arduino-esp32** core **≥ 3.0.0** (Boards Manager → "esp32 by Espressif").
2. Install **NimBLE-Arduino ≥ 2.1.0** (Library Manager → "NimBLE-Arduino").
3. Add build-time remote-control admins in `ADMIN_PUBKEYS` near the top of
   `xiao_esp32c6.ino` as 64-character Ed25519 public-key hex strings.
4. Select board **"XIAO_ESP32C6"**.
5. Open `xiao_esp32c6.ino` (keep `mesh.h` / `mesh.cpp` in the same folder) and Upload.
6. Open Serial Monitor at **115200 baud** — you'll see the node ID, advertising
   status, peer connect/disconnect, and per-packet relay/forward logs.

## Remote control

Send an encrypted direct message to the ESP32 node. The sender public key carried
inside the chat envelope must be a build-time admin or a runtime admin stored in
NVS; otherwise the node replies `not authenticated`.

Commands:

- `help` — show supported commands.
- `sensors` — read the ESP32-C6 internal temperature.
- `stats` — uptime, peer/neighbor counts, packet/frame counters, command counters,
  free heap, plus placeholders for metrics that BLE does not expose (`noise_floor`
  and `air_time`).
- `admins` or `admin` — list build-time and runtime admin public keys.
- `admin.add <pubkey>` — add a runtime admin public key to NVS.
- `admin.remove <pubkey>` — remove a runtime admin public key from NVS. Build-time
  admins cannot be removed at runtime.

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
