Here is `CHAT_PROTOCOL.md` as plain text:

# BLEEdge Chat Protocol Specification

**Chat protocol version: 1**
**BLEEdge payload protocol ID: `0x0100` (`BLEEDGE_CHAT`)**

> BLEEdge Chat is a small native messenger protocol carried inside BLEEdge
> datagrams. It is an application protocol, not part of the routing engine.
> Relays route its payload as opaque bytes.

This document specifies the first interoperable BLEEdge Chat payload format. It
covers public text messages, encrypted direct messages, ephemeral typing
notifications, and encrypted group channels.

Channel messages are native BLEEdge Chat messages. Their encrypted payload layout
intentionally mirrors MeshCore `GRP_TXT`, allowing implementations to reuse the
same channel crypto and allowing gateways to translate channel messages without
inventing a second group-message format. Tunneling a complete MeshCore packet
remains separately available through `MESHCORE_PACKET`, as defined in
`PROTOCOL.md`.

---

## 1. Layering

```text
BLE GATT frame
└── BLEEdge datagram
    └── BLEEDGE_CHAT payload
        ├── PUBLIC_TEXT
        ├── DIRECT_TEXT
        ├── TYPING
        └── CHANNEL_TEXT
```

A BLEEdge Chat message is carried inside a BLEEdge datagram with:

```text
protocol = BLEEDGE_CHAT   # 0x0100
payload  = encoded chat message
```

The outer BLEEdge datagram provides:

* sender and destination NodeIDs;
* routing and TTL;
* end-to-end datagram ID;
* optional BLEEdge delivery acknowledgement; and
* fragmentation through the hop-local GATT frame layer.

The chat protocol MUST NOT copy routing fields into its own payload.

---

## 2. Message envelope

A chat payload is encoded as a CBOR map with compact integer keys. Field order is
irrelevant. Keys are authoritative.

| Key | Field     | Type     | Required | Notes              |
| --: | --------- | -------- | -------- | ------------------ |
|   1 | `version` | uint8    | yes      | Must equal `1`     |
|   2 | `kind`    | uint8    | yes      | Chat message kind  |
|   3 | `body`    | CBOR map | yes      | Kind-specific body |

Message kinds:

| Value | Name           | Purpose                                                       |
| ----: | -------------- | ------------------------------------------------------------- |
|     1 | `PUBLIC_TEXT`  | Signed plaintext message broadcast within the BLEEdge scope   |
|     2 | `DIRECT_TEXT`  | Authenticated encrypted message sent to one BLEEdge node      |
|     3 | `TYPING`       | Signed ephemeral typing notification sent to one BLEEdge node |
|     4 | `CHANNEL_TEXT` | MeshCore-compatible encrypted group-channel message           |

Unknown message kinds MUST be ignored after local delivery. Relays MUST continue
to forward unknown chat payloads according to the outer BLEEdge routing rules.

---

## 3. Common rules

### 3.1 Sender identity

The sender identity is the Ed25519 public key associated with the outer BLEEdge
datagram `source` NodeID.

Whenever a chat body carries `sender_public_key`, the receiver MUST require:

```text
outer.source == sender_public_key[0:10]
```

A receiver MUST drop the locally delivered chat message when this binding fails.
Relays do not parse or validate chat bodies.

### 3.2 Display metadata

Chat payloads other than `CHANNEL_TEXT` MUST NOT contain sender display names,
descriptions, or platform strings.

A UI SHOULD obtain the sender name from the most recent verified BLEEdge
`ANNOUNCE`. If no verified announce is available, it SHOULD display the
sender's deterministic fallback name or NodeID.

`CHANNEL_TEXT` is the deliberate exception: its encrypted plaintext contains a
sender label because this mirrors the MeshCore `GRP_TXT` layout. That embedded
label is a channel-level display hint, not a cryptographic BLEEdge identity.

### 3.3 Timestamps

`sent_at` is an informational Unix timestamp in seconds. A sender without a
reliable clock MUST use `0`.

Timestamp values MUST NOT be used as the sole replay-protection mechanism. The
outer BLEEdge datagram `id` and deduplication cache provide short-term duplicate
suppression.

### 3.4 Text limits

Text fields contain UTF-8 strings.

```text
MAX_TEXT_BYTES = 2048
```

A receiver MUST drop a locally delivered message whose UTF-8 encoded text
exceeds this limit.

---

## 4. Signed public text messages

