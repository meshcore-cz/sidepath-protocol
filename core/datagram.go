package core

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"sort"
	"strconv"
	"strings"

	"github.com/fxamacker/cbor/v2"
)

// ParseBridgeSpec parses a CLI bridge spec into a BridgeAd. The format is either "CODE" (canonical
// radio params, resolved by receivers from the code's definition) or "CODE:freqHz,bwHz,sf,cr" for
// custom radio params that differ from the canonical set. e.g. "CZ" or "CZ:869525000,250000,11,5".
func ParseBridgeSpec(s string) (BridgeAd, error) {
	code, rest, custom := strings.Cut(s, ":")
	code = strings.TrimSpace(code)
	if code == "" || len(code) > MaxNetworkCodeBytes {
		return BridgeAd{}, fmt.Errorf("network code must be 1..%d chars", MaxNetworkCodeBytes)
	}
	b := BridgeAd{Code: code}
	if custom {
		parts := strings.Split(rest, ",")
		if len(parts) != 4 {
			return BridgeAd{}, fmt.Errorf("custom params must be freqHz,bwHz,sf,cr")
		}
		freq, e1 := strconv.ParseUint(strings.TrimSpace(parts[0]), 10, 32)
		bw, e2 := strconv.ParseUint(strings.TrimSpace(parts[1]), 10, 32)
		sf, e3 := strconv.ParseUint(strings.TrimSpace(parts[2]), 10, 8)
		cr, e4 := strconv.ParseUint(strings.TrimSpace(parts[3]), 10, 8)
		if e1 != nil || e2 != nil || e3 != nil || e4 != nil {
			return BridgeAd{}, fmt.Errorf("custom params must be integers")
		}
		b.FreqHz, b.BandwidthHz, b.SF, b.CR = freq, bw, uint8(sf), uint8(cr)
	}
	if !b.Valid() {
		return BridgeAd{}, fmt.Errorf("invalid bridge params (sf 5..12, cr 5..8)")
	}
	return b, nil
}

func NewDatagramID() DatagramID {
	var id DatagramID
	rand.Read(id[:]) //nolint:errcheck
	return id
}

func RandomUint32() uint32 {
	var b [4]byte
	rand.Read(b[:]) //nolint:errcheck
	return binary.LittleEndian.Uint32(b[:])
}

func NewTransferID() TransferID {
	var id TransferID
	rand.Read(id[:]) //nolint:errcheck
	return id
}

type Datagram struct {
	Version     uint8           `cbor:"1,keyasint"`
	ID          DatagramID      `cbor:"2,keyasint"`
	Source      NodeID          `cbor:"3,keyasint"`
	Destination NodeID          `cbor:"4,keyasint"`
	TTL         uint8           `cbor:"5,keyasint"`
	Route       []NodeID        `cbor:"6,keyasint,omitempty"`
	RouteCursor uint8           `cbor:"7,keyasint,omitempty"`
	Path        []NodeID        `cbor:"8,keyasint,omitempty"`
	Protocol    PayloadProtocol `cbor:"9,keyasint"`
	Flags       uint16          `cbor:"10,keyasint,omitempty"`
	Payload     []byte          `cbor:"11,keyasint"`
}

func (d Datagram) IsBroadcast() bool    { return d.Destination.IsBroadcast() }
func (d Datagram) IsSourceRouted() bool { return len(d.Route) > 0 }
func (d Datagram) AckRequested() bool   { return d.Flags&uint16(FlagAckRequested) != 0 }

func (d Datagram) Encode() ([]byte, error) {
	return cbor.Marshal(d)
}

func DecodeDatagram(data []byte) (Datagram, error) {
	var d Datagram
	err := cbor.Unmarshal(data, &d)
	return d, err
}

type ControlMessage struct {
	Kind ControlKind     `cbor:"1,keyasint"`
	Body cbor.RawMessage `cbor:"2,keyasint"`
}

func (c ControlMessage) Encode() ([]byte, error) {
	return cbor.Marshal(c)
}

func DecodeControl(data []byte) (ControlMessage, error) {
	var c ControlMessage
	err := cbor.Unmarshal(data, &c)
	return c, err
}

