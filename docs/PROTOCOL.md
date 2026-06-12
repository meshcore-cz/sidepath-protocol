# BLEEdge Protocol Specification

**Datagram protocol version: 3**
**GATT frame version: 2**

> BLEEdge is a protocol-agnostic Bluetooth Low Energy mesh transport. It routes
> opaque datagrams between nearby nodes. MeshCore packets and future
> application-defined protocols are carried as payloads and are not interpreted
> by the routing engine.

This document is the single source of truth for BLEEdge v3 on-the-wire behavior.
BLEEdge v3 is intentionally incompatible with earlier proof-of-concept packet
formats.

---

## 1. Design principles

BLEEdge has three layers:

```text
BLE GATT frame
└── BLEEdge datagram
    └── Payload protocol
        ├── BLEEdge control
        ├── MeshCore packet
        └── application-defined protocol
```

The layers have distinct responsibilities:

| Layer            | Responsibility                                                                |
| ---------------- | ----------------------------------------------------------------------------- |
| BLE GATT frame   | Hop-local fragmentation, reassembly, and CRC validation                       |
| BLEEdge datagram | End-to-end datagram identity, routing, TTL, deduplication, and path recording |
| Payload protocol | Application-specific or encapsulated protocol data                            |

The BLEEdge routing engine MUST treat application payloads as opaque bytes.
Unknown payload protocols MUST still be forwarded normally.

BLEEdge-native announces, acknowledgements, and diagnostics use the built-in
`BLEEDGE_CONTROL` protocol. They MUST NOT be encoded as MeshCore packets.

---

## 2. Terminology

| Term                 | Meaning                                                               |
| -------------------- | --------------------------------------------------------------------- |
| **Node**             | A BLEEdge participant with an Ed25519 identity                        |
| **NodeID**           | Compact 10-byte BLEEdge routing address derived from the public key   |
| **Peer link**        | One direct BLE connection between two BLEEdge nodes                   |
| **Frame**            | Hop-local GATT transport unit carrying one fragment                   |
| **Datagram**         | End-to-end BLEEdge routing unit reconstructed from one or more frames |
| **Payload protocol** | Protocol identified by the datagram `protocol` field                  |
| **Control message**  | BLEEdge-native message carried using `BLEEDGE_CONTROL`                |
| **Gateway**          | Node that injects or extracts packets for another protocol            |

---

## 3. Identity

A BLEEdge node identity is an Ed25519 keypair as specified by RFC 8032.

* A node stores a random 32-byte Ed25519 seed.
* The seed MUST be persisted and MUST NOT be shared.
* The canonical identity is the 32-byte Ed25519 public key.
* `NodeID = public_key[0:10]`.
* NodeID values are rendered as lowercase hexadecimal.
* NodeID ordering is a plain lexicographic byte comparison.
* The all-zero NodeID is reserved and means broadcast when used as a datagram
  destination.

The full public key is authoritative. NodeID is only a compact routing address.
If an implementation encounters two different public keys with the same NodeID,
it MUST report an identity collision and MUST NOT silently merge them.

### 3.1 Deterministic fallback name

A node MAY derive a deterministic human-readable fallback name from its public
key. This name is a UI fallback only. It MUST NOT be used for routing,
authorization, or trust decisions.

A user-configured name is distributed through the signed `ANNOUNCE` control
message. Until an `ANNOUNCE` has been verified, a UI SHOULD display either the
deterministic fallback name or the NodeID.

---

## 4. BLE layer

### 4.1 GATT service and characteristics

| Name         | UUID                                   | Properties                     |
| ------------ | -------------------------------------- | ------------------------------ |
| Service      | `9b7e6a10-7d91-4c19-a3b8-6e2a11f3a001` | Primary                        |
| `NODE_INFO`  | `9b7e6a10-7d91-4c19-a3b8-6e2a11f3a002` | Read                           |
| `PACKET_IN`  | `9b7e6a10-7d91-4c19-a3b8-6e2a11f3a003` | Write / Write Without Response |
| `PACKET_OUT` | `9b7e6a10-7d91-4c19-a3b8-6e2a11f3a004` | Notify                         |

