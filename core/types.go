package core

import (
	"encoding/hex"
	"fmt"
	"strings"
)

// ProtocolVersion is bumped to 2 for Ed25519 identities + signed ANNOUNCE.
// v2 is intentionally incompatible with v1 nodes (the router drops version
// mismatches).
const ProtocolVersion = 2

type NodeID [8]byte

func (n NodeID) String() string { return hex.EncodeToString(n[:]) }

func ParseNodeID(s string) (NodeID, error) {
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 8 {
		return NodeID{}, fmt.Errorf("invalid node id: %s", s)
	}
	var id NodeID
	copy(id[:], b)
	return id, nil
}

func (n NodeID) Less(other NodeID) bool {
	for i := range n {
		if n[i] < other[i] {
			return true
		}
		if n[i] > other[i] {
			return false
		}
	}
	return false
}

type PacketType uint8

const (
	PacketTypeData     PacketType = 1
	PacketTypeAnnounce PacketType = 2
	PacketTypeAck      PacketType = 3
)

type RoutingMode uint8

const (
	RoutingModeFlood       RoutingMode = 1
	RoutingModeSourceRoute RoutingMode = 2
)

type PayloadType uint8

const (
	PayloadTypeTextTest      PayloadType = 1
	PayloadTypeMeshCoreRaw   PayloadType = 2
	PayloadTypeChatPlain     PayloadType = 3 // broadcast channel text (UTF-8)
	PayloadTypeChatEncrypted PayloadType = 4 // direct message: Crypto sealed envelope (CBOR)
)

type PHY uint8

const (
	PHYUnknown PHY = 0
	PHY1M      PHY = 1
	PHY2M      PHY = 2
	PHYCoded   PHY = 3
)

func (p PHY) String() string {
	switch p {
	case PHY1M:
		return "1M"
	case PHY2M:
		return "2M"
	case PHYCoded:
		return "LE Coded"
	default:
		return "unknown"
	}
}

type Capability uint8

const (
	CapSender   Capability = 0x01
	CapReceiver Capability = 0x02
	CapRelay    Capability = 0x04
	CapGateway  Capability = 0x08
	CapCodedPHY Capability = 0x10
)

type Capabilities uint8

func (c Capabilities) Has(cap Capability) bool { return uint8(c)&uint8(cap) != 0 }
func (c Capabilities) IsRelay() bool            { return c.Has(CapRelay) }
func (c Capabilities) IsGateway() bool          { return c.Has(CapGateway) }

// String renders the capability flags as a pipe-joined list (mirrors the Kotlin side).
func (c Capabilities) String() string {
	var flags []string
	if c.Has(CapSender) {
		flags = append(flags, "sender")
	}
	if c.Has(CapReceiver) {
		flags = append(flags, "receiver")
	}
	if c.Has(CapRelay) {
		flags = append(flags, "relay")
	}
	if c.Has(CapGateway) {
		flags = append(flags, "gateway")
	}
	if c.Has(CapCodedPHY) {
		flags = append(flags, "coded-phy")
	}
	if len(flags) == 0 {
		return "none"
	}
	return strings.Join(flags, "|")
}

const (
	AndroidCapabilities = Capability(CapSender | CapReceiver | CapRelay | CapCodedPHY)
	LinuxCapabilities   = Capability(CapReceiver | CapGateway | CapCodedPHY)
)

type PHYMode string

const (
	PHYModeCodedOnly      PHYMode = "coded-only"
	PHYModeCodedPreferred PHYMode = "coded-preferred"
	// PHYMode1M is the default. 1M is universally supported for both advertising
	// and scanning; Coded PHY (Long Range) is opt-in because many devices can
	// advertise on it but cannot scan it (despite reporting support).
	PHYMode1M PHYMode = "1m"
)