// BridgeAd is one external network a gateway node bridges, advertised in the v2 ANNOUNCE `bridges`
// array (§8.3). Code is the short network code (e.g. "CZ", <= MaxNetworkCodeBytes). Radio params are
// carried only when they differ from the code's canonical definition (IsCustom); otherwise the
// receiver resolves them from its network-definitions dataset. FreqHz/BandwidthHz are integer Hz (no
// float on the wire); SF is the spreading factor; CR is the N in coding rate 4/N. The radio keys are
// present in CBOR only when custom.
type BridgeAd struct {
	Code        string `cbor:"1,keyasint"`
	FreqHz      uint64 `cbor:"2,keyasint,omitempty"`
	BandwidthHz uint64 `cbor:"3,keyasint,omitempty"`
	SF          uint8  `cbor:"4,keyasint,omitempty"`
	CR          uint8  `cbor:"5,keyasint,omitempty"`
}

// IsCustom reports whether this entry carries explicit radio params (they differ from the code's
// canonical set).
func (b BridgeAd) IsCustom() bool {
	return b.FreqHz > 0 || b.BandwidthHz > 0 || b.SF > 0 || b.CR > 0
}

// Valid checks the code length and, for custom entries, that the radio params are fully specified
// and in range.
func (b BridgeAd) Valid() bool {
	codeLen := len([]byte(b.Code))
	if codeLen < 1 || codeLen > MaxNetworkCodeBytes {
		return false
	}
	if b.IsCustom() {
		if b.FreqHz == 0 || b.FreqHz > 0xFFFFFFFF {
			return false
		}
		if b.BandwidthHz == 0 || b.BandwidthHz > 0xFFFFFFFF {
			return false
		}
		if b.SF < 5 || b.SF > 12 || b.CR < 5 || b.CR > 8 {
			return false
		}
	}
	return true
}

// NeighborInfo is one directly-linked peer as advertised in a v3 ANNOUNCE `neighbor_info` section.
// It is the announcing node's own view of the link: RSSI in dBm (always negative in practice; 0 means
// "no sample"), the BLE PHY in each direction, which side opened the link, and how long ago the last
// packet was received. CBOR is transport; the signed binary (§8.3) is the authenticated form.
type NeighborInfo struct {
	ID    NodeID        `cbor:"1,keyasint"`
	RSSI  int8          `cbor:"2,keyasint,omitempty"`
	TxPHY PHY           `cbor:"3,keyasint,omitempty"`
	RxPHY PHY           `cbor:"4,keyasint,omitempty"`
	Dir   ConnDirection `cbor:"5,keyasint,omitempty"`
	AgeS  uint32        `cbor:"6,keyasint,omitempty"`
}

// Valid checks the PHY and direction enums are in range.
func (n NeighborInfo) Valid() bool {
	if n.TxPHY > PHYCoded || n.RxPHY > PHYCoded {
		return false
	}
	if n.Dir > DirectionBoth {
		return false
	}
	return true
}

type AnnounceBody struct {
	AnnounceVersion uint8        `cbor:"1,keyasint"`
	PublicKey       []byte       `cbor:"2,keyasint"`
	Epoch           uint64       `cbor:"3,keyasint"`
	Seq             uint32       `cbor:"4,keyasint"`
	Timestamp       int64        `cbor:"5,keyasint"`
	Caps            Capabilities `cbor:"6,keyasint"`
	// Neighbors is the bare neighbor-ID list carried by v1/v2 announces. v3 leaves it empty and
	// carries neighbors via NeighborInfo instead (use NeighborIDs to read either uniformly).
	Neighbors   []NodeID `cbor:"7,keyasint"`
	Name        string   `cbor:"8,keyasint"`
	Description string   `cbor:"9,keyasint"`
	Platform    string   `cbor:"10,keyasint"`
	Signature   []byte   `cbor:"11,keyasint"`
	// Bridges is present (CBOR k12) only on v2+ gateway announces; omitempty keeps v1 bodies
	// byte-for-byte unchanged. CBOR is transport; the signed binary (§8.3) is the authenticated form.
	Bridges []BridgeAd `cbor:"12,keyasint,omitempty"`
	// NeighborInfo is present (CBOR k13) only on v3 announces; it replaces the bare Neighbors list.
	NeighborInfo []NeighborInfo `cbor:"13,keyasint,omitempty"`
}

