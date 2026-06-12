// Package meshcore implements a minimal, dependency-free bridge that taps a
// running meshcore-go backend daemon over its Unix-socket IPC and forwards
// MeshCore over-the-air packets into a BLEEdge mesh.
//
// Phase 1 is deliberately dumb: it subscribes to the backend's `watch_rf`
// stream (raw OTA bytes), decodes each complete MeshCore packet, and hands only
// packets that should propagate to a forward callback. The BLEEdge side wraps
// each packet opaquely in a v3 MESHCORE_PACKET datagram; BLEEdge routing never
// decodes the inner MeshCore payload.
//
// The IPC wire format is meshcore-go's own newline-JSON request/response
// protocol (see meshcore-go backend/client.go). We re-implement only the tiny
// slice of it we need so BLEEdge does not have to import the heavy meshcore-go
// module (which requires Go 1.25 and pulls a backend/sqlite dependency tree).
package meshcore

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/meshcore-cz/meshpkt"
)

// rfPacket mirrors meshcore-go's RFPacketReceived JSON shape. Bytes is the raw
// over-the-air MeshCore packet (companion frame code + signal metadata
// stripped by the backend).
type rfPacket struct {
	Timestamp time.Time `json:"timestamp"`
	SNR       float64   `json:"snr"`
	RSSI      int       `json:"rssi"`
	Bytes     []byte    `json:"bytes"`
}

type request struct {
	ID     uint64 `json:"id"`
	Device string `json:"device,omitempty"`
	Method string `json:"method"`
}

type response struct {
	ID    uint64 `json:"id"`
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	// Result is present only on the initial stream-open ack; subsequent frames
	// are bare rfPacket objects, decoded separately.
	Result json.RawMessage `json:"result,omitempty"`
}

type ForwardMode uint8

const (
	ForwardFlood ForwardMode = iota + 1
	ForwardDirect
)

func (m ForwardMode) String() string {
	switch m {
	case ForwardFlood:
		return "flood"
	case ForwardDirect:
		return "direct"
	default:
		return "unknown"
	}
}

// Packet is a decoded raw MeshCore packet observed on the air.
type Packet struct {
	Bytes      []byte
	Mesh       meshpkt.Packet
	Mode       ForwardMode
	TargetHash []byte
	SNR        float64
	RSSI       int
	At         time.Time
}

func (p Packet) Summary() string {
	target := ""
	if len(p.TargetHash) > 0 {
		target = fmt.Sprintf(" target=%s", hex.EncodeToString(p.TargetHash))
	}
	transport := ""
	if p.Mesh.Route.IsTransport() {
		transport = fmt.Sprintf(" transport=%04x/%04x", p.Mesh.TransportCodes[0], p.Mesh.TransportCodes[1])
	}
	return fmt.Sprintf("mode=%s route=%s type=%s len=%d payload=%d hops=%d hash=%d%s%s rssi=%d snr=%.1f",
		p.Mode, p.Mesh.Route, p.Mesh.Type, len(p.Bytes), len(p.Mesh.Payload),
		p.Mesh.HopCount(), p.Mesh.PathHashSize, target, transport, p.RSSI, p.SNR)
}

// Config configures a Bridge.
type Config struct {
	// Socket is the meshcore-go backend Unix socket path. Empty uses
	// DefaultSocketPath().
	Socket string
	// Device optionally selects a specific backend device session (empty =
	// the daemon's default device).
	Device string
	// DedupTTL is how long an identical packet (by content hash) is suppressed
	// after being forwarded. MeshCore re-floods packets repeatedly;
	// without this the BLEEdge mesh would be spammed. Zero uses 60s.
	DedupTTL time.Duration
	// ReconnectDelay is the wait before re-dialing after the stream drops or
	// the daemon is unavailable. Zero uses 2s.
	ReconnectDelay time.Duration
	// Log, if set, receives human-readable status/diagnostic lines.
	Log func(string)
}

// Bridge taps the meshcore-go backend and forwards MeshCore packets.
type Bridge struct {
	cfg Config

	mu   sync.Mutex
	seen map[[32]byte]time.Time // content hash -> last-forwarded time
}

// New creates a Bridge from cfg, applying defaults.
func New(cfg Config) *Bridge {
	if cfg.Socket == "" {
		cfg.Socket = DefaultSocketPath()
	}
	if cfg.DedupTTL <= 0 {
		cfg.DedupTTL = 60 * time.Second
	}
	if cfg.ReconnectDelay <= 0 {
		cfg.ReconnectDelay = 2 * time.Second
	}
	return &Bridge{cfg: cfg, seen: make(map[[32]byte]time.Time)}
}

