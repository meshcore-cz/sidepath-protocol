package core

import (
	"crypto/rand"

	"github.com/fxamacker/cbor/v2"
)

type PacketID [16]byte

func NewPacketID() PacketID {
	var id PacketID
	rand.Read(id[:]) //nolint:errcheck
	return id
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
}

func (a AnnouncePayload) Encode() ([]byte, error) { return cbor.Marshal(a) }

func DecodeAnnounce(data []byte) (AnnouncePayload, error) {
	var a AnnouncePayload
	return a, cbor.Unmarshal(data, &a)
}
