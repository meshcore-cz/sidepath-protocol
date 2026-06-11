# BLEEdge Protocol Specification

**Protocol version: 1**

This document is the single source of truth for the on-the-wire behavior of the
BLEEdge BLE Coded-PHY mesh transport. Every implementation must conform to it:

| Implementation | Location | Roles |
| --- | --- | --- |
| Go core (reference) | `core/` | routing engine, no BLE deps |
| Linux | `linux/`, `cmd/bleedge-listen/` | central + peripheral (BlueZ D-Bus) |
| macOS | `macos/`, `cmd/bleedge-macos/` | central + peripheral (CoreBluetooth, 1M only) |
| Android | `android/` | central + peripheral (Kotlin) |
| ESP32-C6 | `firmware/xiao_esp32c6/` | peripheral-only relay hub (NimBLE) |

The Go `core/` package is the **reference implementation**. When this document
and the code disagree, `core/` wins, and this document must be corrected. The
ESP32 `mesh.cpp` and Android Kotlin port are cross-checked against `core/`
(see `firmware/xiao_esp32c6/test/run_tests.sh`).

> Scope: this is an early PoC. LoRa, MeshCore protocol framing, encryption, and
> auth are out of scope. The packet payload is opaque bytes.

---

## 1. Identity

- **NodeID** = 8 random bytes, generated once per node. **Not** the BLE MAC
  address (which rotates). Rendered as lowercase hex.
- NodeID ordering (`Less`) is a plain big-endian lexicographic byte compare.
- The all-zero NodeID is reserved and means **broadcast** as a packet destination.

---

## 2. BLE layer

### 2.1 GATT service & characteristics

| Name | UUID | Properties |
| --- | --- | --- |
| Service | `9b7e6a10-7d91-4c19-a3b8-6e2a11f3a001` | primary |
| `NODE_INFO` | `9b7e6a10-7d91-4c19-a3b8-6e2a11f3a002` | Read |
| `PACKET_IN` | `9b7e6a10-7d91-4c19-a3b8-6e2a11f3a003` | Write / Write-No-Response |
| `PACKET_OUT` | `9b7e6a10-7d91-4c19-a3b8-6e2a11f3a004` | Notify |