A `PUBLIC_TEXT` message is readable by every receiving node in the BLEEdge scope.
It is signed but not encrypted.

### 4.1 Outer BLEEdge datagram

A public text message SHOULD use:

```text
source      = sender NodeID
destination = broadcast
route       = omitted
protocol    = BLEEDGE_CHAT
flags       = 0
kind        = PUBLIC_TEXT
```

The sender chooses an appropriate outer BLEEdge TTL for the intended local
scope.

### 4.2 Body

| Key | Field               | Type      | Required | Notes                     |
| --: | ------------------- | --------- | -------- | ------------------------- |
|   1 | `sender_public_key` | bytes(32) | yes      | Sender Ed25519 public key |
|   2 | `sent_at`           | int64     | yes      | Unix seconds or `0`       |
|   3 | `text`              | text      | yes      | UTF-8 message text        |
|   4 | `signature`         | bytes(64) | yes      | Ed25519 signature         |

### 4.3 Signature layout

The sender signs the following exact byte sequence:

```text
ascii("BLEEDGE-CHAT-PUBLIC-TEXT-V1\0")
datagram_id            [16]
source_node_id         [10]
destination_node_id    [10]
sender_public_key      [32]
sent_at                [8]   int64 little-endian
text_length            [2]   uint16 little-endian
text_utf8              [text_length]
```

The signature is:

```text
signature = Ed25519.Sign(sender_private_key, signed_bytes)
```

### 4.4 Verification

Before displaying a locally delivered `PUBLIC_TEXT`, a receiver MUST:

1. require the outer destination to be broadcast;
2. require `sender_public_key` to be exactly 32 bytes;
3. require `outer.source == sender_public_key[0:10]`;
4. validate the text length;
5. reconstruct the exact signed byte sequence; and
6. verify the Ed25519 signature.

Any failure causes the locally delivered chat message to be dropped.

---

## 5. Encrypted direct text messages

A `DIRECT_TEXT` message is sent to exactly one BLEEdge node. The text is
encrypted and authenticated end to end between the sender and recipient.

### 5.1 Outer BLEEdge datagram

A direct text message SHOULD use:

```text
source      = sender NodeID
destination = recipient NodeID
protocol    = BLEEDGE_CHAT
flags       = ACK_REQUESTED
kind        = DIRECT_TEXT
```

Routing mode is selected according to `PROTOCOL.md`:

* use a source route when a route is known; or
* use flood routing when direct-message fallback flooding is enabled.

`ACK_REQUESTED` is recommended for direct text messages. The resulting BLEEdge
ACK confirms local BLEEdge delivery only. It is not a read receipt.

### 5.2 Encrypted body

| Key | Field               | Type      | Required | Notes                                                           |
| --: | ------------------- | --------- | -------- | --------------------------------------------------------------- |
|   1 | `sender_public_key` | bytes(32) | yes      | Sender Ed25519 public key                                       |
|   2 | `nonce`             | bytes(12) | yes      | AES-GCM nonce                                                   |
|   3 | `ciphertext`        | bytes     | yes      | AES-256-GCM ciphertext with 16-byte authentication tag appended |

### 5.3 Plaintext before encryption

The encrypted plaintext is a CBOR map:

| Key | Field     | Type  | Required | Notes               |
| --: | --------- | ----- | -------- | ------------------- |
|   1 | `sent_at` | int64 | yes      | Unix seconds or `0` |
|   2 | `text`    | text  | yes      | UTF-8 message text  |

### 5.4 Ed25519 to X25519 conversion

Each node uses its BLEEdge Ed25519 identity for chat key agreement by converting
it to X25519 using the libsodium-compatible conversion semantics:

```text
sender_x25519_private    = Ed25519PrivateKeyToCurve25519(sender_ed25519_private)
recipient_x25519_public  = Ed25519PublicKeyToCurve25519(recipient_ed25519_public)
shared_secret            = X25519(sender_x25519_private, recipient_x25519_public)
```

The recipient derives the same shared secret using its converted private key and
the sender's converted public key.

Implementations MUST reject invalid public-key conversions.

### 5.5 Pairwise encryption key

Let `pub_low` and `pub_high` be the two 32-byte Ed25519 public keys sorted
lexicographically.

Derive the AES key using HKDF-SHA256:

```text
ikm  = shared_secret
salt = empty
info = ascii("BLEEDGE-CHAT-DIRECT-V1\0") || pub_low || pub_high
key  = HKDF-SHA256(ikm, salt, info, 32)
```

