# BLEEdge Protocol Specification

**Protocol version: 2**

> v2 (current) adds Ed25519 node identities and signed ANNOUNCE. It is
> intentionally incompatible with v1 (the router drops version mismatches).

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

A node identity is an **Ed25519 keypair** (RFC 8032), MeshCore-compatible:

- A node stores a random **32-byte seed**; the keypair is derived from it
  (`ed25519` public key = 32 bytes). The same seed yields the same keypair under
  any RFC 8032 implementation — Go stdlib, BouncyCastle (Android), orlp/ed25519
  (ESP32) — so identities and signatures interoperate. The seed is persisted
  (`~/.bleedge/seed` on Go nodes; NVS on the ESP32) and must never be shared.
- The **32-byte public key** is the canonical identity. It is carried in
  `NODE_INFO` and in every ANNOUNCE, and is what signatures verify against.
- **NodeID** (the 8-byte routing address used in packet headers) = the **first 8
  bytes of the public key**. This keeps packet headers and advertising compact
  (MeshCore likewise routes on a pubkey prefix). Rendered as lowercase hex.
- NodeID ordering (`Less`) is a plain big-endian lexicographic byte compare.
- The all-zero NodeID is reserved and means **broadcast** as a packet destination.

> Reference: `core.Identity`, `core.NodeIDFromPubKey`, `core.LoadOrCreateIdentity`.

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

