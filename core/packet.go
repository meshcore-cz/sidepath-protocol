package core

import (
	"crypto/rand"
	"encoding/binary"

	"github.com/fxamacker/cbor/v2"
)

type PacketID [16]byte

func NewPacketID() PacketID {
	var id PacketID
	rand.Read(id[:]) //nolint:errcheck
	return id
}

func RandomUint32() uint32 {
	var b [4]byte
	rand.Read(b[:]) //nolint:errcheck
	return binary.LittleEndian.Uint32(b[:])
}

// Packet is the core mesh routing packet. CBOR keys are compact integers.
type Packet struct {
	Version     uint8       `cbor:"1,keyasint"`
	Type        PacketType  `cbor:"2,keyasint"`
	ID          PacketID    `cbor:"3,keyasint"`
	Source      NodeID      `cbor:"4,keyasint"`
	Destination NodeID      `cbor:"5,keyasint"` // zero = broadcast
	Mode        RoutingMode `cbor:"6,keyasint"`
	TTL         uint8       `cbor:"7,keyasint"`
	RouteCursor uint8       `cbor:"8,keyasint"`
	Route       []NodeID    `cbor:"9,keyasint"`
	Trace       []NodeID    `cbor:"10,keyasint"`
	PayloadType PayloadType `cbor:"11,keyasint"`
	Payload     []byte      `cbor:"12,keyasint"`
	Seq         uint32      `cbor:"13,keyasint,omitempty"` // for ANNOUNCE
	TraceMetric []int8      `cbor:"14,keyasint,omitempty"` // TRACE link samples; int8 is metric-specific
}

var zeroNodeID NodeID

func (p Packet) IsBroadcast() bool { return p.Destination == zeroNodeID }

func (p Packet) Encode() ([]byte, error) {
	return cbor.Marshal(p)
}

func DecodePacket(data []byte) (Packet, error) {
	var p Packet
	err := cbor.Unmarshal(data, &p)
	return p, err
}

// AnnouncePayload is the payload inside PacketTypeAnnounce. Keys 6 (PublicKey)
// and 7 (Signature) authenticate the announce: PublicKey is the originator's
// Ed25519 public key (NodeID == PublicKey[:8]) and Signature is an Ed25519
// signature over AnnounceSignedMessage(...). See identity.go.
type AnnouncePayload struct {
	NodeID    NodeID       `cbor:"1,keyasint"`
	Caps      Capabilities `cbor:"2,keyasint"`
	Neighbors []NodeID     `cbor:"3,keyasint"`
	Seq       uint32       `cbor:"4,keyasint"`
	Timestamp int64        `cbor:"5,keyasint"`
	PublicKey []byte       `cbor:"6,keyasint"`
	Signature []byte       `cbor:"7,keyasint"`
	// Description is a free-form, human-readable node bio (default empty). It is
	// diagnostic only and is NOT covered by Signature — never use it for any
	// routing or trust decision.
	Description string `cbor:"8,keyasint,omitempty"`
	// Name is the node's primary display label (default: deterministic from the
	// public key, see DefaultNodeName; user-overridable). Unsigned — informational.
	Name string `cbor:"9,keyasint,omitempty"`
	// Platform is the node's OS/device string (e.g. "linux/arm64", "esp32-c6").
	// Auto-set per platform, read-only. Unsigned — informational.
	Platform string `cbor:"10,keyasint,omitempty"`
}

func (a AnnouncePayload) Encode() ([]byte, error) { return cbor.Marshal(a) }

func DecodeAnnounce(data []byte) (AnnouncePayload, error) {
	var a AnnouncePayload
	return a, cbor.Unmarshal(data, &a)
}