### 5.6 Nonce generation

The sender MUST generate a fresh random 12-byte nonce for every direct text
message.

A sender MUST NOT reuse the same nonce with the same pairwise key.

### 5.7 Additional authenticated data

The AES-256-GCM additional authenticated data is the following exact byte
sequence:

```text
ascii("BLEEDGE-CHAT-DIRECT-AAD-V1\0")
datagram_id            [16]
source_node_id         [10]
destination_node_id    [10]
sender_public_key      [32]
protocol               [2]   uint16 little-endian, always 0x0100
chat_version           [1]   always 1
kind                   [1]   always DIRECT_TEXT (= 2)
```

Mutable routing fields such as TTL, route cursor, route, and path MUST NOT be
included because relays modify them in transit.

### 5.8 Encryption

```text
ciphertext = AES-256-GCM.Seal(
  key,
  nonce,
  plaintext_cbor,
  additional_authenticated_data
)
```

The 16-byte GCM authentication tag is appended to the ciphertext.

### 5.9 Decryption

Before displaying a locally delivered `DIRECT_TEXT`, the recipient MUST:

1. require the outer destination to equal the local NodeID;
2. require `sender_public_key` to be exactly 32 bytes;
3. require `outer.source == sender_public_key[0:10]`;
4. require `nonce` to be exactly 12 bytes;
5. convert the sender public key and recipient private key to X25519;
6. derive the pairwise key;
7. reconstruct the additional authenticated data;
8. decrypt and authenticate the ciphertext;
9. decode the plaintext CBOR map; and
10. validate the text length.

Any failure causes the locally delivered chat message to be dropped.

---

## 6. Signed typing notifications

A `TYPING` message is a small ephemeral hint that a peer is actively composing a
direct message.

Typing notifications are signed but not encrypted. The outer BLEEdge envelope
already exposes the communicating NodeIDs, so plaintext typing notifications do
not reveal additional routing relationships.

### 6.1 Outer BLEEdge datagram

A typing notification MUST use:

```text
source      = sender NodeID
destination = recipient NodeID
protocol    = BLEEDGE_CHAT
flags       = 0
kind        = TYPING
```

A typing notification MUST NOT request an ACK.

### 6.2 Body

| Key | Field               | Type      | Required | Notes                     |
| --: | ------------------- | --------- | -------- | ------------------------- |
|   1 | `sender_public_key` | bytes(32) | yes      | Sender Ed25519 public key |
|   2 | `sent_at`           | int64     | yes      | Unix seconds or `0`       |
|   3 | `signature`         | bytes(64) | yes      | Ed25519 signature         |

### 6.3 Signature layout

The sender signs the following exact byte sequence:

```text
ascii("BLEEDGE-CHAT-TYPING-V1\0")
datagram_id            [16]
source_node_id         [10]
destination_node_id    [10]
sender_public_key      [32]
sent_at                [8]   int64 little-endian
```

### 6.4 Verification and UI lifetime

Before displaying a typing hint, a receiver MUST validate the public-key binding
and Ed25519 signature.

A sender SHOULD re-emit the hint approximately every 10 seconds while the user
is actively typing. A receiver SHOULD hide the hint after approximately 13
seconds without a newer valid typing notification, or immediately after a real
message arrives from the same peer.

Typing notifications MUST NOT be persisted and SHOULD NOT be retransmitted by
the application after failure.

---

## 7. MeshCore-compatible channel text messages

A `CHANNEL_TEXT` message is an encrypted group-channel broadcast. A channel is
identified by a 16-byte pre-shared secret. Every node that knows the secret can
read and originate messages for that channel.

The channel payload format intentionally mirrors the MeshCore `GRP_TXT` payload.
This is a native BLEEdge Chat message type, not a complete encapsulated MeshCore
packet.

### 7.1 Outer BLEEdge datagram

A channel message MUST use:

```text
source      = sender NodeID
destination = broadcast
route       = omitted
protocol    = BLEEDGE_CHAT
flags       = 0
kind        = CHANNEL_TEXT
```

A channel message MUST NOT request an ACK. It is flood-routed according to the
outer BLEEdge TTL.

### 7.2 Body

| Key | Field             | Type  | Required | Notes                                       |
| --: | ----------------- | ----- | -------- | ------------------------------------------- |
|   1 | `channel_payload` | bytes | yes      | Exact MeshCore-compatible `GRP_TXT` payload |

