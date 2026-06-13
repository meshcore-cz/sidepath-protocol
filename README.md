# BLEEdge — Bluetooth LE Coded PHY Mesh Transport PoC

BLEEdge is a proof-of-concept long-range BLE mesh transport layer using
**LE Coded PHY** (S=8 coding, 125 kbps, ~4× standard range improvement).
It consists of:

- A pure-Go routing engine with no Bluetooth dependencies (`core/`)
- A Linux CLI daemon (`bleedge-listen`) using BlueZ D-Bus — **primary Long Range target**
- A macOS CLI daemon (`bleedge-macos`) using CoreBluetooth — 1M PHY debug only
- An in-memory Go simulator (`bleedge-simulate`) with no BLE hardware needed
- A native Kotlin Android app with Jetpack Compose UI

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                    core/                             │
│  types.go   packet.go  fragment.go  dedup.go         │
│  neighbor.go  topology.go  router.go                 │
│  (pure Go, no BLE/OS dependencies)                   │
└──────────────┬───────────────────────────────────────┘
               │ imported by
   ┌───────────┴──────────────┐
   │                          │
┌──▼──────────────┐  ┌──────────────────┐  ┌────────▼────────────────────────┐
│ linux/          │  │ macos/           │  │ android/app/.../                 │
│ adapter.go      │  │ node.go          │  │ core/     (Kotlin port)          │
│ scanner.go      │  │ peer_link.go     │  │ ble/      (BLEManager etc.)      │
│ gatt_server.go  │  │ (CoreBluetooth,  │  │ service/  (BLEEdgeService)       │
│                 │  │  1M debug only)  │  │
│ gatt_client.go  │  │ ui/       (Compose UI)           │
│ peer_link.go    │  └─────────────────────────────────┘
│ node.go         │
└────────┬────────┘
         │
┌────────▼────────────────────────────────────────────┐
│ cmd/bleedge-listen/main.go   (Linux CLI)            │
│ cmd/bleedge-simulate/main.go (In-memory simulator)  │
└────────────────────────────────────────────────────┘
```

### Packet format

Packets are CBOR-encoded with compact integer keys (1–13) for cross-language
interoperability between Go and Kotlin. A packet is fragmented into GATT frames
(23-byte header + payload) for BLE transport.

### Routing

Two modes:
- **Flood** (TTL-limited): broadcast or unicast; all peers relay with random jitter (10–100 ms)
- **Source Route**: explicit hop list; built from BFS over topology learned via ANNOUNCE packets

Deduplication uses a PacketID cache (4096 entries, 5-minute TTL). Loop detection
uses a `Trace` field appended at each hop.

### PHY modes

| Mode | PHY used | Notes |
|------|----------|-------|
| `1m` | 1M legacy | **Default.** Universally supported for advertising *and* scanning |
| `coded-only` | LE Coded (S=8) | Long Range; rejects connections on wrong PHY |
| `coded-preferred` | LE Coded preferred | Falls back to 1M if peer doesn't support Coded |

> **Note on Coded PHY (Long Range):** many devices report `isLeCodedPhySupported = true`
> and can *advertise* on Coded PHY but cannot reliably *scan/receive* it, so two such
> devices never discover each other in `coded-only`. Android exposes no API for the
> coded *scan* capability. `1m` is therefore the default; Coded PHY is an opt-in
> extension that needs a device with a known-good Coded-PHY receiver.

---

## Android Permissions

The app declares the following permissions in `AndroidManifest.xml`:

| Permission | API level | Reason |
|---|---|---|
| `BLUETOOTH` | ≤30 | Legacy BLE access on Android ≤11 |
| `BLUETOOTH_ADMIN` | ≤30 | Legacy scan/advertise control on Android ≤11 |
| `BLUETOOTH_SCAN` | 31+ | Required for all BLE scanning on Android 12+ |
| `BLUETOOTH_CONNECT` | 31+ | Required to connect to GATT peers on Android 12+ |
| `BLUETOOTH_ADVERTISE` | 31+ | Required for extended advertising (Coded PHY) |
| `ACCESS_FINE_LOCATION` | ≤30 | Required by Android ≤11 for BLE scanning |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 31+ | Required for foreground service type `connectedDevice` |

---

## Android build and install

### Prerequisites

- Android Studio Hedgehog or later, or Android SDK command-line tools
- JDK 17
- Android device running API 26+ (Android 8.0+) with BLE support

### Build

```bash
cd android
./gradlew assembleDebug
```

The APK is produced at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### Install

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Verify LE Coded PHY support on device

**In-app:** Open the app → Overview tab → Capabilities section → check "Coded PHY: yes".

**Via ADB:**
```bash
adb shell dumpsys bluetooth_manager | grep -i "coded"
# Look for: "le_coded_phy_support: true"
```

Or check the chipset datasheet for your device. Devices confirmed to support LE Coded PHY:
- Qualcomm QCA6391 / QCA6490 (Snapdragon 865+, 888)
- Samsung Exynos 2100 / 2200
- MediaTek MT6893 (Dimensity 1200)

**Note:** Not all Android 8+ phones support LE Coded PHY in hardware. Pixel 4 and
older do **not**. Pixel 6+ (with Google Tensor), most 2021+ flagship Snapdragon devices do.
In-app the app will show "Coded PHY support: no" and fall back to 1M PHY if `coded-only`
mode is forced.

---

## Linux build

### Prerequisites

- Go 1.22+
- BlueZ 5.56+ (`bluetoothd --version`)
- Linux kernel 5.4+ (for extended advertising / LE Coded PHY)
- `btmgmt` utility (from BlueZ)
- Either `sudo` or `CAP_NET_ADMIN + CAP_NET_RAW` capabilities on the binary

```bash
# Check BlueZ version
bluetoothd --version

