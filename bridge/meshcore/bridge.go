// Package meshcore implements a minimal, dependency-free bridge that taps a
// running meshcore-go backend daemon over its Unix-socket IPC and forwards
// MeshCore over-the-air packets into a BLEEdge mesh.
//
// Phase 1 is deliberately dumb: it subscribes to the backend's `watch_rf`
// stream (raw OTA bytes), keeps only ADVERT packets, and hands the raw bytes
// to a forward callback. The BLEEdge side wraps them opaquely in a
// PayloadTypeMeshCoreRaw DATA packet and floods them — no node decodes the
// MeshCore payload, it is carried verbatim.
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
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// MeshCore OTA header layout (meshpkt/packet.go):
//
//	bits 1-0  route type
//	bits 5-2  payload type (4 bits)
//	bits 7-6  payload version
//
// We only care about the payload type, and only about ADVERT.
const (
	payloadTypeMask   = 0x0F
	payloadTypeShift  = 2
	payloadTypeAdvert = 0x04
)

// IsAdvert reports whether a raw MeshCore OTA packet is an ADVERT (node
// advertisement). It is a pure header check — no payload decoding.
func IsAdvert(otaBytes []byte) bool {
	if len(otaBytes) == 0 {
		return false
	}
	return (otaBytes[0]>>payloadTypeShift)&payloadTypeMask == payloadTypeAdvert
}

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

// Advert is a raw MeshCore advertisement observed on the air.
type Advert struct {
	Bytes []byte
	SNR   float64
	RSSI  int
	At    time.Time
}

// Config configures a Bridge.
type Config struct {
	// Socket is the meshcore-go backend Unix socket path. Empty uses
	// DefaultSocketPath().
	Socket string
	// Device optionally selects a specific backend device session (empty =
	// the daemon's default device).
	Device string
	// DedupTTL is how long an identical advert (by content hash) is suppressed
	// after being forwarded. MeshCore re-floods the same advert repeatedly;
	// without this the BLEEdge mesh would be spammed. Zero uses 60s.
	DedupTTL time.Duration
	// ReconnectDelay is the wait before re-dialing after the stream drops or
	// the daemon is unavailable. Zero uses 2s.
	ReconnectDelay time.Duration
	// Log, if set, receives human-readable status/diagnostic lines.
	Log func(string)
}

// Bridge taps the meshcore-go backend and forwards MeshCore adverts.
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

// Run streams adverts from the backend, invoking forward for each new advert,
// until ctx is cancelled. It reconnects automatically when the daemon is
// unavailable or the stream drops. Run only returns when ctx is done.
func (b *Bridge) Run(ctx context.Context, forward func(Advert)) error {
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
func (b *Bridge) stream(ctx context.Context, forward func(Advert)) error {
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
	b.logf("meshcore bridge: connected to %s, watching RF for adverts", b.cfg.Socket)

	// Subsequent messages are bare rfPacket frames.
	for {
		var pkt rfPacket
		if err := dec.Decode(&pkt); err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return fmt.Errorf("stream closed: %w", err)
		}
		if !IsAdvert(pkt.Bytes) {
			continue
		}
		if !b.shouldForward(pkt.Bytes) {
			continue
		}
		forward(Advert{Bytes: pkt.Bytes, SNR: pkt.SNR, RSSI: pkt.RSSI, At: pkt.Timestamp})
	}
}

// shouldForward returns true if this advert's content has not been forwarded
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