### 7.3 Channel secret derivation

The wire-level channel secret is always exactly 16 bytes.

Supported channel forms:

| Channel form      | Secret derivation                               |
| ----------------- | ----------------------------------------------- |
| Public            | Fixed secret `8b3387e9c5cdea6ac9e5edbaa115cd72` |
| Named             | `SHA-256(utf8(channel_name))[0:16]`             |
| Secret key        | User-provided 16-byte secret                    |
| Secret passphrase | `SHA-256(utf8(passphrase))[0:16]`               |

The public channel secret produces channel hash `0x11`.

Channel names and passphrases are byte-sensitive. Implementations MUST NOT
silently lowercase, trim, normalize, or otherwise rewrite their UTF-8 input
before deriving a secret.

### 7.4 Compatible `GRP_TXT` payload layout

The body `channel_payload` MUST have this exact byte layout:

```text
channel_payload = channel_hash[1] || mac[2] || ciphertext[N]
channel_hash    = SHA-256(secret)[0]
mac_key         = secret[16] || zero[16]
mac             = HMAC-SHA256(mac_key, ciphertext)[0:2]
```

The encrypted plaintext is:

```text
plaintext = timestamp[4] || flags[1] || message_utf8 || zero_padding
```

where:

| Field          | Encoding                                                                                |
| -------------- | --------------------------------------------------------------------------------------- |
| `timestamp`    | uint32 little-endian Unix seconds; use `0` when no reliable clock exists                |
| `flags`        | upper 6 bits = text type; lower 2 bits = attempt count                                  |
| `message_utf8` | UTF-8 bytes of `sender_label + ": " + text`                                             |
| `zero_padding` | zero bytes until plaintext length is a multiple of 16; this is zero padding, not PKCS#7 |

For ordinary plaintext chat messages:

```text
text_type     = 0
attempt_count = 0
```

The ciphertext is:

```text
ciphertext = AES-128-ECB-Encrypt(secret, plaintext)
```

`ciphertext` MUST be non-empty and its length MUST be a multiple of 16 bytes.

### 7.5 Receiving a channel message

Before displaying a locally delivered `CHANNEL_TEXT`, a receiver MUST:

1. require the outer destination to be broadcast;
2. require `channel_payload` to contain at least one encrypted block;
3. require ciphertext length to be a multiple of 16 bytes;
4. read `channel_hash` and find locally joined channels with a matching hash;
5. for each candidate secret, reconstruct and verify the two-byte MAC;
6. decrypt the first candidate whose MAC verifies;
7. remove trailing zero padding;
8. parse `timestamp`, `flags`, and UTF-8 message text; and
9. enforce local text-size and packet-size limits.

The one-byte channel hash is only a lookup hint. Different channel secrets can
collide. A receiver resolves collisions by checking the MAC against every joined
candidate secret with the same hash.

### 7.6 Identity semantics

A valid channel MAC proves knowledge of the shared channel secret. It does not
prove which individual channel member originated a message.

The sender label embedded in `message_utf8` is therefore a claimed display label.
A channel member can impersonate another label. The outer BLEEdge `source`
NodeID MAY be displayed as transport metadata, but it MUST NOT be interpreted as
a cryptographic author signature for the channel message.

### 7.7 MeshCore gateway mapping

Because `channel_payload` matches the MeshCore `GRP_TXT` payload, a gateway can
translate between native BLEEdge Chat channels and MeshCore group messages:

```text
BLEEDGE_CHAT / CHANNEL_TEXT body.channel_payload
        ↕
MeshCore GRP_TXT payload
```

A gateway targeting a MeshCore radio MUST enforce the packet-size limits of its
target MeshCore implementation. BLEEdge fragmentation does not enlarge the
maximum packet size accepted by a MeshCore radio.

Complete opaque MeshCore packet tunneling remains separate:

```text
protocol = MESHCORE_PACKET
payload  = complete encoded MeshCore packet
```

---

## 8. Delivery semantics

| Message kind   | Outer destination | Encryption  | Authentication    | `ACK_REQUESTED` |
| -------------- | ----------------- | ----------- | ----------------- | --------------- |
| `PUBLIC_TEXT`  | broadcast         | no          | Ed25519 signature | no              |
| `DIRECT_TEXT`  | one NodeID        | AES-256-GCM | AEAD              | recommended     |
| `TYPING`       | one NodeID        | no          | Ed25519 signature | no              |
| `CHANNEL_TEXT` | broadcast         | AES-128-ECB | shared-secret MAC | no              |