- A client **writes** a GATT frame to `PACKET_IN` to send into the mesh.
- The server **notifies** on `PACKET_OUT` to push a GATT frame to the client.
- Both directions carry the same [frame format](#3-gatt-frame-format).

**`NODE_INFO`** is a 10-byte read value identifying the peer without parsing an
ANNOUNCE:

```
version(1) | nodeID(8) | caps(1)
```

### 2.2 Advertising & discovery

- NodeID is advertised in **manufacturer-specific data** with company ID
  `0xBEED`, payload = the raw 8-byte NodeID, in the **primary** advertising PDU.
  Peers learn each other's NodeID from the scan result without connecting.
- The BLEEdge service UUID is also advertised.
- A scanner accepts a device that has **either** the `0xBEED` manufacturer data
  **or** the BLEEdge service UUID. macOS/CoreBluetooth peripherals cannot
  broadcast manufacturer data, so they are discovered by service UUID and their
  NodeID is read from `NODE_INFO` after connecting. **Do not** tighten scanners
  to manufacturer-data-only — it breaks macOS interop.
- Do not rely on a hardware `ScanFilter`: some chipsets silently drop Coded-PHY
  extended PDUs when a filter is set. Filter app-side on the `0xBEED` data.

### 2.3 Connection rule

**"Whoever discovers, connects."** There is no NodeID-ordering gate on *whether*
to connect (that deadlocks under asymmetric discovery). Instead, mutual
double-connections are collapsed deterministically: of the two links between the
same NodeID pair, the node with the **larger** NodeID drops its outgoing link and
keeps the inbound one. Dedup links by NodeID.

### 2.4 PHY modes

Selectable per node; default is **`1m`**:

| Mode string | Meaning |
| --- | --- |
| `1m` | 1M PHY only (default, universally supported for adv + scan) |
| `coded-preferred` | prefer Coded PHY, fall back to 1M |
| `coded-only` | Coded PHY (Long Range) only |

> Legacy stored value `"1m-debug"` maps to `1m`.

**Known limitation:** many devices report Coded-PHY support and can *advertise*
on it but cannot reliably *scan/receive* coded extended advertisements, so they
never discover each other on `coded-only`. Android exposes no API for coded-scan
capability. Use `1m` for interop testing.

---

## 3. GATT frame format

A frame is the GATT transport unit. A mesh packet (section 4) is CBOR-encoded,
then split into one or more frames that fit the negotiated MTU.

**Big-endian, fixed 23-byte header + data:**

```
offset  size  field
0       1     version          (= 1)
1       16    packet_id        (matches the packet's CBOR id field)
17      1     fragment_index   (0-based)
18      1     fragment_count    (total fragments for this packet_id)
19      4     payload_crc32    (CRC-32/IEEE of the *entire* reassembled packet bytes)
23      N     data             (this fragment's slice of the packet bytes)
```

### 3.1 Fragmentation

A frame must fit in a single GATT write, i.e. `frame_size <= ATT_MTU - 3` (the
ATT opcode + handle cost 3 bytes). Within that budget the header costs 23 bytes,
so `data_per_frame = frame_size - 23`.

**All implementations use one shared maximum frame size: `MAX_FRAME_SIZE = 200`
bytes** (→ `data_per_frame = 200 - 23 = 177`). Rationale:

- Nodes request an ATT MTU of 512, but a node fragments a broadcast or relayed
  packet **once** and sends the identical frames to every neighbor. Those frames
  must therefore fit the **smallest** peer's link. The ESP32 relay negotiates an
  ATT MTU of 247 (→ max frame 244), so a fixed cap of 200 is safe for everyone.
- Reassembly is size-agnostic (it concatenates by index and checks CRC), so a
  uniform small frame size costs nothing in interop and keeps all four
  implementations byte-for-byte identical.

This constant is `core.MaxFrameSize` (Go), `MAX_FRAME_SIZE` (Kotlin), and
`FRAGMENT_MTU` (firmware) — they must stay equal. Do **not** fragment using the
raw negotiated 512: a 512-byte frame cannot be written to the ESP32's 247-MTU
link. `core.FragmentPacket(data, mtu, id)`'s `mtu` argument is the max **frame**
size (it subtracts the 23-byte header internally) — pass `MaxFrameSize`.

- All frames of one packet share the same `packet_id`, `fragment_count`, and
  `payload_crc32`. `fragment_index` runs `0 .. fragment_count-1`.

### 3.2 Reassembly

- Collect frames by `packet_id`. Duplicate `fragment_index` values are ignored.
- When all `fragment_count` indices are present, concatenate by index and verify
  CRC-32 over the result. **CRC mismatch → drop.**
- Reassembly buffers time out after **10 s** without a new fragment.

---

## 4. Mesh packet (CBOR)

Packets are CBOR maps with **compact integer keys** (not string keys) for
cross-language compactness. Field order is irrelevant; keys are authoritative.

| Key | Field | Type | Notes |
| --- | --- | --- | --- |
| 1 | `version` | uint8 | must equal `1`, else drop |
| 2 | `type` | uint8 | 1=DATA, 2=ANNOUNCE, 3=ACK |
| 3 | `id` | 16 bytes | random per packet; dedup key |
| 4 | `source` | 8 bytes | originating NodeID |
| 5 | `destination` | 8 bytes | zero = broadcast |
| 6 | `mode` | uint8 | 1=FLOOD, 2=SOURCE_ROUTE |
| 7 | `ttl` | uint8 | decremented per hop |
| 8 | `route_cursor` | uint8 | index into `route` (source-route) |
| 9 | `route` | array of 8-byte | ordered next-hops (source-route) |
| 10 | `trace` | array of 8-byte | hops visited so far (appended per hop) |
| 11 | `payload_type` | uint8 | 1=TEXT_TEST, 2=MESHCORE_RAW |
| 12 | `payload` | bytes | opaque |
| 13 | `seq` | uint32 | ANNOUNCE sequence; omitted when 0 |

> Note: the GATT frame `packet_id` (16 bytes) is the same value as packet key 3.

### 4.1 ANNOUNCE payload

When `type == 2`, `payload` (key 12) is itself a CBOR map:

| Key | Field | Type |
| --- | --- | --- |
| 1 | `node_id` | 8 bytes |
| 2 | `caps` | uint8 (capability bitmask) |
| 3 | `neighbors` | array of 8-byte NodeID |
| 4 | `seq` | uint32 |
| 5 | `timestamp` | int64 (unix seconds) |

### 4.2 Capabilities bitmask

| Bit | Flag | Name |
| --- | --- | --- |
| 0x01 | sender | `CapSender` |
| 0x02 | receiver | `CapReceiver` |
| 0x04 | relay | `CapRelay` |
| 0x08 | gateway | `CapGateway` |
| 0x10 | coded-phy | `CapCodedPHY` |

Conventional role sets:
- Android: `sender | receiver | relay | coded-phy`
- Linux: `receiver | gateway | coded-phy`

`String()` renders flags pipe-joined (`sender|relay|...`) or `none`.

---

## 5. Routing engine

All nodes run the same decision logic (`core.Router.HandlePacket`). It is pure:
given an incoming packet and the peer it arrived from, it returns a list of
**actions** for the transport to execute (`deliver-local`, `relay-flood`,
`relay-next-hop`, `send-ack`, `drop`).

### 5.1 Common checks (every packet)

1. **Version**: `version != 1` → drop (`invalid-version`).
2. **Allowlist** (optional): if a non-empty allowlist is configured and the
   incoming peer is not in it → drop (`peer-not-allowed`).
3. ANNOUNCE (`type==2`) updates local [topology](#54-topology) then is flooded.

### 5.2 FLOOD mode (`mode == 1`)

1. **Dedup**: if `id` already in the seen-cache → drop (`duplicate`).
2. **Loop**: if `LocalID` already appears in `trace` → drop (`loop`).
3. **TTL**: if `ttl == 0` → drop (`ttl-exhausted`).
4. Append `LocalID` to `trace`.
5. **Deliver locally** if broadcast or `destination == LocalID`. For a *unicast*
   DATA packet addressed to us, also emit an ACK (section 5.5).
6. **Relay** if `ttl > 1` and (broadcast or not addressed to us): decrement TTL
   and re-flood. The incoming peer is excluded from the relay set (split-horizon).

Apply **flood jitter** of a random 10–100 ms before relaying to reduce collisions.

### 5.3 SOURCE_ROUTE mode (`mode == 2`)

1. Dedup, loop, TTL checks as above.
2. If `route[route_cursor] != LocalID` → drop (`not-next-hop`).
3. Append self to `trace`, increment `route_cursor`, decrement `ttl`.
4. If `route_cursor >= len(route)` we are the destination → deliver locally
   (+ ACK for DATA). Otherwise relay to `route[route_cursor]` (`relay-next-hop`).

### 5.4 ACK (`type == 3`)

ACKs are generated automatically for **unicast DATA** that is delivered locally:

- `destination = original.source`, `ttl = len(trace)+1`.
- If the inbound `trace` had >1 hop, the ACK is sent **SOURCE_ROUTE** along the
  reversed trace (excluding self); otherwise it is **FLOOD**.
- The originator records the ACK's own `id` in its dedup cache so a flood echo of
  its own ACK is dropped rather than re-flooded. The same applies to *any* packet
  a node originates (`MarkOriginated`).

### 5.5 Route selection

`SelectRoute(dst)`:
- If `dst` is a direct neighbor → send direct (no source route).
- Else BFS over learned topology for a path; if found → SOURCE_ROUTE.
- Else → fall back to FLOOD.

---

## 6. State tables & timing

| Table | Purpose | Size / TTL | Reap cadence |
| --- | --- | --- | --- |
| Dedup cache | drop already-seen `id` | 4096 entries, 5-min TTL, evict-oldest when full | 1 min |
| Neighbor table | directly-connected peers | 60 s timeout | 10 s |
| Topology | global graph from ANNOUNCEs | 90 s expiry | 15 s |
| Reassembler | in-flight fragments | 10 s timeout | 5 s |

- **ANNOUNCE interval: 15 s, TTL 3.**
- Topology `Update` ignores stale ANNOUNCEs (`seq <= existing seq`).
- **Neighbor learning from received traffic** (Go nodes + ESP32, **not** Android):
  on every received packet, the directly-connected peer = last `trace` hop (or
  `source` if trace empty) is upserted/touched as a neighbor. This lets an
  inbound-only node (e.g. the ESP32 relay) report neighbors in its ANNOUNCE.
  Android intentionally omits this to preserve its inbound peer-card display.

---

## 7. Conformance checklist

A new or modified implementation must:

- [ ] Generate an 8-byte random NodeID; advertise it under company ID `0xBEED`.
- [ ] Expose the GATT service + 3 characteristics with the exact UUIDs above.
- [ ] Encode/decode the 23-byte big-endian frame header; CRC-32/IEEE over the
      full packet; fragment at `MAX_FRAME_SIZE = 200` (177 data bytes/frame).
- [ ] CBOR-encode packets with **integer** keys 1–13 exactly as in section 4.
- [ ] Drop on version mismatch, duplicate `id`, loop (self in trace), TTL 0.
- [ ] Append self to `trace` and decrement TTL on every relay.
- [ ] Exclude the inbound peer when re-flooding; apply 10–100 ms jitter.
- [ ] Emit ANNOUNCE every 15 s with TTL 3 and the correct caps bitmask.
- [ ] Honor dedup (4096 / 5 min), neighbor (60 s), topology (90 s) lifetimes.

When changing the wire format, bump `ProtocolVersion`, update this doc in the
same change, and update `firmware/xiao_esp32c6/test/` cross-checks.