A client writes frames to `PACKET_IN`. A server emits frames through
`PACKET_OUT`. Both directions use the same frame format.

### 4.2 `NODE_INFO`

`NODE_INFO` is bootstrap-only. It allows a newly connected peer to bind a direct
BLE connection to a public key before receiving a routed signed announce.

Binary layout:

```text
version(1) | public_key(32) | provisional_caps(2)
```

| Field              | Type                 | Meaning                        |
| ------------------ | -------------------- | ------------------------------ |
| `version`          | uint8                | Must equal `1`                 |
| `public_key`       | 32 bytes             | Ed25519 public key             |
| `provisional_caps` | uint16 little-endian | Optional early capability hint |

`NODE_INFO` is not signed. Therefore:

* `public_key` is used only to identify the directly connected peer.
* `provisional_caps` is advisory and MUST NOT be treated as authoritative.
* Name, description, platform, neighbor list, and authoritative capabilities are
  distributed only through signed `ANNOUNCE` messages.

### 4.3 Advertising and discovery

A node SHOULD advertise:

* the BLEEdge service UUID; and
* manufacturer-specific data with company ID `0xBEED` and payload equal to the
  raw 10-byte NodeID.

A scanner MUST accept a device that advertises either the manufacturer data or
the BLEEdge service UUID. Service-UUID-only discovery is required for platforms
that cannot advertise manufacturer-specific data.

A scanner SHOULD apply BLEEdge filtering in application code rather than relying
on a hardware scan filter, because hardware filtering can interfere with BLE
Coded-PHY discovery on some devices.

### 4.4 Connection rule

Whoever discovers a peer MAY connect.

Mutual duplicate connections are collapsed deterministically. For a pair of
NodeIDs, the node with the larger NodeID drops its outgoing link and keeps the
incoming link. Peer links are deduplicated by NodeID.

### 4.5 PHY modes

| Mode              | Meaning                                  |
| ----------------- | ---------------------------------------- |
| `1m`              | Use BLE 1M PHY only                      |
| `coded-preferred` | Prefer BLE Coded PHY and fall back to 1M |
| `coded-only`      | Use BLE Coded PHY only                   |

The default is `1m` for interoperability.

---

## 5. GATT frame format

A frame is a hop-local fragment of a serialized BLEEdge datagram.

The frame header is fixed-width and big-endian except where explicitly stated.

```text
offset  size  field
0       1     frame_version
1       16    transfer_id
17      1     fragment_index
18      1     fragment_count
19      4     payload_crc32
23      N     data
```

| Field            | Meaning                                                      |
| ---------------- | ------------------------------------------------------------ |
| `frame_version`  | Must equal `2`                                               |
| `transfer_id`    | Random hop-local identifier for this serialized transmission |
| `fragment_index` | Zero-based fragment index                                    |
| `fragment_count` | Total number of fragments                                    |
| `payload_crc32`  | CRC-32/IEEE of the complete serialized BLEEdge datagram      |
| `data`           | Fragment bytes                                               |

### 5.1 `transfer_id` and datagram `id`

`transfer_id` is hop-local. Datagram `id` is end-to-end.

They MUST NOT be treated as the same value.

* A relay keeps the datagram `id` unchanged.
* A relay MUST generate a new `transfer_id` when it serializes a datagram for an
  outgoing transmission.
* A sender MAY reuse one `transfer_id` when sending identical serialized bytes to
  multiple direct neighbors as part of the same transmission batch.
* Reassembly buffers MUST be keyed by `(peer_link, transfer_id)`.

This prevents fragments from distinct links or differently mutated relay copies
from colliding during flood propagation.

### 5.2 Fragmentation

All implementations MUST use:

```text
MAX_FRAME_SIZE = 200 bytes
HEADER_SIZE    = 23 bytes
MAX_FRAME_DATA = 177 bytes
```