# Check kernel version
uname -r
```

### Build

```bash
go build -o bleedge-listen ./cmd/bleedge-listen
```

### Required capabilities

Either run as root:
```bash
sudo ./bleedge-listen --adapter hci0
```

Or grant capabilities to the binary:
```bash
sudo setcap 'cap_net_admin+eip cap_net_raw+eip' ./bleedge-listen
./bleedge-listen --adapter hci0
```

### Usage

```
bleedge-listen [flags]

Flags:
  --adapter      hci0             Bluetooth adapter (default: hci0)
  --node-id      <hex>            8-byte NodeID in hex; loaded from ~/.bleedge/node-id if omitted
  --phy          1m               PHY mode: 1m (default) | coded-only | coded-preferred
  --allow-peer   <hex>            Allowed peer NodeID; repeatable; empty = allow all
  --json                          Output log as JSON lines
  --verbose                       Verbose logging
```

---

## macOS build

> **Important:** macOS CoreBluetooth does **not** expose LE Coded PHY control,
> and cannot broadcast manufacturer data as a peripheral. `bleedge-macos` always
> operates in `1m` mode and is discovered by peers via its BLEEdge service UUID.
> It is useful for smoke-testing the routing engine and packet format, but
> **cannot** be used as a node in the Long Range demonstration. Use
> `bleedge-listen` on Linux for Coded PHY testing.

### Prerequisites

- Go 1.22+
- macOS 12 Monterey or newer (CoreBluetooth with peripheral + central roles)
- Xcode Command Line Tools (`xcode-select --install`) — required for CGo

### Build

```bash
go build -o bleedge-macos ./cmd/bleedge-macos
```

### Permissions

macOS will prompt for Bluetooth access the first time the binary runs.
If running in a terminal, grant access when prompted. If the prompt is suppressed,
go to **System Settings → Privacy & Security → Bluetooth** and add Terminal (or
your IDE) to the allowed apps list.

### Usage

```
bleedge-macos [flags]

Flags:
  --node-id      <hex>    8-byte NodeID in hex; loaded from ~/.bleedge/node-id if omitted
  --allow-peer   <hex>    Allowed peer NodeIDs, comma-separated; empty = allow all
  --send         <text>   Send a broadcast text message 3 s after startup (smoke test)
  --json                  Output log as JSON lines
  --verbose               Log neighbor table every 30 s
