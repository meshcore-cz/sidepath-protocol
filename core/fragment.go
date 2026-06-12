package core

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"sync"
	"time"
)

type Frame struct {
	Version       uint8
	TransferID    TransferID
	FragmentIndex uint8
	FragmentCount uint8
	PayloadCRC32  uint32
	Data          []byte
}

const (
	FrameHeaderSize = 23
	MaxFrameSize    = 200
	MaxFrameData    = MaxFrameSize - FrameHeaderSize
)

func (f Frame) Encode() []byte {
	buf := make([]byte, FrameHeaderSize+len(f.Data))
	buf[0] = f.Version
	copy(buf[1:17], f.TransferID[:])
	buf[17] = f.FragmentIndex
	buf[18] = f.FragmentCount
	binary.BigEndian.PutUint32(buf[19:23], f.PayloadCRC32)
	copy(buf[23:], f.Data)
	return buf
}

func DecodeFrame(raw []byte) (Frame, error) {
	if len(raw) < FrameHeaderSize {
		return Frame{}, fmt.Errorf("frame too short: %d bytes", len(raw))
	}
	var f Frame
	f.Version = raw[0]
	copy(f.TransferID[:], raw[1:17])
	f.FragmentIndex = raw[17]
	f.FragmentCount = raw[18]
	f.PayloadCRC32 = binary.BigEndian.Uint32(raw[19:23])
	f.Data = append([]byte(nil), raw[23:]...)
	if f.Version != FrameVersion {
		return Frame{}, fmt.Errorf("invalid frame version: %d", f.Version)
	}
	if f.FragmentCount == 0 || f.FragmentIndex >= f.FragmentCount {
		return Frame{}, fmt.Errorf("invalid fragment index/count: %d/%d", f.FragmentIndex, f.FragmentCount)
	}
	return f, nil
}

func FragmentDatagram(datagramBytes []byte, maxFrameSize int, transferID TransferID) []Frame {
	maxData := maxFrameSize - FrameHeaderSize
	if maxData < 1 {
		maxData = 1
	}
	crc := crc32.ChecksumIEEE(datagramBytes)
	var frames []Frame
	for i := 0; i < len(datagramBytes) || (len(datagramBytes) == 0 && i == 0); i += maxData {
		end := i + maxData
		if end > len(datagramBytes) {
			end = len(datagramBytes)
		}
		chunk := append([]byte(nil), datagramBytes[i:end]...)
		frames = append(frames, Frame{Version: FrameVersion, TransferID: transferID, FragmentIndex: uint8(len(frames)), PayloadCRC32: crc, Data: chunk})
		if len(datagramBytes) == 0 {
			break
		}
	}
	count := uint8(len(frames))
	for i := range frames {
		frames[i].FragmentCount = count
	}
	return frames
}

func FragmentDatagramNew(datagramBytes []byte, maxFrameSize int) []Frame {
	return FragmentDatagram(datagramBytes, maxFrameSize, NewTransferID())
}

type Reassembler struct {
	mu      sync.Mutex
	pending map[reassemblyKey]*assembly
	timeout time.Duration
}

type reassemblyKey struct {
	Peer       string
	TransferID TransferID
}

type assembly struct {
	frags    map[uint8][]byte
	count    uint8
	crc      uint32
	lastSeen time.Time
}

func NewReassembler() *Reassembler {
	r := &Reassembler{
		pending: make(map[reassemblyKey]*assembly),
		timeout: 10 * time.Second,
	}
	go r.reap()
	return r
}

func (r *Reassembler) AddFrame(peerLinkID string, f Frame) ([]byte, bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	k := reassemblyKey{Peer: peerLinkID, TransferID: f.TransferID}
	a, ok := r.pending[k]
	if !ok {
		a = &assembly{frags: make(map[uint8][]byte), count: f.FragmentCount, crc: f.PayloadCRC32}
		r.pending[k] = a
	}
	if a.count != f.FragmentCount || a.crc != f.PayloadCRC32 {
		delete(r.pending, k)
		return nil, false, fmt.Errorf("fragment metadata changed")
	}
	a.lastSeen = time.Now()
	if _, dup := a.frags[f.FragmentIndex]; dup {
		return nil, false, nil
	}
	a.frags[f.FragmentIndex] = f.Data
	if uint8(len(a.frags)) < a.count {
		return nil, false, nil
	}

	var buf bytes.Buffer
	for i := uint8(0); i < a.count; i++ {
		d, ok := a.frags[i]
		if !ok {
			return nil, false, nil
		}
		buf.Write(d)
	}
	delete(r.pending, k)

	data := buf.Bytes()
	if crc32.ChecksumIEEE(data) != a.crc {
		return nil, false, fmt.Errorf("CRC mismatch on transfer %x", f.TransferID)
	}
	return data, true, nil
}

func (r *Reassembler) reap() {
	ticker := time.NewTicker(5 * time.Second)
	for range ticker.C {
		r.Reap()
	}
}

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