func (b *Bridge) logf(format string, args ...any) {
	if b.cfg.Log != nil {
		b.cfg.Log(fmt.Sprintf(format, args...))
	}
}

// Run streams packets from the backend, invoking forward for each new packet,
// until ctx is cancelled. It reconnects automatically when the daemon is
// unavailable or the stream drops. Run only returns when ctx is done.
func (b *Bridge) Run(ctx context.Context, forward func(Packet)) error {
	for {
		if err := b.stream(ctx, forward); err != nil && ctx.Err() == nil {
			b.logf("meshcore bridge: %v (retrying in %s)", err, b.cfg.ReconnectDelay)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(b.cfg.ReconnectDelay):
		}
	}
}

// stream opens one watch_rf subscription and pumps frames until it errors or
// ctx is cancelled.
func (b *Bridge) stream(ctx context.Context, forward func(Packet)) error {
	d := net.Dialer{Timeout: 250 * time.Millisecond}
	conn, err := d.DialContext(ctx, "unix", b.cfg.Socket)
	if err != nil {
		return fmt.Errorf("dial %s: %w", b.cfg.Socket, err)
	}
	defer conn.Close()

	// Close the connection when ctx is cancelled so the blocking Decode below
	// unwinds.
	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()

	req := request{ID: 1, Device: b.cfg.Device, Method: "watch_rf"}
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return fmt.Errorf("send watch_rf: %w", err)
	}

	dec := json.NewDecoder(bufio.NewReader(conn))

	// First message is the stream-open ack (response{ok}).
	var ack response
	if err := dec.Decode(&ack); err != nil {
		return fmt.Errorf("read ack: %w", err)
	}
	if !ack.OK {
		if ack.Error == "" {
			ack.Error = "unknown backend error"
		}
		return fmt.Errorf("watch_rf rejected: %s", ack.Error)
	}
	b.logf("meshcore bridge: connected to %s, watching RF packets", b.cfg.Socket)

	// Subsequent messages are bare rfPacket frames.
	for {
		var pkt rfPacket
		if err := dec.Decode(&pkt); err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return fmt.Errorf("stream closed: %w", err)
		}
		if len(pkt.Bytes) == 0 {
			continue
		}
		mesh, mode, target, reason, err := classify(pkt.Bytes)
		if err != nil {
			b.logf("meshcore bridge: drop undecodable packet len=%d rssi=%d: %v", len(pkt.Bytes), pkt.RSSI, err)
			continue
		}
		if mode == 0 {
			b.logf("meshcore bridge: skip %s: %s", packetSummary(mesh, pkt.Bytes, 0, nil, pkt.RSSI, pkt.SNR), reason)
			continue
		}
		if !b.shouldForwardPacket(mesh, pkt.Bytes) {
			continue
		}
		forwardPkt := Packet{Bytes: pkt.Bytes, Mesh: mesh, Mode: mode, TargetHash: target, SNR: pkt.SNR, RSSI: pkt.RSSI, At: pkt.Timestamp}
		if mesh.Type == meshpkt.PayloadAdvert {
			b.logf("meshcore bridge: forward %s", advertSummary(forwardPkt))
		}
		forward(forwardPkt)
	}
}

func classify(raw []byte) (meshpkt.Packet, ForwardMode, []byte, string, error) {
	pkt, err := meshpkt.DecodePacket(raw)
	if err != nil {
		return meshpkt.Packet{}, 0, nil, "", err
	}
	// ADVERTs are broadcast node announcements — always flood them onto BLEEdge so nodes can
	// discover the advertiser, even when MeshCore sent the advert as a DIRECT packet (which has
	// no routable target hash and would otherwise be skipped below).
	if pkt.Type == meshpkt.PayloadAdvert {
		return pkt, ForwardFlood, nil, "", nil
	}
	switch pkt.Route {
	case meshpkt.RouteFlood, meshpkt.RouteTransportFlood:
		return pkt, ForwardFlood, nil, "", nil
	case meshpkt.RouteDirect, meshpkt.RouteTransportDirect:
		target := directTargetHash(pkt)
		if len(target) == 0 {
			return pkt, 0, nil, "direct packet has no routable target hash", nil
		}
		return pkt, ForwardDirect, target, "", nil
	default:
		return pkt, 0, nil, "unsupported route type", nil
	}
}

