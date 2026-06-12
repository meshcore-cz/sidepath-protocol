package core

import (
	"crypto/rand"
	"encoding/binary"
	"sort"

	"github.com/fxamacker/cbor/v2"
)

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

type AnnounceBody struct {
	AnnounceVersion uint8        `cbor:"1,keyasint"`
	PublicKey       []byte       `cbor:"2,keyasint"`
	Epoch           uint64       `cbor:"3,keyasint"`
	Seq             uint32       `cbor:"4,keyasint"`
	Timestamp       int64        `cbor:"5,keyasint"`
	Caps            Capabilities `cbor:"6,keyasint"`
	Neighbors       []NodeID     `cbor:"7,keyasint"`
	Name            string       `cbor:"8,keyasint"`
	Description     string       `cbor:"9,keyasint"`
	Platform        string       `cbor:"10,keyasint"`
	Signature       []byte       `cbor:"11,keyasint"`
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
	if a.AnnounceVersion != AnnounceVersion {
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
	return VerifyAnnounce(a.PublicKey, a.Signature, a.Epoch, a.Seq, a.Timestamp, a.Caps, a.Neighbors, a.Name, a.Description, a.Platform)
}

func NewAnnounceBody(id *Identity, epoch uint64, seq uint32, timestamp int64, caps Capabilities, neighbors []NodeID, name, desc, platform string) AnnounceBody {
	nbs := append([]NodeID(nil), neighbors...)
	sort.Slice(nbs, func(i, j int) bool { return nbs[i].Less(nbs[j]) })
	uniq := nbs[:0]
	for _, nb := range nbs {
		if len(uniq) == 0 || uniq[len(uniq)-1] != nb {
			uniq = append(uniq, nb)
		}
	}
	sig := id.SignAnnounce(epoch, seq, timestamp, caps, uniq, name, desc, platform)
	return AnnounceBody{
		AnnounceVersion: AnnounceVersion,
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
