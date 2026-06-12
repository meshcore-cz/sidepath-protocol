package core

import (
	"encoding/hex"
	"fmt"
	"strings"
)

const (
	FrameVersion     = 2
	DatagramVersion  = 3
	NodeInfoVersion  = 1
	AnnounceVersion  = 1
	NodeIDBytes      = 10
	DatagramIDBytes  = 16
	TransferIDBytes  = 16
	PublicKeyBytes   = 32
	SignatureBytes   = 64
	SeedSize         = 32
	MaxTTL           = 16
	DefaultFloodTTL  = 5
	MaxRouteHops     = 16
	AnnounceTTL      = 5
	MaxNeighbors     = 255
	MaxNameBytes     = 64
	MaxDescBytes     = 255
	MaxPlatformBytes = 64
)

type NodeID [NodeIDBytes]byte

var BroadcastNodeID NodeID

func (n NodeID) String() string { return hex.EncodeToString(n[:]) }
func (n NodeID) IsBroadcast() bool {
	return n == BroadcastNodeID
}

func ParseNodeID(s string) (NodeID, error) {
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != NodeIDBytes {
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

func CompareNodeID(a, b NodeID) int {
	for i := range a {
		if a[i] != b[i] {
			return int(a[i]) - int(b[i])
		}
	}
	return 0
}

type DatagramID [DatagramIDBytes]byte
type TransferID [TransferIDBytes]byte

type PayloadProtocol uint16

const (
	ProtocolBLEEdgeControl PayloadProtocol = 0x0000
	ProtocolMeshCorePacket PayloadProtocol = 0x0001
	ProtocolBLEEdgeChat    PayloadProtocol = 0x0100
)

type DatagramFlag uint16

const (
	FlagAckRequested DatagramFlag = 0x0001
)

type ControlKind uint8

const (
	ControlAnnounce      ControlKind = 1
	ControlAck           ControlKind = 2
	ControlTraceRequest  ControlKind = 3
	ControlTraceResponse ControlKind = 4
)

type TraceMetric uint8

const (
	TraceMetricUnknown TraceMetric = 0
	TraceMetricRSSIDBM TraceMetric = 1
	TraceMetricSNRQ4   TraceMetric = 2
	TraceUnavailable               = int16(-32768)
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

type Capability uint16

const (
	CapSender   Capability = 0x0001
	CapReceiver Capability = 0x0002
	CapRelay    Capability = 0x0004
	CapGateway  Capability = 0x0008
	CapCodedPHY Capability = 0x0010
)

type Capabilities uint16

func (c Capabilities) Has(cap Capability) bool { return uint16(c)&uint16(cap) != 0 }
func (c Capabilities) IsRelay() bool           { return c.Has(CapRelay) }
func (c Capabilities) IsGateway() bool         { return c.Has(CapGateway) }

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
	AndroidCapabilities = Capabilities(CapSender | CapReceiver | CapRelay | CapCodedPHY)
	LinuxCapabilities   = Capabilities(CapReceiver | CapGateway | CapCodedPHY)
)

type PHYMode string

const (
	PHYModeCodedOnly      PHYMode = "coded-only"
	PHYModeCodedPreferred PHYMode = "coded-preferred"
	PHYMode1M             PHYMode = "1m"
)
