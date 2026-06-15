# Sidepath BLE modem — ESP32-C6 (ESP-IDF)

An ESP32-C6 acting as an **external BLE radio** for a host (macOS / Linux /
Android) over USB serial. It does one job: move opaque **Sidepath** packets over
**connectionless BLE advertisements**, with an optional store-and-reflood relay.

There is deliberately **no chat logic, no MeshCore logic, and no routing
database** on the device — those live on the host. The modem only flips bytes
between the USB serial port and the BLE radio.

```
   host (sp / app)  <--USB serial-->  ESP32-C6 modem  <--BLE adv flood-->  other modems
        builds/parses Sidepath          dumb radio + relay        connectionless mesh
```

## Features

- **Connectionless relay** — scan → parse → dedup by content hash → drop on
  `TTL=0` → decrement TTL → re-advertise. Small expiring seen-cache, no routing
  state.
- **BLE 5 extended advertising** preferred (up to 229 bytes of opaque content
  per frame vs. 31 for legacy).
- **LE Coded PHY / Long Range** requested by default, with automatic **fallback
  to 1M** if the controller rejects the coded configuration. `SET_PHY 1M` and
  `SET_PHY CODED` remain available for host-side experiments.
- **Line-oriented USB serial protocol** — see [PROTOCOL.md](PROTOCOL.md).

## Layout

| File | Purpose |
|------|---------|
| `main/main.c` | Entry point; wires serial ⇄ relay ⇄ BLE. |
| `main/ble.c` / `ble.h` | NimBLE init, extended advertising, scanning, PHY/TX-power, coded→1M fallback. |
| `main/relay.c` / `relay.h` | Expiring seen-cache and the dedup/TTL relay decision (pure logic). |
| `main/serial.c` / `serial.h` | USB Serial/JTAG command parser and event emitters. |
| `main/frame.h` | OTA frame format + content hash. |
| `tools/test_modem.py` | Host smoke + two-modem relay test. |
| `PROTOCOL.md` | Serial command/event reference. |

## Prerequisites

- [ESP-IDF **v5.x**](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c6/get-started/)
  installed and exported (`. $IDF_PATH/export.sh`).
- A Seeed XIAO ESP32-C6 (or any ESP32-C6 board) on USB.

## Build, flash, monitor

```bash
cd firmware/sidepath_modem_c6
idf.py set-target esp32c6

# build
idf.py build

# flash + open the serial monitor (replace the port for your OS)
idf.py -p /dev/ttyACM0 flash monitor      # Linux
idf.py -p /dev/cu.usbmodem* flash monitor # macOS
```

Exit the monitor with `Ctrl-]`.

The same USB cable carries both the flashing/monitor traffic and the line
protocol (native USB Serial/JTAG). At runtime any terminal at 115200 baud works
(USB CDC ignores the baud value):

```bash
# quick manual poke
python3 - <<'PY'
import serial; s=serial.Serial('/dev/ttyACM0',115200,timeout=1)
s.write(b'PING\n'); print(s.readline())
PY
```

## Host test script

```bash
pip install pyserial

# single modem: PING/INFO/SEND/relay-toggle/STATS smoke test
python3 tools/test_modem.py --port /dev/ttyACM0

# two modems on the same machine: verify a SENT packet is RX'd on the peer
python3 tools/test_modem.py --port /dev/ttyACM0 --peer /dev/ttyACM1
```

## Driving it from `sp`

The `sp` CLI speaks this protocol directly:

```bash
sp modem --port /dev/ttyACM0 ping
sp modem --port /dev/ttyACM0 info
sp modem --port /dev/ttyACM0 set-phy 1m   # optional override; default is coded when available
sp modem --port /dev/ttyACM0 send 08deadbeef
sp modem --port /dev/ttyACM0 relay on
sp modem --port /dev/ttyACM0 monitor      # stream RX/RELAY events
```

The daemon can also own a modem (`sp daemon run --modem /dev/ttyACM0`); see the
top-level project README and `sp modem --help`.

## Notes & limitations

- **No connections / no GATT.** This firmware is advertiser + observer only. It
  is *not* the GATT relay hub in `firmware/xiao_esp32c6/` — it is a separate,
  simpler connectionless modem.
- Extended advertising and Coded PHY require ESP-IDF's NimBLE with BLE 5 enabled
  (already set in `sdkconfig.defaults`).
- Coded PHY roughly quadruples range at the cost of throughput; both ends must
  be on the same PHY to interoperate on the primary channels. The modem requests
  Coded at boot and reports the active PHY via `INFO`.