A frame MUST fit within one GATT write. Nodes MAY negotiate a larger ATT MTU,
but MUST NOT exceed `MAX_FRAME_SIZE` for BLEEdge frames.

All frames belonging to one transmission share the same `transfer_id`,
`fragment_count`, and `payload_crc32`.

### 5.3 Reassembly

A receiver:

1. groups fragments by `(peer_link, transfer_id)`;
2. ignores duplicate `fragment_index` values;
3. concatenates fragments in index order after all fragments are present;
4. verifies CRC-32/IEEE over the complete serialized datagram; and
5. drops the transmission on CRC mismatch.

Incomplete reassembly buffers expire after 10 seconds without a new fragment.

---

## 6. BLEEdge datagram

A BLEEdge datagram is encoded as a CBOR map with compact integer keys. Field
order is irrelevant. Keys are authoritative.

| Key | Field          | Type               | Required | Notes                                             |
| --: | -------------- | ------------------ | -------- | ------------------------------------------------- |
|   1 | `version`      | uint8              | yes      | Must equal `3`                                    |
|   2 | `id`           | bytes(16)          | yes      | Random end-to-end datagram ID                     |
|   3 | `source`       | bytes(10)          | yes      | Originating NodeID                                |
|   4 | `destination`  | bytes(10)          | yes      | Zero NodeID means broadcast                       |
|   5 | `ttl`          | uint8              | yes      | Remaining BLEEdge hop budget                      |
|   6 | `route`        | array of bytes(10) | no       | Explicit source route, including destination      |
|   7 | `route_cursor` | uint8              | no       | Index of the next expected route hop; default `0` |
|   8 | `path`         | array of bytes(10) | no       | Nodes visited so far; default empty               |
|   9 | `protocol`     | uint16             | yes      | Payload protocol registry value                   |
|  10 | `flags`        | uint16             | no       | Routing flags; default `0`                        |
|  11 | `payload`      | bytes              | yes      | Opaque protocol-specific payload                  |

### 6.1 Routing mode

Routing mode is inferred:

* missing or empty `route` means TTL-limited flood routing;
* non-empty `route` means source routing.

A source route contains each receiving node in order, including the final
destination. For direct unicast delivery, use:

```text
route = [destination]
```

For source-routed datagrams:

* `route[0]` is the first receiving hop;
* `route[len(route)-1]` MUST equal `destination`;
* `route_cursor` identifies the node that is expected to receive the datagram
  next.

### 6.2 TTL policy

TTL remains mandatory for every BLEEdge datagram. Deduplication suppresses
ordinary duplicate deliveries, while TTL provides a hard propagation bound
across cache expiry, node restarts, malformed traffic, and accidentally bridged
BLEEdge scopes.

```text
MAX_TTL           = 16
DEFAULT_FLOOD_TTL = 5
MAX_ROUTE_HOPS    = 16
ANNOUNCE_TTL      = 5
```

TTL counts receiving hops:

* `ttl = 1` reaches one directly connected peer and is not relayed further;
* `ttl = 5` reaches nodes up to five BLEEdge hops away;
* `ttl = 0` is invalid on reception;
* an incoming datagram with `ttl > MAX_TTL` MUST be dropped; and
* a source-routed datagram MUST use `ttl = len(route)` when originated.

Applications MAY deliberately choose a smaller flood TTL for local-only traffic.
They SHOULD use source routing rather than continually increasing flood TTL for
larger known paths.

### 6.3 Datagram flags

|      Bit | Name            | Meaning                                                      |
| -------: | --------------- | ------------------------------------------------------------ |
| `0x0001` | `ACK_REQUESTED` | Destination should return a BLEEdge ACK after local delivery |

Rules:

* `ACK_REQUESTED` is valid only for unicast datagrams.
* A BLEEdge ACK MUST NOT request another BLEEdge ACK.
* Unknown flag bits MUST be ignored when forwarding.
* Application-specific behavior MUST NOT be added to the routing engine.
  Payload protocols decide whether a datagram needs `ACK_REQUESTED`.