A BLEEdge ACK confirms that the outer direct-message datagram reached the
recipient's BLEEdge node. It does not confirm:

* that the recipient read the message;
* that a UI displayed the message; or
* that an application-level action succeeded.

Read receipts are not part of chat protocol version 1.

---

## 9. Security considerations

### 9.1 Public messages

`PUBLIC_TEXT` is signed but readable by every node within the reachable BLEEdge
scope.

### 9.2 Direct messages

`DIRECT_TEXT` provides authenticated pairwise encryption using converted static
identity keys and AES-256-GCM.

Chat protocol version 1 does **not** provide forward secrecy. Compromise of a
node's long-term identity seed may permit decryption of previously captured
direct messages involving that node.

A later chat protocol version may introduce ephemeral keys or a ratcheting
session protocol without changing the BLEEdge routing engine.

### 9.3 Channel messages

`CHANNEL_TEXT` provides confidentiality and membership authentication using a
shared channel secret. It does not authenticate the individual human sender.
Every member who knows the secret can decrypt messages and generate valid new
messages.

AES-128-ECB is retained here only for MeshCore payload compatibility. It MUST NOT
be reused for `DIRECT_TEXT` or new non-compatible message formats.

### 9.4 Metadata exposure

BLEEdge routing metadata is visible to relays, including source NodeID,
destination NodeID, route, TTL, and path. Direct-message text remains encrypted,
but BLEEdge Chat v1 does not hide the communication graph.

### 9.5 Commands and automation

Remote device commands, bots, and automation messages are outside the scope of
BLEEdge Chat v1. They SHOULD use a separate application payload protocol rather
than overloading text-message semantics.

---

## 10. Constants

| Constant                 |                              Value |
| ------------------------ | ---------------------------------: |
| `CHAT_PROTOCOL_ID`       |                           `0x0100` |
| `CHAT_VERSION`           |                                `1` |
| `MAX_TEXT_BYTES`         |                             `2048` |
| `DIRECT_NONCE_BYTES`     |                               `12` |
| `DIRECT_KEY_BYTES`       |                               `32` |
| `DIRECT_GCM_TAG_BYTES`   |                               `16` |
| `CHANNEL_SECRET_BYTES`   |                               `16` |
| `CHANNEL_HASH_BYTES`     |                                `1` |
| `CHANNEL_MAC_BYTES`      |                                `2` |
| `CHANNEL_BLOCK_BYTES`    |                               `16` |
| `PUBLIC_CHANNEL_SECRET`  | `8b3387e9c5cdea6ac9e5edbaa115cd72` |
| `TYPING_REEMIT_INTERVAL` |               approximately `10 s` |
| `TYPING_EXPIRY`          |               approximately `13 s` |

---

## 11. Conformance checklist

A conforming BLEEdge Chat v1 implementation MUST:

* [ ] carry chat payloads using BLEEdge payload protocol ID `0x0100`;
* [ ] CBOR-encode the chat envelope using integer keys `1`–`3`;
* [ ] treat sender display metadata as signed `ANNOUNCE` state rather than chat payload data;
* [ ] sign and verify `PUBLIC_TEXT` using the exact fixed byte layout;
* [ ] convert Ed25519 identities to X25519 using libsodium-compatible semantics;
* [ ] derive pairwise direct-message keys using the specified HKDF-SHA256 layout;
* [ ] generate a fresh random 12-byte AES-GCM nonce for every direct message;
* [ ] bind direct-message ciphertext to immutable outer fields using the specified AAD;
* [ ] validate the sender-public-key-to-NodeID binding before displaying a message;
* [ ] recommend `ACK_REQUESTED` only for `DIRECT_TEXT`;
* [ ] sign and verify ephemeral `TYPING` hints and never request ACKs for them;
* [ ] implement `CHANNEL_TEXT` as a broadcast BLEEdge Chat subtype;
* [ ] encode `CHANNEL_TEXT.body.channel_payload` using the exact MeshCore-compatible `GRP_TXT` layout;
* [ ] derive channel secrets and validate channel MACs exactly as specified;
* [ ] treat channel sender labels as claims rather than individual cryptographic signatures;
* [ ] keep complete opaque MeshCore packet tunneling available separately through `MESHCORE_PACKET`; and
* [ ] enforce the 2048-byte UTF-8 text limit.