// NeighborIDs returns the announcing node's neighbor IDs regardless of announce version: the bare
// Neighbors list on v1/v2, or the IDs from NeighborInfo on v3.
func (a AnnounceBody) NeighborIDs() []NodeID {
	if len(a.NeighborInfo) == 0 {
		return a.Neighbors
	}
	ids := make([]NodeID, len(a.NeighborInfo))
	for i, n := range a.NeighborInfo {
		ids[i] = n.ID
	}
	return ids
}

func (a AnnounceBody) EncodeBody() ([]byte, error) { return cbor.Marshal(a) }

func (a AnnounceBody) ToControl() ([]byte, error) {
	body, err := a.EncodeBody()
	if err != nil {
		return nil, err
	}
	return ControlMessage{Kind: ControlAnnounce, Body: body}.Encode()
}

func DecodeAnnounceBody(raw []byte) (AnnounceBody, error) {
	var a AnnounceBody
	err := cbor.Unmarshal(raw, &a)
	return a, err
}

func (a AnnounceBody) Valid() bool {
	if a.AnnounceVersion < MinAnnounceVersion || a.AnnounceVersion > AnnounceVersion {
		return false
	}
	if len(a.PublicKey) != PublicKeyBytes || len(a.Signature) != SignatureBytes {
		return false
	}
	if len(a.Neighbors) > MaxNeighbors {
		return false
	}
	if len([]byte(a.Name)) > MaxNameBytes || len([]byte(a.Description)) > MaxDescBytes || len([]byte(a.Platform)) > MaxPlatformBytes {
		return false
	}
	for i := 1; i < len(a.Neighbors); i++ {
		if CompareNodeID(a.Neighbors[i-1], a.Neighbors[i]) >= 0 {
			return false
		}
	}
	// Bridges only exist from v2; reject any carried on a v1 body, and bound/validate v2 entries.
	if a.AnnounceVersion < 2 && len(a.Bridges) > 0 {
		return false
	}
	if len(a.Bridges) > MaxBridges {
		return false
	}
	for _, b := range a.Bridges {
		if !b.Valid() {
			return false
		}
	}
	// NeighborInfo only exists from v3; v3 carries its neighbors there and leaves the bare list empty.
	if a.AnnounceVersion < 3 && len(a.NeighborInfo) > 0 {
		return false
	}
	if len(a.NeighborInfo) > 0 && len(a.Neighbors) > 0 {
		return false
	}
	if len(a.NeighborInfo) > MaxNeighbors {
		return false
	}
	for i, n := range a.NeighborInfo {
		if !n.Valid() {
			return false
		}
		if i > 0 && CompareNodeID(a.NeighborInfo[i-1].ID, n.ID) >= 0 {
			return false
		}
	}
	return VerifyAnnounce(a.PublicKey, a.Signature, a.Epoch, a.Seq, a.Timestamp, a.Caps, a.Neighbors, a.Name, a.Description, a.Platform, a.AnnounceVersion, a.Bridges, a.NeighborInfo)
}

// NewAnnounceBody builds and signs an announce body. It emits v1 (byte-identical to the original
// layout) when bridges is empty, or v2 with the `bridges` section when it isn't.
func NewAnnounceBody(id *Identity, epoch uint64, seq uint32, timestamp int64, caps Capabilities, neighbors []NodeID, name, desc, platform string, bridges []BridgeAd) AnnounceBody {
	nbs := append([]NodeID(nil), neighbors...)
	sort.Slice(nbs, func(i, j int) bool { return nbs[i].Less(nbs[j]) })
	uniq := nbs[:0]
	for _, nb := range nbs {
		if len(uniq) == 0 || uniq[len(uniq)-1] != nb {
			uniq = append(uniq, nb)
		}
	}
	version := uint8(MinAnnounceVersion)
	if len(bridges) > 0 {
		version = 2
	}
	sig := id.SignAnnounce(epoch, seq, timestamp, caps, uniq, name, desc, platform, version, bridges, nil)
	return AnnounceBody{
		AnnounceVersion: version,
		PublicKey:       append([]byte(nil), id.Pub...),
		Epoch:           epoch,
		Seq:             seq,
		Timestamp:       timestamp,
		Caps:            caps,
		Neighbors:       uniq,
		Name:            name,
		Description:     desc,
		Platform:        platform,
		Signature:       sig,
		Bridges:         bridges,
	}
}