```

### Example — smoke test with Android phone in 1m mode

1. The Android app defaults to `1m` (no change needed; selectable on the Overview tab).
2. Start the macOS listener:
   ```bash
   ./bleedge-macos --verbose
   ```
3. The app and the Mac will discover each other and connect.
4. Send a message from the Android app — it should appear in the macOS log:
   ```
   15:04:05  deliver payload-type=1 payload="hello from android" trace=[...]
   ```

### What macOS CANNOT do

- LE Coded PHY scanning or advertising
- Reporting actual TX/RX PHY (CoreBluetooth does not expose this)
- Acting as a relay in the Long Range A → B → Linux demonstration

---

## Test scenarios

### Scenario 1: Direct A → B test

On device A (Linux or Android), note the Node ID displayed at startup:
```
bleedge listener started node=aabbccdd11223344 phy=coded-only
```

On device B (Android), enter device A's Node ID in the Destination field and tap Send.

Device A should log:
```
rx packet id=... source=... ttl=3
deliver payload-type=1 payload="hello"
send ack route=[...]
```

### Scenario 2: A → B → Linux relay

Topology: `Android-A  ←LE Coded→  Android-B  ←LE Coded→  Linux-gateway`

1. Start Linux gateway, note its Node ID (call it `gateway-id`):
   ```bash
   sudo ./bleedge-listen --adapter hci0 --phy coded-only --verbose
   ```

2. Start Android-B, add Android-A to its allowlist (or leave empty to allow all).

3. On Android-A, enter `gateway-id` as destination and send.

4. Android-B relays the flood packet to the Linux gateway.

5. Linux gateway logs:
   ```
   scan found node=<android-b-addr> rssi=-72
   connected peer=<android-b-id> tx-phy=coded rx-phy=coded
   rx packet id=... source=<android-a-id> ttl=2
   trace=[<android-a-id>, <android-b-id>]
   deliver payload-type=text payload="hello relay"
   send ack route=[<android-b-id>, <android-a-id>]
   ```

### Allowlist configuration (Linux)

Only accept packets from specific Android nodes:
```bash
sudo ./bleedge-listen \
  --allow-peer aabbccdd11223344 \
  --allow-peer 1122334455667788 \
  --phy coded-only
```

---

## Simulator

No BLE hardware required:

```bash
go run ./cmd/bleedge-simulate
```

Runs 35 deterministic test scenarios covering flood routing, deduplication, TTL exhaustion,
loop detection, source routing, BFS path calculation, fallback to flood, ACK via reversed
trace, peer disconnect, topology expiration, and large payload fragmentation.

---

## Repository layout

```
go.mod                          Go module: github.com/burningtree/bleedge
core/                           Pure-Go routing engine (no BLE dependencies)
  types.go                      NodeID, PacketType, PHY, Capabilities, etc.
  packet.go                     CBOR encode/decode for Packet and AnnouncePayload
  fragment.go                   GATT fragmentation / reassembly with CRC32
  dedup.go                      Packet ID deduplication cache
  neighbor.go                   Direct-link neighbor table
  topology.go                   Global mesh topology with BFS path finding
  router.go                     Routing decisions: flood, source route, ACK, announce
  router_test.go                40 unit tests
linux/                          BlueZ D-Bus bindings
  adapter.go                    Adapter discovery, PHY capability check
  scanner.go                    D-Bus ObjectManager scanning
  advertiser.go                 LEAdvertisement1 D-Bus object
  gatt_server.go                GATT application (GattService1 + 3 characteristics)
  gatt_client.go                GATT client: connect, discover, subscribe
  peer_link.go                  core.PeerLink implementation over GattClient
  node.go                       Top-level node: scan, connect, route, announce
cmd/
  bleedge-listen/main.go        Linux CLI (BlueZ, LE Coded PHY)
  bleedge-macos/main.go         macOS CLI (CoreBluetooth, 1M debug only)
  bleedge-simulate/main.go      In-memory simulator (35 test scenarios)
android/
  settings.gradle
  build.gradle
  gradle.properties
  app/build.gradle
  app/src/main/AndroidManifest.xml
  app/src/main/java/cz/arnal/bleedge/
    core/                       Kotlin port of core/
    ble/                        BLEManager, Advertiser, Scanner, GattServer, GattClient, PeerLink
    service/BLEEdgeService.kt   Foreground service with StateFlows
    ui/                         Compose UI: MainActivity, MainViewModel, MainScreen
```

---

## Known hardware limitations

- **LE Coded PHY** (LE Long Range) requires explicit hardware + driver support. It is part
  of the Bluetooth 5.0 spec but optional. Not all BT 5.0 chips implement it.
- Confirmed working on Android: Pixel 6/7/8 (Tensor), Samsung Galaxy S21+/S22+ (Exynos/Snapdragon 888).
- **Not** supported on: Pixel 4/4a, most mid-range Snapdragon 7xx pre-2021.
- On Linux: requires kernel ≥5.4 and BlueZ ≥5.56. Some USB BT adapters (CSR8510) do not
  support extended HCI commands needed for Coded PHY scanning.
- The raw HCI socket path for setting `LE_Set_Extended_Scan_Parameters` (OGF=0x08, OCF=0x0041)
  and `LE_Set_PHY` (OGF=0x08, OCF=0x0031) is partially stubbed with `// TODO` comments —
  BlueZ's own extended scan support handles this when kernel and adapter support it.