### 6.4 Payload protocol registry

|             Value | Name              | Payload                                                  |
| ----------------: | ----------------- | -------------------------------------------------------- |
|          `0x0000` | `BLEEDGE_CONTROL` | BLEEdge-native control message                           |
|          `0x0001` | `MESHCORE_PACKET` | Complete encoded MeshCore packet                         |
|          `0x0100` | `BLEEDGE_CHAT`    | Native messenger payload specified in `CHAT_PROTOCOL.md` |
| `0x0002`–`0x00ff` | unassigned        | Reserved for future registered payload protocols         |
| `0x0101`–`0x7fff` | unassigned        | Reserved for future registered payload protocols         |
| `0x8000`–`0xffff` | experimental      | Private or unstable payload protocols                    |

Unknown protocol values MUST be forwarded as opaque payloads.

Encapsulated protocols MUST carry complete packets. BLEEdge MUST NOT copy
selected inner fields into the BLEEdge header or partially reimplement inner
packet semantics.

---

## 7. BLEEdge control protocol

A control message is carried in a datagram with:

```text
protocol = BLEEDGE_CONTROL
```

The datagram `payload` is a CBOR map:

| Key | Field  | Type     |
| --: | ------ | -------- |
|   1 | `kind` | uint8    |
|   2 | `body` | CBOR map |

Control kinds:

| Value | Name             | Purpose                                           |
| ----: | ---------------- | ------------------------------------------------- |
|     1 | `ANNOUNCE`       | Publish signed node metadata and direct neighbors |
|     2 | `ACK`            | Confirm local delivery of one datagram            |
|     3 | `TRACE_REQUEST`  | Request BLEEdge-native route diagnostics          |
|     4 | `TRACE_RESPONSE` | Return BLEEdge-native route diagnostics           |

Unknown control kinds MUST be ignored after local delivery. Relays MAY forward
unknown control kinds according to the outer BLEEdge routing rules.

---

## 8. Signed `ANNOUNCE`

An `ANNOUNCE` publishes a node's authoritative BLEEdge identity metadata,
capabilities, and directly connected neighbors.

Every field in the announce body except the signature itself is signed.

### 8.1 Outer datagram

An announce datagram MUST use:

```text
source      = announcing node NodeID
destination = broadcast
route       = omitted
ttl         = ANNOUNCE_TTL  # 5
protocol    = BLEEDGE_CONTROL
flags       = 0
kind        = ANNOUNCE
```

### 8.2 Announce body

| Key | Field              | Type               | Required | Notes                                                                            |
| --: | ------------------ | ------------------ | -------- | -------------------------------------------------------------------------------- |
|   1 | `announce_version` | uint8              | yes      | Must equal `1`                                                                   |
|   2 | `public_key`       | bytes(32)          | yes      | Ed25519 public key                                                               |
|   3 | `epoch`            | uint64             | yes      | Persisted generation counter incremented before the first announce after startup |
|   4 | `seq`              | uint32             | yes      | Monotonically increasing within one `epoch`                                      |
|   5 | `timestamp`        | int64              | yes      | Unix seconds; use `0` when no reliable clock exists                              |
|   6 | `caps`             | uint16             | yes      | Capability bitmask                                                               |
|   7 | `neighbors`        | array of bytes(10) | yes      | Directly connected NodeIDs, sorted and unique                                    |
|   8 | `name`             | text               | yes      | User-configured display name or empty string                                     |
|   9 | `description`      | text               | yes      | Free-form description or empty string                                            |
|  10 | `platform`         | text               | yes      | Platform string or empty string                                                  |
|  11 | `signature`        | bytes(64)          | yes      | Ed25519 signature                                                                |

Constraints:

| Field         | Maximum encoded content length |
| ------------- | -----------------------------: |
| `neighbors`   |                    255 entries |
| `name`        |                 64 UTF-8 bytes |
| `description` |                255 UTF-8 bytes |
| `platform`    |                 64 UTF-8 bytes |

`neighbors` MUST be sorted lexicographically and MUST NOT contain duplicates.

An empty `name` means that peers SHOULD display the deterministic fallback name
derived from `public_key`.

### 8.3 Announce signature message

The Ed25519 signature MUST cover a fixed explicit binary layout rather than the
CBOR encoding. This avoids cross-library differences in CBOR serialization.

The signed byte sequence is:

```text
ascii("BLEEDGE-ANNOUNCE-V1\0")
announce_version       [1]
public_key             [32]
epoch                  [8]   uint64 little-endian
seq                    [4]   uint32 little-endian
timestamp              [8]   int64 little-endian
caps                   [2]   uint16 little-endian
neighbor_count         [2]   uint16 little-endian
neighbors              [neighbor_count * 10]
name_length            [2]   uint16 little-endian
name_utf8              [name_length]
description_length     [2]   uint16 little-endian
description_utf8       [description_length]
platform_length        [2]   uint16 little-endian
platform_utf8          [platform_length]
```

The signature is:

```text
signature = Ed25519.Sign(private_key, signed_bytes)
```

### 8.4 Announce verification

Before accepting or relaying an announce, a verifying node MUST:

1. decode the control payload and announce body;
2. require `announce_version == 1`;
3. require `public_key` to be exactly 32 bytes;
4. derive `node_id = public_key[0:10]`;
5. require outer datagram `source == node_id`;
6. validate length limits;
7. require the neighbor list to be sorted and unique;
8. reconstruct the exact signed byte sequence;
9. verify the Ed25519 signature; and
10. reject the announce on any failure.

A valid announce MAY then update topology state and MAY be flood-relayed.

Nodes that cannot verify Ed25519 signatures MAY relay an opaque announce for
constrained-device interoperability, but MUST NOT update trusted topology or
display the announce as verified. Full implementations MUST verify announces
before using them.

### 8.5 Announce freshness

A node MUST persist an unsigned 64-bit `epoch` counter alongside its identity.
Before emitting the first announce after process or device startup, it MUST
increment and persist `epoch`. It then emits a new announce every 15 seconds with
an incremented in-memory `seq` value.

Topology state stores `(public_key, epoch, seq, received_at)` per NodeID.

An announce is newer only when:

* its `epoch` is greater than the stored `epoch`; or
* its `epoch` equals the stored `epoch` and its `seq` is greater than the stored
  `seq`.

An announce with a lower `epoch`, or with an equal `epoch` and non-increasing
`seq`, is stale and MUST NOT replace newer topology state. This prevents replay
of an older signed announce after a node restarts.

`received_at`, not the signed sender timestamp, controls local expiry. Signed
`timestamp` is informational because some devices do not have a reliable clock.

Topology announcements expire after 90 seconds without a newer accepted
announce.

### 8.6 Capabilities

|      Bit | Name        | Meaning                                         |
| -------: | ----------- | ----------------------------------------------- |
| `0x0001` | `SENDER`    | Can originate BLEEdge datagrams                 |
| `0x0002` | `RECEIVER`  | Can consume locally delivered datagrams         |
| `0x0004` | `RELAY`     | Can forward datagrams                           |
| `0x0008` | `GATEWAY`   | Can inject or extract external protocol packets |
| `0x0010` | `CODED_PHY` | Supports BLE Coded PHY mode                     |

Unknown capability bits MUST be preserved when storing or forwarding announces
and MUST be ignored by implementations that do not understand them.

---

## 9. ACK control message

An ACK confirms local delivery of a specific BLEEdge datagram. It does not
confirm delivery or interpretation by an encapsulated protocol beyond BLEEdge.

### 9.1 ACK body

| Key | Field      | Type      | Required |
| --: | ---------- | --------- | -------- |
|   1 | `acked_id` | bytes(16) | yes      |