// NewAnnounceBodyV3 builds and signs a v3 announce. Neighbors are carried as NeighborInfo (with
// per-link RSSI/PHY/direction/age) rather than the bare v1/v2 ID list, which v3 leaves empty. The
// info entries are sorted by ID and de-duplicated; bridges, when present, ride along in the v2 section.
func NewAnnounceBodyV3(id *Identity, epoch uint64, seq uint32, timestamp int64, caps Capabilities, infos []NeighborInfo, name, desc, platform string, bridges []BridgeAd) AnnounceBody {
	nbs := append([]NeighborInfo(nil), infos...)
	sort.Slice(nbs, func(i, j int) bool { return nbs[i].ID.Less(nbs[j].ID) })
	uniq := nbs[:0]
	for _, nb := range nbs {
		if len(uniq) == 0 || uniq[len(uniq)-1].ID != nb.ID {
			uniq = append(uniq, nb)
		}
	}
	sig := id.SignAnnounce(epoch, seq, timestamp, caps, nil, name, desc, platform, 3, bridges, uniq)
	return AnnounceBody{
		AnnounceVersion: 3,
		PublicKey:       append([]byte(nil), id.Pub...),
		Epoch:           epoch,
		Seq:             seq,
		Timestamp:       timestamp,
		Caps:            caps,
		Name:            name,
		Description:     desc,
		Platform:        platform,
		Signature:       sig,
		Bridges:         bridges,
		NeighborInfo:    uniq,
	}
}

type AckBody struct {
	AckedID DatagramID `cbor:"1,keyasint"`
}

func (a AckBody) ToControl() ([]byte, error) {
	body, err := cbor.Marshal(a)
	if err != nil {
		return nil, err
	}
	return ControlMessage{Kind: ControlAck, Body: body}.Encode()
}

// BridgedBody (ACK_BRIDGED, §9.2) is returned to the sender of a channel message after a gateway
// relayed it onto an external network. BridgedID is the bridged Sidepath datagram id, BridgeID the
// gateway's NodeID, and MeshHash an optional short hash of the emitted external packet (for dedup
// / correlation). Purely informational; never used for routing.
type BridgedBody struct {
	BridgedID DatagramID `cbor:"1,keyasint"`
	BridgeID  NodeID     `cbor:"2,keyasint"`
	MeshHash  []byte     `cbor:"3,keyasint,omitempty"`
}

func (b BridgedBody) ToControl() ([]byte, error) {
	body, err := cbor.Marshal(b)
	if err != nil {
		return nil, err
	}
	return ControlMessage{Kind: ControlBridged, Body: body}.Encode()
}

type TraceRequestBody struct {
	Tag            uint32      `cbor:"1,keyasint"`
	Metric         TraceMetric `cbor:"2,keyasint"`
	ForwardSamples []int16     `cbor:"3,keyasint"`
}

func (t TraceRequestBody) ToControl() ([]byte, error) {
	body, err := cbor.Marshal(t)
	if err != nil {
		return nil, err
	}
	return ControlMessage{Kind: ControlTraceRequest, Body: body}.Encode()
}

type TraceResponseBody struct {
	Tag            uint32      `cbor:"1,keyasint"`
	Metric         TraceMetric `cbor:"2,keyasint"`
	ForwardPath    []NodeID    `cbor:"3,keyasint"`
	ForwardSamples []int16     `cbor:"4,keyasint"`
	ReturnSamples  []int16     `cbor:"5,keyasint"`
}

func (t TraceResponseBody) ToControl() ([]byte, error) {
	body, err := cbor.Marshal(t)
	if err != nil {
		return nil, err
	}
	return ControlMessage{Kind: ControlTraceResponse, Body: body}.Encode()
}