func directTargetHash(pkt meshpkt.Packet) []byte {
	if hops := pkt.Hops(); len(hops) > 0 {
		return cloneBytes(hops[0])
	}
	if pkt.Type == meshpkt.PayloadTrace {
		trace, err := meshpkt.DecodeTracePayload(pkt.Payload)
		if err == nil {
			if hashes := trace.RouteHashes(); len(hashes) > 0 {
				return cloneBytes(hashes[0])
			}
		}
	}
	if payloadCarriesDestHash(pkt.Type) && len(pkt.Payload) > 0 {
		return []byte{pkt.Payload[0]}
	}
	return nil
}

func payloadCarriesDestHash(t meshpkt.PayloadType) bool {
	switch t {
	case meshpkt.PayloadReq, meshpkt.PayloadResponse, meshpkt.PayloadTxtMsg, meshpkt.PayloadAnonReq, meshpkt.PayloadPath:
		return true
	default:
		return false
	}
}

func cloneBytes(in []byte) []byte {
	out := make([]byte, len(in))
	copy(out, in)
	return out
}

func packetSummary(pkt meshpkt.Packet, raw []byte, mode ForwardMode, target []byte, rssi int, snr float64) string {
	return Packet{Bytes: raw, Mesh: pkt, Mode: mode, TargetHash: target, RSSI: rssi, SNR: snr}.Summary()
}

func advertSummary(p Packet) string {
	adv, err := meshpkt.DecodeAdvertPayload(p.Mesh.Payload)
	if err != nil {
		return fmt.Sprintf("ADVERT decode-error=%q %s", err, p.Summary())
	}
	gps := ""
	if adv.HasGPS {
		gps = fmt.Sprintf(" gps=%.6f,%.6f", adv.Lat, adv.Lon)
	}
	features := ""
	if adv.HasFeat1 {
		features += fmt.Sprintf(" feat1=0x%04x", adv.Feature1)
	}
	if adv.HasFeat2 {
		features += fmt.Sprintf(" feat2=0x%04x", adv.Feature2)
	}
	signed := "unsigned"
	if !allZero(adv.Signature) {
		signed = "signed"
	}
	return fmt.Sprintf(
		"ADVERT name=%q nodeType=%s pub=%s ts=%s sig=%s%s%s %s",
		adv.Name,
		advertNodeTypeName(adv.NodeType),
		shortHex(adv.PublicKey, 8),
		adv.Timestamp.UTC().Format(time.RFC3339),
		signed,
		gps,
		features,
		p.Summary(),
	)
}

func advertNodeTypeName(t byte) string {
	switch t {
	case meshpkt.AdvertNodeChat:
		return "chat"
	case meshpkt.AdvertNodeRepeater:
		return "repeater"
	case meshpkt.AdvertNodeRoom:
		return "room"
	case meshpkt.AdvertNodeSensor:
		return "sensor"
	case meshpkt.AdvertNodeUnknown:
		return "unknown"
	default:
		return fmt.Sprintf("unknown(%d)", t)
	}
}

func shortHex(b []byte, n int) string {
	if len(b) > n {
		b = b[:n]
	}
	return hex.EncodeToString(b)
}

func allZero(b []byte) bool {
	for _, v := range b {
		if v != 0 {
			return false
		}
	}
	return true
}

// shouldForward returns true if this packet's content has not been forwarded
// within DedupTTL. It also opportunistically prunes expired entries.
func (b *Bridge) shouldForward(otaBytes []byte) bool {
	h := sha256.Sum256(otaBytes)
	now := time.Now()

	b.mu.Lock()
	defer b.mu.Unlock()
	for k, t := range b.seen {
		if now.Sub(t) > b.cfg.DedupTTL {
			delete(b.seen, k)
		}
	}
	if t, ok := b.seen[h]; ok && now.Sub(t) <= b.cfg.DedupTTL {
		return false
	}
	b.seen[h] = now
	return true
}

func (b *Bridge) shouldForwardPacket(pkt meshpkt.Packet, otaBytes []byte) bool {
	if pkt.Type == meshpkt.PayloadAdvert {
		return true
	}
	return b.shouldForward(otaBytes)
}

// DefaultSocketPath mirrors meshcore-go backend.SocketPath(): MC_BACKEND_SOCKET
// if set, else <runtime-dir>/mc/backend.sock. On macOS the runtime dir is the
// user cache dir (~/Library/Caches) unless XDG_RUNTIME_DIR is set.
func DefaultSocketPath() string {
	if s := os.Getenv("MC_BACKEND_SOCKET"); s != "" {
		return s
	}
	return filepath.Join(runtimeDir(), "mc", "backend.sock")
}

func runtimeDir() string {
	if dir := os.Getenv("XDG_RUNTIME_DIR"); dir != "" {
		return dir
	}
	if dir, err := os.UserCacheDir(); err == nil {
		return dir
	}
	return os.TempDir()
}
