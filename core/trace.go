package core

import (
	"encoding/binary"
	"fmt"

	"github.com/fxamacker/cbor/v2"
)

const (
	TraceHashWidth1 = 1
	TraceHashWidth2 = 2
	TraceHashWidth4 = 4
	TraceHashWidth8 = 8

	TraceMetricUnknown = "unknown"
	TraceMetricRSSI    = "rssi"
	TraceMetricSNR     = "snr"
)

// TracePayload follows MeshCore's TRACE payload prefix:
// [tag:4 LE][auth_code:4 LE][flags:1][route_hashes...].
//
// flags & 0x03 selects each route hash width: 0=1B, 1=2B, 2=4B, 3=8B.
// BLEEdge currently uses 8-byte NodeID route hashes for exact routing.
type TracePayload struct {
	Tag       uint32
	AuthCode  uint32
	Flags     byte
	RouteData []byte
}

func TraceFlagsForHashWidth(width int) (byte, error) {
	switch width {
	case TraceHashWidth1:
		return 0, nil
	case TraceHashWidth2:
		return 1, nil
	case TraceHashWidth4:
		return 2, nil
	case TraceHashWidth8:
		return 3, nil
	default:
		return 0, fmt.Errorf("invalid trace hash width %d", width)
	}
}

func (p TracePayload) HashWidth() int {
	return 1 << (p.Flags & 0x03)
}

func (p TracePayload) RouteHashes() [][]byte {
	width := p.HashWidth()
	if width <= 0 || len(p.RouteData) == 0 {
		return nil
	}
	n := len(p.RouteData) / width
	out := make([][]byte, n)
	for i := range out {
		out[i] = append([]byte(nil), p.RouteData[i*width:(i+1)*width]...)
	}
	return out
}

func EncodeTracePayload(p TracePayload) []byte {
	payload := make([]byte, 9+len(p.RouteData))
	binary.LittleEndian.PutUint32(payload[0:4], p.Tag)
	binary.LittleEndian.PutUint32(payload[4:8], p.AuthCode)
	payload[8] = p.Flags
	copy(payload[9:], p.RouteData)
	return payload
}

func DecodeTracePayload(payload []byte) (TracePayload, error) {
	if len(payload) < 9 {
		return TracePayload{}, fmt.Errorf("trace payload too short: %d", len(payload))
	}
	return TracePayload{
		Tag:       binary.LittleEndian.Uint32(payload[0:4]),
		AuthCode:  binary.LittleEndian.Uint32(payload[4:8]),
		Flags:     payload[8],
		RouteData: append([]byte(nil), payload[9:]...),
	}, nil
}

func TraceRouteData(route []NodeID, width int) ([]byte, error) {
	if _, err := TraceFlagsForHashWidth(width); err != nil {
		return nil, err
	}
	out := make([]byte, 0, len(route)*width)
	for _, id := range route {
		out = append(out, id[:width]...)
	}
	return out, nil
}

// TraceResult is returned to the originator when a trace reaches its destination.
// ForwardSamples are collected on the outbound request. ReturnSamples are collected
// on the result packet as it travels back. On macOS CoreBluetooth exposes RSSI but
// not LoRa SNR, so Metric is currently "rssi"; future radios can use "snr".
type TraceResult struct {
	Tag            uint32   `cbor:"1,keyasint"`
	AuthCode       uint32   `cbor:"2,keyasint"`
	Route          []NodeID `cbor:"3,keyasint"`
	ForwardNodes   []NodeID `cbor:"4,keyasint"`
	ForwardSamples []int8   `cbor:"5,keyasint"`
	ReturnNodes    []NodeID `cbor:"6,keyasint,omitempty"`
	ReturnSamples  []int8   `cbor:"7,keyasint,omitempty"`
	Metric         string   `cbor:"8,keyasint"`
}

type traceResultWire struct {
	Tag            int32    `cbor:"1,keyasint"`
	AuthCode       int32    `cbor:"2,keyasint"`
	Route          []NodeID `cbor:"3,keyasint"`
	ForwardNodes   []NodeID `cbor:"4,keyasint"`
	ForwardSamples []int8   `cbor:"5,keyasint"`
	ReturnNodes    []NodeID `cbor:"6,keyasint,omitempty"`
	ReturnSamples  []int8   `cbor:"7,keyasint,omitempty"`
	Metric         string   `cbor:"8,keyasint"`
}

func (r TraceResult) Encode() ([]byte, error) {
	return cbor.Marshal(traceResultWire{
		Tag:            int32(r.Tag),
		AuthCode:       int32(r.AuthCode),
		Route:          r.Route,
		ForwardNodes:   r.ForwardNodes,
		ForwardSamples: r.ForwardSamples,
		ReturnNodes:    r.ReturnNodes,
		ReturnSamples:  r.ReturnSamples,
		Metric:         r.Metric,
	})
}

func DecodeTraceResult(payload []byte) (TraceResult, error) {
	var raw map[int]cbor.RawMessage
	if err := cbor.Unmarshal(payload, &raw); err != nil {
		return TraceResult{}, err
	}
	tag, err := decodeTraceUint32(raw[1])
	if err != nil {
		return TraceResult{}, fmt.Errorf("trace tag: %w", err)
	}
	auth, err := decodeTraceUint32(raw[2])
	if err != nil {
		return TraceResult{}, fmt.Errorf("trace auth: %w", err)
	}
	var w traceResultWire
	w.Tag = int32(tag)
	w.AuthCode = int32(auth)
	if err := decodeTraceField(raw, 3, &w.Route); err != nil {
		return TraceResult{}, fmt.Errorf("trace route: %w", err)
	}
	if err := decodeTraceField(raw, 4, &w.ForwardNodes); err != nil {
		return TraceResult{}, fmt.Errorf("trace forward nodes: %w", err)
	}
	if err := decodeTraceField(raw, 5, &w.ForwardSamples); err != nil {
		return TraceResult{}, fmt.Errorf("trace forward samples: %w", err)
	}
	if err := decodeTraceField(raw, 6, &w.ReturnNodes); err != nil {
		return TraceResult{}, fmt.Errorf("trace return nodes: %w", err)
	}
	if err := decodeTraceField(raw, 7, &w.ReturnSamples); err != nil {
		return TraceResult{}, fmt.Errorf("trace return samples: %w", err)
	}
	if err := decodeTraceField(raw, 8, &w.Metric); err != nil {
		return TraceResult{}, fmt.Errorf("trace metric: %w", err)
	}
	return TraceResult{
		Tag:            uint32(w.Tag),
		AuthCode:       uint32(w.AuthCode),
		Route:          w.Route,
		ForwardNodes:   w.ForwardNodes,
		ForwardSamples: w.ForwardSamples,
		ReturnNodes:    w.ReturnNodes,
		ReturnSamples:  w.ReturnSamples,
		Metric:         w.Metric,
	}, nil
}

func decodeTraceUint32(raw cbor.RawMessage) (uint32, error) {
	if len(raw) == 0 {
		return 0, nil
	}
	var v int64
	if err := cbor.Unmarshal(raw, &v); err != nil {
		return 0, err
	}
	return uint32(v), nil
}

func decodeTraceField(raw map[int]cbor.RawMessage, key int, dst any) error {
	msg := raw[key]
	if len(msg) == 0 {
		return nil
	}
	return cbor.Unmarshal(msg, dst)
}
