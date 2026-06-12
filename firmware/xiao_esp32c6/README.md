# BLEEdge relay firmware — Seeed Studio XIAO ESP32-C6

Minimal relay node for the BLEEdge mesh. The ESP32-C6 runs as a **multi-connection
GATT-server "relay hub"**: phones and other nodes connect to it as GATT clients —
exactly the way they connect to each other — and it floods packets between all
connected peers, applying the same routing rules as the rest of the mesh
(dedup, TTL, loop/path checks, flood/source-route). Two nodes that can't reach each other directly can
talk through the ESP32.

It is wire-compatible with the Go (`core/`, `linux/`, `macos/`) and Android nodes:
same GATT UUIDs, v2 23-byte frame fragmentation + CRC32, and v3 CBOR datagrams
with integer keys.

## What it does

- Advertises the BLEEdge service UUID (primary advert) + `0xBEED` manufacturer data
  carrying its NodeID (scan response) so any node discovers it on 1M PHY.
- GATT server with the three BLEEdge characteristics: `NODE_INFO` (read),
  `PACKET_IN` (write), `PACKET_OUT` (notify).
- Reassembles incoming frames, decodes the datagram, and for **flood** datagrams:
  drops duplicates / loops / TTL-exhausted, appends itself to the path, decrements
  TTL, re-fragments, and notifies every connected peer **except** the sender.
- For **source-routed** datagrams where its NodeID is the current route hop,
  appends itself to the path, advances `route_cursor`, and notifies only the
  connected next hop on the selected route.
- Sends a periodic signed v3 `ANNOUNCE` (every 15 s) advertising `relay` capability **and its
  learned neighbors**, so it appears correctly in other nodes' topology. Neighbors
  are learned from received traffic — the last path hop (or the source of a fresh
  datagram) is the directly-connected peer — which works even though every connection
  is inbound (the phones connect to the relay, not the other way around).
- Derives a stable 10-byte NodeID from its persisted Ed25519 public key (`pubkey[:10]`).
- Provides admin commands over USB serial and encrypted direct BLEEdge Chat
  messages for relay diagnostics, admin-key management, and clock setup.

## Limitations (intentional, for a "basic" relay)

- **Peripheral/server role only.** It does not scan or initiate connections, so it
  won't link two *other* relays together; it bridges the centrals (phones) that
  connect to it. Adding the central role (scan + GATT client) is the natural next step.
- **Peripheral/server relay only.** Source-routed datagrams are forwarded only
  when this relay is on the selected route and the next hop is currently connected.
- **1M PHY.** Matches the mesh default. (The C6 supports Coded PHY; enabling Long
  Range advertising is a future extension.)
- Default max simultaneous connections is set by NimBLE
  (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS`, typically 3). Raise it to bridge more peers.

## Build & flash (Arduino IDE)

1. Install the **arduino-esp32** core **≥ 3.0.0** (Boards Manager → "esp32 by Espressif").
2. Install **NimBLE-Arduino ≥ 2.1.0** (Library Manager → "NimBLE-Arduino").
3. Select board **"XIAO_ESP32C6"**.
4. Optional: set `BLEEDGE_ADMIN_PUBKEY` to a 32-byte Ed25519 public key hex
   string in your build flags to enable a built-in remote admin.
5. Open `xiao_esp32c6.ino` (keep `mesh.h` / `mesh.cpp` / `remote_admin.h`
   in the same folder) and Upload.
6. Open Serial Monitor at **115200 baud** — you'll see the node ID, advertising
   status, peer connect/disconnect, and per-packet relay/forward logs.

## Admin commands

Admin commands are available in two places:

- USB Serial Monitor at 115200 baud.
- Encrypted BLEEdge Chat v1 `DIRECT_TEXT` messages addressed to the relay node.
  The sender must be a configured admin public key. Remote admin replies are sent
  back as encrypted `DIRECT_TEXT` messages.

Commands:

- `help` — show supported commands.
- `sensors` — read the ESP32 temperature sensor.
- `stats` — uptime, peer/neighbor counts, datagram/frame counters, relay counters,
  announce count, and current clock state.
- `clock` — show the relay's internal UTC clock, or `unset`.
- `clock.set <YYYY-MM-DDTHH:MM:SSZ>` — set the relay's volatile internal UTC
  clock. Signed announces use this timestamp once set; before that, announces use
  timestamp `0` as specified for devices without a reliable clock.
- `admins` — list built-in and runtime remote-admin public keys.
- `admin.add <pubkey>` — add a runtime remote-admin Ed25519 public key.
- `admin.remove <pubkey>` — remove a runtime remote-admin public key. Built-in
  admins cannot be removed at runtime.

Runtime admins are stored in ESP32 NVS under the relay's admin namespace.

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
| `remote_admin.h` | Encrypted direct-chat remote admin commands and admin-key storage |
| `mesh.h` / `mesh.cpp` | Wire-format logic: CRC32, fragment/reassemble, CBOR parse + relay transcode, ANNOUNCE builder. No BLE deps. |
| `test/` | Host cross-check harness — validates the mesh logic against the Go reference |

## Tests

`mesh.cpp` is pure logic and is unit-tested on the host for v3 CRC, frame, and
announce parsing behavior:

```bash
./firmware/xiao_esp32c6/test/run_tests.sh
```

Requires a host C++17 compiler.