### 9.2 ACK generation

When a node locally delivers a unicast datagram with `ACK_REQUESTED`, it creates
an ACK datagram:

```text
source      = local NodeID
destination = original.source
route       = reverse(original.path excluding local destination) + [original.source]
route_cursor = 0
ttl         = len(route)
protocol    = BLEEDGE_CONTROL
flags       = 0
kind        = ACK
```

The ACK uses source routing. For a directly delivered datagram, the ACK route is:

```text
[original.source]
```

An ACK MUST NOT itself request an ACK.

Applications that already provide their own acknowledgement semantics SHOULD
omit `ACK_REQUESTED`.

---

## 10. Routing engine

All routing decisions use only the BLEEdge datagram envelope and, where needed,
BLEEdge control messages.

### 10.1 Common checks

For every incoming datagram:

1. decode CBOR;
2. require `version == 3`;
3. validate required field lengths and types;
4. require `1 <= ttl <= MAX_TTL`;
5. require `len(path) <= MAX_ROUTE_HOPS`;
6. if `route` is present, require `1 <= len(route) <= MAX_ROUTE_HOPS`;
7. apply an optional direct-peer allowlist;
8. drop if `id` is already in the deduplication cache;
9. drop if local NodeID already appears in `path`; and
10. if the packet is an `ANNOUNCE`, perform announce verification before using or
    relaying it.

A node MUST mark every locally originated datagram ID as seen before sending it.

### 10.2 Flood routing

Flood routing applies when `route` is absent or empty.

After common checks:

1. append local NodeID to `path`;
2. deliver locally if `destination` is broadcast or equals local NodeID;
3. generate an ACK if local delivery is unicast and `ACK_REQUESTED` is set;
4. if relay is needed, decrement `ttl`;
5. relay only if the remaining TTL is greater than zero and the datagram is
   broadcast or not addressed to the local node;
6. exclude the incoming peer link when relaying; and
7. apply random flood jitter of 10–100 ms.

### 10.3 Source routing

Source routing applies when `route` is non-empty.

A receiver MUST:

1. require `route_cursor < len(route)`;
2. require `route[route_cursor] == local NodeID`;
3. require `route[len(route)-1] == destination`;
4. require `ttl == len(route) - route_cursor`;
5. append local NodeID to `path`;
6. decrement `ttl`;
7. increment `route_cursor`;
8. locally deliver when `route_cursor == len(route)` and generate an ACK when
   `ACK_REQUESTED` is set; otherwise
9. relay only to `route[route_cursor]`.

A source-routed datagram MUST NOT be flood-relayed if its next hop is unavailable.

### 10.4 Route selection

To send a unicast datagram to `destination`:

1. if destination is a direct neighbor, use `route = [destination]`;
2. otherwise run BFS over the learned topology graph;
3. if a path is known, use that complete route including destination; and
4. if no path is known, MAY fall back to flood routing for protocols that permit
   flooding.

Applications MAY require an explicit known route and disable fallback flooding.

---

## 11. Native trace diagnostics

Trace is a BLEEdge-native control operation. It is not encoded using MeshCore
trace layouts.

A trace request MUST be source-routed. It does not request a generic ACK.

### 11.1 Trace metric identifiers

| Value | Name       | Encoding                   |
| ----: | ---------- | -------------------------- |
|     0 | `UNKNOWN`  | sample value is `-32768`   |
|     1 | `RSSI_DBM` | signed integer dBm         |
|     2 | `SNR_Q4`   | signed SNR multiplied by 4 |

### 11.2 `TRACE_REQUEST` body

| Key | Field             | Type           | Required |
| --: | ----------------- | -------------- | -------- |
|   1 | `tag`             | uint32         | yes      |
|   2 | `metric`          | uint8          | yes      |
|   3 | `forward_samples` | array of int16 | yes      |

Each receiving hop appends one inbound-link sample before forwarding or
responding. If the requested metric is unavailable, append `-32768`.

