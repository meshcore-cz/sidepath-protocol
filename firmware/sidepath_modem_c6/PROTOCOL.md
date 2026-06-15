# Sidepath BLE modem — serial protocol

Line-oriented ASCII over the ESP32-C6 **USB Serial/JTAG** port. One command per
line, terminated by `\n` (CR is also accepted). The modem answers every command
with exactly one `OK <cmd>` or `ERR <message>` line, and emits asynchronous
`RX` / `RELAY` event lines at any time. Every line is self-delimited, so events
may interleave with command replies.

The host should treat the modem as a dumb radio: it builds and parses Sidepath
datagrams itself and hands the modem **opaque packets**. The only structure the
modem imposes is a leading 1-byte **TTL** (hop limit) on each packet.

## Packet format (the bytes in `SEND` / `RX`)

```
packet = ttl(1) || content(N)
```

- `ttl` — remaining hop count. The modem drops a relayed packet whose TTL is 0
  and decrements TTL by 1 each time it re-advertises.
- `content` — opaque host bytes, up to **229 bytes**. The modem never parses it.

Dedup is by a 32-bit FNV-1a hash over `content` only (TTL is excluded so a
packet is still recognised after an upstream relay decremented it).

On the air the packet rides in a Manufacturer-Specific-Data AD structure with
company id `0x5053` ("SP") and a 1-byte format version; the host never sees
that framing.

## Commands (host → modem)

| Command | Argument | Effect |
|---|---|---|
| `PING` | — | Liveness check. Replies `OK PING`. |
| `INFO` | — | Emits an `INFO ...` line, then `OK INFO`. |
| `SET_PHY` | `1M` \| `CODED` | Select advertising/scanning PHY. Reply echoes the PHY **actually** applied (a rejected `CODED` falls back to `1M`). |
| `SET_TX_POWER` | `LOW` \| `MEDIUM` \| `HIGH` | Set TX power tier. |
| `START_SCAN` | — | Begin scanning for Sidepath frames. |
| `STOP_SCAN` | — | Stop scanning. |
| `SEND` | `<hex>` | Transmit one packet (hex of `ttl||content`). Reply `OK SEND <hash>`. |
| `RELAY_ON` | — | Enable connectionless relay of received frames. |
| `RELAY_OFF` | — | Disable relay (still reports `RX`). |
| `STATS` | — | Emits a `STATS ...` line, then `OK STATS`. |

Commands are case-insensitive. Unknown commands return `ERR unknown command`.

## Events / replies (modem → host)

| Line | Meaning |
|---|---|
| `READY sidepath-modem ext_adv=<0\|1>` | Sent once at boot. |
| `OK <command>` | Command succeeded. Some carry a value, e.g. `OK SEND <hash>`, `OK SET_PHY 1M`. |
| `ERR <message>` | Command failed / was malformed. |
| `RX <rssi> <phy> <hex>` | A Sidepath frame was received. `rssi` in dBm, `phy` is `1M`/`CODED`, `hex` is `ttl||content`. |
| `RELAY <hash> <ttl>` | The modem re-advertised a frame. `hash` is the 8-hex content hash, `ttl` the decremented value. |
| `INFO fw=.. chip=.. phy=.. relay=.. scan=.. ext_adv=.. maxlen=..` | Reply to `INFO`. |
| `STATS rx=.. tx=.. relayed=.. dup=.. ttl_drop=.. seen=.. scan=.. relay=.. uptime=..` | Reply to `STATS`. |

## Relay rules (connectionless)

When relay mode is on, every received frame is:

1. Reported to the host as `RX`.
2. Hashed over `content`; if the hash was seen within the cache TTL (~30 s) →
   **drop** (`dup`).
3. Else if `ttl == 0` → **drop** (`ttl_drop`).
4. Else mark seen, `ttl -= 1`, re-advertise, emit `RELAY <hash> <ttl>`.

The seen-cache is bounded (256 entries) and expiring, so the modem holds no
long-term state and never needs a routing database.

## Example session

```
< READY sidepath-modem ext_adv=1
> PING
< OK PING
> SET_PHY CODED
< OK SET_PHY CODED
> RELAY_ON
< OK RELAY_ON
> START_SCAN
< OK START_SCAN
> SEND 08deadbeef
< OK SEND 0d3b8f1a
  ...
< RX -57 CODED 07deadbeef
< RELAY 0d3b8f1a 6
```