**`NODE_INFO`** identifies the peer without parsing an ANNOUNCE. The reader
derives `NodeID = pubkey[:8]`. It is `34 + 1 + descLen` bytes; `desc` is a UTF-8
diagnostic label (≤255 bytes, defaults to the peer's platform/OS):

```
version(1) | pubkey(32) | caps(1) | descLen(1) | desc(descLen)
```

### 2.2 Advertising & discovery

- NodeID is advertised in **manufacturer-specific data** with company ID
  `0xBEED`, payload = the raw 8-byte NodeID (= pubkey prefix), in the **primary**
  advertising PDU. Peers learn each other's NodeID from the scan result without
  connecting; the full 32-byte public key is learned later via `NODE_INFO` or
  ANNOUNCE. (The full key does not fit a legacy advert alongside the service
  UUID, which is why only the 8-byte prefix is advertised.)
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
| 11 | `payload_type` | uint8 | 1=TEXT_TEST, 2=MESHCORE_RAW, 3=CHAT_PLAIN, 4=CHAT_ENCRYPTED, 5=CHANNEL, 6=TRACE_REQ, 7=TRACE_RESP |
| 12 | `payload` | bytes | opaque |
| 13 | `seq` | uint32 | ANNOUNCE sequence; omitted when 0 |
| 14 | `trace_metric` | array of int8 | optional TRACE link samples; metric meaning is payload-specific |

> Note: the GATT frame `packet_id` (16 bytes) is the same value as packet key 3.

### 4.1 ANNOUNCE payload

When `type == 2`, `payload` (key 12) is itself a CBOR map:

| Key | Field | Type |
| --- | --- | --- |
| 1 | `node_id` | 8 bytes (= `public_key[:8]`) |
| 2 | `caps` | uint8 (capability bitmask) |
| 3 | `neighbors` | array of 8-byte NodeID |
| 4 | `seq` | uint32 |
| 5 | `timestamp` | int64 (unix seconds) |
| 6 | `public_key` | 32 bytes (Ed25519) |
| 7 | `signature` | 64 bytes (Ed25519) |
| 8 | `description` | text string (diagnostic; **not** signed) |

`description` is a free-form, human-readable label that defaults to the node's
platform/OS (`linux/arm64`, `darwin/arm64`, `Android 14 (Pixel 7)`, `esp32-c6`).
It is **not** covered by the signature and must never be used for routing or
trust decisions — it is informational only. Encoded as a CBOR **text** string
(major type 3), so it decodes to a native string on every platform.

**Signed message.** `signature` is an Ed25519 signature, by the node's identity
key, over a **fixed explicit byte layout** (not the CBOR — CBOR is not byte-stable
across libraries). Every implementation builds it identically
(`core.AnnounceSignedMessage` / `mesh::announceSignedMessage`):

```
public_key      [32]
timestamp       [4]  uint32 little-endian (unix seconds, low 32 bits)
caps            [1]
seq             [4]  uint32 little-endian
neighbor_count  [1]
neighbors       [neighbor_count * 8]   each NodeID, in ANNOUNCE order
```

A receiver **must**, before trusting an ANNOUNCE: check `public_key` is 32 bytes,
check `node_id == public_key[:8]` and `packet.source == public_key[:8]`, then
verify the signature. Any failure → drop as `bad-signature` and **do not relay**.
(The ESP32 relay forwards opaque flood packets and on-track TRACE source-route
packets without verifying; verifying endpoints reject forgeries.)

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

### 4.3 Chat payloads (Android chat-app)

The Android chat-app rides on top of the transport using two `payload_type`s; the
mesh treats both as opaque `payload` and routes them like any other DATA packet
(Go/firmware nodes still relay them without interpreting).

- **`CHAT_PLAIN` (3)** — broadcast "channel" message. `payload` is UTF-8 text,
  `destination = 0` (broadcast), FLOOD. Not encrypted.
- **`CHAT_ENCRYPTED` (4)** — direct message, end-to-end encrypted to the
  recipient's identity key. `payload` is a CBOR map:

  | Key | Field | Type |
  | --- | --- | --- |
  | 1 | `sender_pub` | 32 bytes (sender's Ed25519 public key) |
  | 2 | `iv` | 12 bytes (AES-GCM nonce) |
  | 3 | `ciphertext` | bytes (AES-256-GCM, 16-byte tag appended) |

  Key agreement: both identity keys are mapped from Ed25519 to X25519 (the
  libsodium `crypto_sign_ed25519_*_to_curve25519` transform), then a
  static↔static X25519 ECDH shared secret is run through HKDF-SHA256
  (`info = "bleedge-chat-v1"`) to a 32-byte AES key. Because the secret is
  symmetric, the recipient re-derives the key from `sender_pub` (carried in the
  envelope) and its own private key. The sender's public key is learned from the
  recipient's signed ANNOUNCE (key 6).

  ESP32 relay nodes also consume encrypted direct messages addressed to their own
  NodeID as remote-control commands. The command sender is authenticated by the
  envelope's `sender_pub`: it must match a build-time or NVS-stored admin public
  key, otherwise the node replies with `not authenticated`. Commands are firmware
  local behavior (`help`, `sensors`, `stats`, `admins`, `admin.add`, `admin.remove`)
  and are not interpreted by the routing layer.

### 4.4 Channels — MeshCore-compatible (`CHANNEL`, payload_type 5)

Group channels mirror MeshCore (`meshcore-cz/meshpkt`) so they can interoperate. A
channel is identified by a 16-byte pre-shared key (PSK). The BLEEdge `payload` (key 12)
**is** the MeshCore GRP_TXT payload, broadcast (`destination = 0`, FLOOD):

```
payload     = channelHash[1] | mac[2] | ciphertext[...]
plaintext   = timestamp[4 LE] | flags[1] | "SenderName: MessageText"   (zero-padded to 16)
ciphertext  = AES-128-ECB(secret, plaintext)
mac         = HMAC-SHA256(key = secret16 ‖ zero16, ciphertext)[:2]
channelHash = SHA-256(secret)[0]
```

`flags`: upper 6 bits = text type (0 = plain), lower 2 = attempt count.

PSK derivation by channel kind:
- **Public** — fixed PSK `8b3387e9c5cdea6ac9e5edbaa115cd72` (channelHash `0x11`).
- **Named ("public hash")** — `secret = SHA-256(name)[:16]`.
- **Secret** — a user-supplied 16-byte key (32 hex chars used raw; otherwise `SHA-256(passphrase)[:16]`).

A receiver matches an inbound packet to a joined channel by `channelHash`, then confirms
with the MAC (the hash is 1 byte, so collisions are resolved by which PSK's MAC verifies).
Reference impls: Kotlin `core.ChannelCrypto` and Go `core.SealChannel`/`OpenChannel` (byte-identical). Broadcast → no per-message ACK.

### 4.5 Trace (`TRACE_REQ` payload_type 6, `TRACE_RESP` payload_type 7)

Trace is a diagnostic round trip similar to `ping`/`traceroute`: the originator
chooses a destination and optionally a specific source route. The request follows
that path, each receiver records a link metric, and the destination sends a
`TRACE_RESP` back along the reversed observed path.

`TRACE_REQ` intentionally starts with the MeshCore TRACE payload layout so the
diagnostic body can be made radio-compatible later:

```
payload = tag[4 LE] | auth_code[4 LE] | flags[1] | route_hashes[...]
```

- `tag` is a random request identifier echoed by the response.
- `auth_code` is opaque and forwarded/echoed; current BLEEdge does not validate
  it cryptographically.
- `flags & 0x03` selects each `route_hash` width: `0=1 byte`, `1=2 bytes`,
  `2=4 bytes`, `3=8 bytes`.
- BLEEdge uses 8-byte NodeID route hashes (`flags & 0x03 == 3`) for exact routes.

MeshCore stores TRACE SNR samples in its outer path accumulator. BLEEdge keeps
normal route loop detection in `trace` (key 10), so TRACE link samples live in
packet key 14, `trace_metric`. The values are signed 8-bit samples whose unit is
declared by the result payload. On macOS/CoreBluetooth the metric is `rssi` in
dBm because CoreBluetooth does not expose LoRa SNR; future LoRa/Coded-PHY radio
implementations may use `snr` encoded as MeshCore-style `int8 = SNR * 4`.

`TRACE_RESP` payload is a CBOR map with compact integer keys:

| Key | Field | Type | Notes |
| --- | --- | --- | --- |
| 1 | `tag` | uint32 | copied from request |
| 2 | `auth_code` | uint32 | copied from request |
| 3 | `route` | array of 8-byte NodeID | requested source route |
| 4 | `forward_nodes` | array of 8-byte NodeID | nodes that handled request |
| 5 | `forward_samples` | array of int8 | request-leg `trace_metric` samples |
| 6 | `return_nodes` | array of 8-byte NodeID | nodes that handled response; filled by final receiver |
| 7 | `return_samples` | array of int8 | response-leg `trace_metric` samples; filled by final receiver |
| 8 | `metric` | text | currently `rssi`; reserved value `snr` for SNR-capable radios |

TRACE requests do not generate generic ACK packets. The trace response is the
delivery confirmation. If no explicit route is supplied, a node first tries
`SelectRoute(dst)` (section 5.5). If no route is known, the trace is not sent.
TRACE packets must not be flood-relayed: only nodes on the selected source-route
track may repeat the request or response.

---

## 5. Routing engine

All nodes run the same decision logic (`core.Router.HandlePacket`). It is pure:
given an incoming packet and the peer it arrived from, it returns a list of
**actions** for the transport to execute (`deliver-local`, `relay-flood`,
`relay-next-hop`, `send-ack`, `drop`).

### 5.1 Common checks (every packet)

1. **Version**: `version != 2` → drop (`invalid-version`).
2. **Allowlist** (optional): if a non-empty allowlist is configured and the
   incoming peer is not in it → drop (`peer-not-allowed`).
3. ANNOUNCE (`type==2`): **verify the signature and NodeID/pubkey binding**
   (section 4.1) → drop `bad-signature` on failure; otherwise update local
   [topology](#54-topology) then flood.

### 5.2 FLOOD mode (`mode == 1`)

1. **Dedup**: if `id` already in the seen-cache → drop (`duplicate`).
2. **Loop**: if `LocalID` already appears in `trace` → drop (`loop`).
3. **TTL**: if `ttl == 0` → drop (`ttl-exhausted`).
4. Append `LocalID` to `trace`.
5. **Deliver locally** if broadcast or `destination == LocalID`. For a *unicast*
   DATA packet addressed to us, also emit an ACK (section 5.4), except
   `TRACE_REQ`, which returns a `TRACE_RESP` instead.
6. **Relay** if `ttl > 1` and (broadcast or not addressed to us): decrement TTL
   and re-flood. The incoming peer is excluded from the relay set (split-horizon).
   TRACE packets are never re-flooded.

Apply **flood jitter** of a random 10–100 ms before relaying to reduce collisions.

### 5.3 SOURCE_ROUTE mode (`mode == 2`)

1. Dedup, loop, TTL checks as above.
2. If `route[route_cursor] != LocalID` → drop (`not-next-hop`).
3. Append self to `trace`, increment `route_cursor`, decrement `ttl`.
4. If `route_cursor >= len(route)` we are the destination → deliver locally
   (+ ACK for DATA except `TRACE_REQ`). Otherwise relay to `route[route_cursor]`
   (`relay-next-hop`).

### 5.4 ACK (`type == 3`)

ACKs are generated automatically for **unicast DATA** that is delivered locally,
except `TRACE_REQ` packets:

- `destination = original.source`, `ttl = len(trace)+1`.
- If the inbound `trace` had >1 hop, the ACK is sent **SOURCE_ROUTE** along the
  reversed trace (excluding self) **with the original `source` appended as the final
  hop** — source-route delivery happens by route exhaustion and the originator is not
  in the trace, so without the appended source the ACK would stop at the last relay.
  If the `trace` had ≤1 hop, the ACK is **FLOOD**.
- The originator records the ACK's own `id` in its dedup cache so a flood echo of
  its own ACK is dropped rather than re-flooded. The same applies to *any* packet
  a node originates (`MarkOriginated`).
- The ACK `payload` (key 12) carries the 16-byte `id` of the DATA packet being
  acked, so an originator can match a delivery confirmation to a specific outgoing
  message. (Additive; relays/nodes that don't track per-message delivery ignore it.
  Implemented in the Android router; Go/firmware leave the ACK payload empty.)

### 5.5 Route selection

`SelectRoute(dst)`:
- If `dst` is a direct neighbor → send direct (no source route).
- Else BFS over learned topology for a path; if found → SOURCE_ROUTE.
- Else → fall back to FLOOD.

The macOS app exposes this as `/route <nodeid>` and uses the same selection for
`/trace <nodeid>` when no explicit `via` route is supplied. Trace requires a
known route; it does not use the generic flood fallback.

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

- [ ] Derive an Ed25519 identity from a persisted 32-byte seed; `NodeID =
      pubkey[:8]`; advertise the 8-byte NodeID under company ID `0xBEED`.
- [ ] Expose the GATT service + 3 characteristics with the exact UUIDs above;
      serve `NODE_INFO` as `version|pubkey(32)|caps|descLen(1)|desc`.
- [ ] Populate `description` (ANNOUNCE key 8, text; NODE_INFO tail) with the
      platform/OS by default; never sign it or trust it for routing.
- [ ] Encode/decode the 23-byte big-endian frame header; CRC-32/IEEE over the
      full packet; fragment at `MAX_FRAME_SIZE = 200` (177 data bytes/frame).
- [ ] CBOR-encode packets with **integer** keys 1–14 exactly as in section 4;
      ANNOUNCE payload keys 1–7 including `public_key`(6) + `signature`(7).
- [ ] Sign ANNOUNCE over the exact byte layout in section 4.1; on receive, verify
      the signature + `node_id==pubkey[:8]` and drop `bad-signature` on failure.
- [ ] Drop on version mismatch (`!= 2`), duplicate `id`, loop (self in trace), TTL 0.
- [ ] Append self to `trace` and decrement TTL on every relay.
- [ ] For TRACE, append the current link metric to packet key 14, return
      `TRACE_RESP` instead of a generic ACK, and preserve the MeshCore-shaped
      `TRACE_REQ` prefix. Do not flood-relay TRACE; only source-route hops on
      the selected track may repeat it.
- [ ] Exclude the inbound peer when re-flooding; apply 10–100 ms jitter.
- [ ] Emit ANNOUNCE every 15 s with TTL 3 and the correct caps bitmask.
- [ ] Honor dedup (4096 / 5 min), neighbor (60 s), topology (90 s) lifetimes.
- [ ] Reproduce the cross-platform Ed25519 test vector (seed `0001…1f`):
      pubkey `03a107bf…125531b8`, and a signature that Go/firmware both verify.

When changing the wire format, bump `ProtocolVersion`, update this doc in the
same change, and update `firmware/xiao_esp32c6/test/` cross-checks.