### 11.3 `TRACE_RESPONSE` body

| Key | Field             | Type               | Required |
| --: | ----------------- | ------------------ | -------- |
|   1 | `tag`             | uint32             | yes      |
|   2 | `metric`          | uint8              | yes      |
|   3 | `forward_path`    | array of bytes(10) | yes      |
|   4 | `forward_samples` | array of int16     | yes      |
|   5 | `return_samples`  | array of int16     | yes      |

At the trace destination:

* `forward_path` is copied from the completed request `path`;
* `forward_samples` is copied from the request;
* `return_samples` starts empty; and
* the response route is the reverse observed path excluding the destination,
  followed by the original source.

Each receiving hop appends one inbound-link sample to `return_samples`.

---

## 12. Topology and state tables

| Table          | Purpose                        | Limit / expiry                                     | Reap cadence |
| -------------- | ------------------------------ | -------------------------------------------------- | ------------ |
| Dedup cache    | Drop already-seen datagram IDs | 4096 entries, 5-minute TTL, evict oldest when full | 1 minute     |
| Neighbor table | Directly connected peers       | 60-second timeout                                  | 10 seconds   |
| Topology table | Signed announce graph          | 90-second expiry                                   | 15 seconds   |
| Reassembler    | In-flight frame groups         | 10-second timeout                                  | 5 seconds    |

A node SHOULD learn direct neighbors from established BLE peer links. It SHOULD
advertise only directly connected BLEEdge neighbors in `ANNOUNCE`.

A node MUST NOT treat arbitrary entries from a received datagram `path` as its
own direct neighbors.

---

## 13. MeshCore compatibility

### 13.1 Complete MeshCore packet encapsulation

BLEEdge can carry a complete encoded MeshCore packet without interpreting its
contents.

```text
protocol = MESHCORE_PACKET
payload  = complete encoded MeshCore packet
```

MeshCore adverts, complete channel packets, traces, acknowledgements,
encryption, and routing remain MeshCore concerns. BLEEdge MUST NOT extract
selected fields from a tunneled MeshCore packet into its own datagram envelope
or partially reimplement the semantics of that tunneled packet.

### 13.2 Native BLEEdge Chat channels

BLEEdge Chat also defines a native `CHANNEL_TEXT` subtype. Its encrypted
`channel_payload` intentionally mirrors the MeshCore `GRP_TXT` payload layout so
BLEEdge applications can implement compatible channels directly and gateways
can translate channel messages without inventing a second format.

```text
protocol = BLEEDGE_CHAT
kind     = CHANNEL_TEXT
body.channel_payload = MeshCore-compatible GRP_TXT payload
```

This is distinct from complete MeshCore packet encapsulation. Native
`CHANNEL_TEXT` messages are valid BLEEdge Chat messages and MUST be encoded as
described in `CHAT_PROTOCOL.md`.

The routing engine treats both forms as opaque bytes. Compatibility logic belongs
to payload adapters, not to the BLEEdge routing core.

Future payload protocols MAY be assigned additional registry values without
changing the BLEEdge routing engine. Their packet formats are outside the scope
of this specification.

---

## 14. Security considerations

### 14.1 What BLEEdge authenticates

BLEEdge authenticates signed announces. A verified announce binds:

* public key;
* NodeID;
* persisted announce epoch;
* sequence number;
* timestamp;
* capabilities;
* direct-neighbor list;
* name;
* description; and
* platform string.

Relays cannot alter these values without invalidating the signature.

### 14.2 What BLEEdge does not authenticate

The outer routing envelope is mutable during relay. TTL, route cursor, and path
change hop by hop and are not end-to-end signed by BLEEdge.

Application payload confidentiality, integrity, and authentication belong to the
payload protocol. Encapsulated protocols retain their own security model.

### 14.3 Metadata privacy

Signed announcements are public within the reachable BLEEdge scope. A user MAY
leave `name`, `description`, and `platform` empty to reduce exposed metadata.

