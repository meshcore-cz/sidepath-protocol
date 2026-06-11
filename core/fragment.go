package core

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"sync"
	"time"
)

// Frame is the GATT transport unit.
// Wire encoding: version(1) + packet_id(16) + frag_idx(1) + frag_count(1) + crc32(4) + data
type Frame struct {
	Version       uint8
	PacketID      PacketID
	FragmentIndex uint8
	FragmentCount uint8
	PayloadCRC32  uint32
	Data          []byte
}

// FrameHeaderSize is the fixed GATT frame header:
// version(1) + packet_id(16) + frag_idx(1) + frag_count(1) + crc32(4).
const FrameHeaderSize = 23

// MaxFrameSize is the maximum GATT frame — and therefore the maximum single ATT
// write — used for fragmentation by ALL implementations. It must stay <= the
// smallest peer's negotiated (ATT_MTU - 3). The ESP32 relay negotiates an ATT
// MTU of 247, so 200 is safe for every peer. Nodes still request ATT MTU 512,
// but deliberately cap frames here: broadcast and relayed packets are fragmented
// ONCE and sent to every neighbour, so the frames must fit the smallest link in
// a single write. Reassembly is size-agnostic, so capping has no interop cost.
// Mirrors the firmware FRAGMENT_MTU and the Kotlin MAX_FRAME_SIZE.
const MaxFrameSize = 200

const frameHeaderSize = FrameHeaderSize

func (f Frame) Encode() []byte {
	buf := make([]byte, frameHeaderSize+len(f.Data))
	buf[0] = f.Version
	copy(buf[1:17], f.PacketID[:])
	buf[17] = f.FragmentIndex
	buf[18] = f.FragmentCount
	binary.BigEndian.PutUint32(buf[19:23], f.PayloadCRC32)
	copy(buf[23:], f.Data)
	return buf
}

func DecodeFrame(raw []byte) (Frame, error) {
	if len(raw) < frameHeaderSize {
		return Frame{}, fmt.Errorf("frame too short: %d bytes", len(raw))
	}
	var f Frame
	f.Version = raw[0]
	copy(f.PacketID[:], raw[1:17])
	f.FragmentIndex = raw[17]
	f.FragmentCount = raw[18]
	f.PayloadCRC32 = binary.BigEndian.Uint32(raw[19:23])
	f.Data = make([]byte, len(raw)-frameHeaderSize)
	copy(f.Data, raw[23:])
	return f, nil
}

// FragmentPacket splits packet payload bytes into GATT frames. The mtu argument
// is the maximum frame (single GATT write) size in bytes — pass core.MaxFrameSize
// (or, for a known single peer, that peer's negotiated ATT_MTU-3). Each frame
// carries up to mtu-FrameHeaderSize bytes of packet data.
func FragmentPacket(packetData []byte, mtu int, pid PacketID) []Frame {
	maxData := mtu - frameHeaderSize
	if maxData < 1 {
		maxData = 1
	}

	crc := crc32.ChecksumIEEE(packetData)

	var frames []Frame
	for i := 0; i < len(packetData); i += maxData {
		end := i + maxData
		if end > len(packetData) {
			end = len(packetData)
		}
		chunk := make([]byte, end-i)
		copy(chunk, packetData[i:end])
		frames = append(frames, Frame{
			Version:       1,
			PacketID:      pid,
			FragmentIndex: uint8(len(frames)),
			FragmentCount: 0, // filled in below
			PayloadCRC32:  crc,
			Data:          chunk,
		})
	}
	count := uint8(len(frames))
	for i := range frames {
		frames[i].FragmentCount = count
	}
	return frames
}

// Reassembler collects frames and reassembles packets.
type Reassembler struct {
	mu      sync.Mutex
	pending map[PacketID]*assembly
	timeout time.Duration
}

type assembly struct {
	frags    map[uint8][]byte
	count    uint8
	crc      uint32
	lastSeen time.Time
}

func NewReassembler() *Reassembler {
	r := &Reassembler{
		pending: make(map[PacketID]*assembly),
		timeout: 10 * time.Second,
	}
	go r.reap()
	return r
}

// AddFrame adds a frame. Returns (packetData, true, nil) when all fragments received and CRC OK.
func (r *Reassembler) AddFrame(f Frame) ([]byte, bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	a, ok := r.pending[f.PacketID]
	if !ok {
		a = &assembly{frags: make(map[uint8][]byte), count: f.FragmentCount, crc: f.PayloadCRC32}
		r.pending[f.PacketID] = a
	}
	a.lastSeen = time.Now()
	if _, dup := a.frags[f.FragmentIndex]; dup {
		return nil, false, nil // duplicate fragment, ignore
	}
	a.frags[f.FragmentIndex] = f.Data

	if uint8(len(a.frags)) < a.count {
		return nil, false, nil
	}

	// All fragments received — verify we have every index
	var buf bytes.Buffer
	for i := uint8(0); i < a.count; i++ {
		d, ok := a.frags[i]
		if !ok {
			return nil, false, nil // still missing one
		}
		buf.Write(d)
	}
	delete(r.pending, f.PacketID)

	data := buf.Bytes()
	if crc32.ChecksumIEEE(data) != a.crc {
		return nil, false, fmt.Errorf("CRC mismatch on packet %x", f.PacketID)
	}
	return data, true, nil
}

func (r *Reassembler) reap() {
	ticker := time.NewTicker(5 * time.Second)
	for range ticker.C {
		r.Reap()
	}
}

// Reap removes stale assemblies that have not received a fragment within the timeout.
// Exported for testing.
func (r *Reassembler) Reap() {
	r.mu.Lock()
	now := time.Now()
	for id, a := range r.pending {
		if now.Sub(a.lastSeen) > r.timeout {
			delete(r.pending, id)
		}
	}
	r.mu.Unlock()
}