---

## 15. Constants

| Constant             |       Value |
| -------------------- | ----------: |
| `FRAME_VERSION`      |         `2` |
| `DATAGRAM_VERSION`   |         `3` |
| `NODE_INFO_VERSION`  |         `1` |
| `ANNOUNCE_VERSION`   |         `1` |
| `NODE_ID_BYTES`      |  `10` bytes |
| `MAX_FRAME_SIZE`     | `200` bytes |
| `MAX_FRAME_DATA`     | `177` bytes |
| `MAX_TTL`            |   `16` hops |
| `DEFAULT_FLOOD_TTL`  |    `5` hops |
| `MAX_ROUTE_HOPS`     |   `16` hops |
| `ANNOUNCE_INTERVAL`  |      `15 s` |
| `ANNOUNCE_TTL`       |    `5` hops |
| `FLOOD_JITTER`       | `10–100 ms` |
| `REASSEMBLY_TIMEOUT` |      `10 s` |
| `DEDUP_LIMIT`        |  `4096` IDs |
| `DEDUP_TTL`          |     `5 min` |
| `NEIGHBOR_TIMEOUT`   |      `60 s` |
| `TOPOLOGY_TIMEOUT`   |      `90 s` |

---

## 16. Conformance checklist

A conforming implementation MUST:

* [ ] derive an Ed25519 identity from a persisted 32-byte seed;
* [ ] derive the 10-byte NodeID as `public_key[0:10]`;
* [ ] advertise the service UUID and, where supported, `0xBEED` manufacturer data;
* [ ] expose `NODE_INFO`, `PACKET_IN`, and `PACKET_OUT` with the specified UUIDs;
* [ ] encode `NODE_INFO` as `version | public_key | provisional_caps`;
* [ ] encode frame version `2` with a hop-local `transfer_id`;
* [ ] key reassembly by `(peer_link, transfer_id)`;
* [ ] fragment at `MAX_FRAME_SIZE = 200`;
* [ ] encode datagram version `3` using CBOR integer keys 1–11;
* [ ] infer flood versus source routing from the presence of `route`;
* [ ] route unknown protocols as opaque payloads;
* [ ] use `BLEEDGE_CONTROL` for native announces, ACKs, and traces;
* [ ] sign every announce field except the signature itself;
* [ ] verify the exact announce signature layout before trusting topology data;
* [ ] persist and increment `epoch` on startup and use `epoch` plus `seq` for announce freshness;
* [ ] generate ACKs only when `ACK_REQUESTED` is set;
* [ ] mark locally originated datagram IDs as seen before sending;
* [ ] enforce `MAX_TTL`, `DEFAULT_FLOOD_TTL`, `MAX_ROUTE_HOPS`, and `ANNOUNCE_TTL`;
* [ ] apply deduplication, loop detection, TTL checks, split-horizon flood relay,
  and flood jitter; and
* [ ] keep application-specific packet semantics out of the routing engine.

---

## 17. Migration from BLEEdge v2 proof-of-concept

BLEEdge v3 intentionally removes the v2 packet taxonomy:

* remove outer `type`;
* remove outer `payload_type`;
* remove outer `mode`;
* remove outer `seq`;
* remove outer `trace_metric`;
* rename outer `trace` to `path`;
* infer routing mode from `route`;
* replace packet-specific ACK exceptions with `ACK_REQUESTED`;
* move announce, ACK, and trace under `BLEEDGE_CONTROL`;
* move MeshCore encapsulation and application-specific payload handling into
  payload protocol adapters;
* replace frame `packet_id` with hop-local `transfer_id`; and
* remove mutable metadata strings from unsigned `NODE_INFO`;
* increase NodeID width from 8 bytes to 10 bytes; and
* replace replay-prone random `boot_id` freshness with persisted monotonic `epoch + seq`.

Implementations SHOULD reject datagram versions other than `3` and frame
versions other than `2` rather than attempting implicit compatibility.
