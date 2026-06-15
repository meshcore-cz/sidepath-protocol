// Package meshcore implements a minimal, dependency-free bridge that taps a
// running meshcore-go backend daemon over its Unix-socket IPC and forwards
// MeshCore over-the-air packets into a Sidepath mesh.
//
// Phase 1 is deliberately dumb: it subscribes to the backend's `watch_rf`
// stream (raw OTA bytes), decodes each complete MeshCore packet, and hands only
// packets that should propagate to a forward callback. The Sidepath side wraps
// each packet opaquely in a v3 MESHCORE_PACKET datagram; Sidepath routing never
// decodes the inner MeshCore payload.
//
// The IPC wire format is meshcore-go's own newline-JSON request/response
// protocol (see meshcore-go backend/client.go). We re-implement only the tiny
// slice of it we need so Sidepath does not have to import the heavy meshcore-go
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
	ID     uint64          `json:"id"`
	Device string          `json:"device,omitempty"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params,omitempty"`
}

// sendMeshPacketParams mirrors meshcore-go's send_mesh_packet IPC params: an opaque, fully-formed
// MeshCore OTA packet plus a priority hint (0 = default).
type sendMeshPacketParams struct {
	Priority byte   `json:"priority"`
	Packet   []byte `json:"packet"`
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
	// without this the Sidepath mesh would be spammed. Zero uses 60s.
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

	mu         sync.Mutex
	seen       map[[32]byte]time.Time // content hash -> last-forwarded time (MeshCore -> Sidepath)
	seenOut    map[string]time.Time   // Sidepath datagram id hex -> last-bridged time (Sidepath -> MeshCore)
	seenRawOut map[[32]byte]time.Time // raw MeshCore packet hash -> last-injected time (Sidepath -> MeshCore)
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
	return &Bridge{
		cfg:        cfg,
		seen:       make(map[[32]byte]time.Time),
		seenOut:    make(map[string]time.Time),
		seenRawOut: make(map[[32]byte]time.Time),
	}
}

func (b *Bridge) logf(format string, args ...any) {
	if b.cfg.Log != nil {
		b.cfg.Log(fmt.Sprintf(format, args...))
	}
}

// maxReconnectDelay caps the exponential backoff used while the backend keeps
// rejecting the subscription (e.g. its radio is unplugged), so a persistent
// failure settles into an occasional retry instead of hammering every 2s.
const maxReconnectDelay = 30 * time.Second

// Run streams packets from the backend, invoking forward for each new packet,
// until ctx is cancelled. It reconnects automatically when the daemon is
// unavailable or the stream drops. Run only returns when ctx is done.
//
// A failing backend would otherwise log the identical error every ReconnectDelay
// forever; instead the delay backs off exponentially up to maxReconnectDelay, the
// first occurrence of each distinct error is logged, and a persistent identical
// error is summarised only occasionally. A successful subscription resets both.
func (b *Bridge) Run(ctx context.Context, forward func(Packet)) error {
	delay := b.cfg.ReconnectDelay
	var lastErr string
	var repeats int
	for {
		connected, err := b.stream(ctx, forward)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if connected {
			// We held a live subscription; reset backoff and error de-dup so the
			// next failure backs off from scratch and is reported immediately.
			delay = b.cfg.ReconnectDelay
			lastErr, repeats = "", 0
		}
		if err != nil {
			if msg := err.Error(); msg != lastErr {
				b.logf("meshcore bridge: %v (retrying in %s)", err, delay)
				lastErr, repeats = msg, 1
			} else {
				repeats++
				// Remind occasionally so a stuck backend isn't silent forever.
				if repeats%30 == 0 {
					b.logf("meshcore bridge: still failing after %d attempts: %v (retrying in %s)", repeats, err, delay)
				}
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		}
		if !connected {
			if delay *= 2; delay > maxReconnectDelay {
				delay = maxReconnectDelay
			}
		}
	}
}

// stream opens one watch_rf subscription and pumps frames until it errors or
// ctx is cancelled. The bool reports whether the subscription was established
// (the ack came back ok) before the error, so the caller can reset its backoff.
func (b *Bridge) stream(ctx context.Context, forward func(Packet)) (bool, error) {
	d := net.Dialer{Timeout: 250 * time.Millisecond}
	conn, err := d.DialContext(ctx, "unix", b.cfg.Socket)
	if err != nil {
		return false, fmt.Errorf("dial %s: %w", b.cfg.Socket, err)
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
		return false, fmt.Errorf("send watch_rf: %w", err)
	}

	dec := json.NewDecoder(bufio.NewReader(conn))

	// First message is the stream-open ack (response{ok}).
	var ack response
	if err := dec.Decode(&ack); err != nil {
		return false, fmt.Errorf("read ack: %w", err)
	}
	if !ack.OK {
		if ack.Error == "" {
			ack.Error = "unknown backend error"
		}
		return false, fmt.Errorf("watch_rf rejected: %s", ack.Error)
	}
	b.logf("meshcore bridge: connected to %s, watching RF packets", b.cfg.Socket)

	// Subsequent messages are bare rfPacket frames. From here on the subscription
	// was live, so a later error is a dropped stream (return connected=true).
	for {
		var pkt rfPacket
		if err := dec.Decode(&pkt); err != nil {
			if ctx.Err() != nil {
				return true, ctx.Err()
			}
			return true, fmt.Errorf("stream closed: %w", err)
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
		if !b.shouldForwardPacket(mesh) {
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
	// ADVERTs are broadcast node announcements — always flood them onto Sidepath so nodes can
	// discover the advertiser, even when MeshCore sent the advert as a DIRECT packet (which has
	// no routable target hash and would otherwise be skipped below).
	if pkt.Type == meshpkt.PayloadAdvert {
		return pkt, ForwardFlood, nil, "", nil
	}
	// Data-bearing direct messages (TXT_MSG etc.) are addressed by a 1-byte dest hash in their
	// payload, INDEPENDENT of the MeshCore route mode: a sender with no known path emits a DM as
	// RouteFlood, but it is still destined for a single node. Resolve Sidepath candidates by that
	// dest hash and deliver directly (the caller floods as a fallback when no candidate matches),
	// rather than blindly flooding every FLOOD-routed DM.
	if payloadCarriesDestHash(pkt.Type) && len(pkt.Payload) > 0 {
		return pkt, ForwardDirect, []byte{pkt.Payload[0]}, "", nil
	}
	switch pkt.Route {
	case meshpkt.RouteFlood, meshpkt.RouteTransportFlood:
		return pkt, ForwardFlood, nil, "", nil
	case meshpkt.RouteDirect, meshpkt.RouteTransportDirect:
		target := directTargetHash(pkt)
		if len(target) == 0 {
			// MeshCore ACKs do not carry the original sender's public-key hash. If a companion has
			// learned a path, it can return DIRECT ACK or DIRECT MULTIPART(ACK); fan those tiny packets
			// back through Sidepath and let recipients match by pending ACK CRC.
			if ackLikePacket(pkt) {
				return pkt, ForwardFlood, nil, "", nil
			}
			return pkt, 0, nil, "direct packet has no routable target hash", nil
		}
		return pkt, ForwardDirect, target, "", nil
	default:
		return pkt, 0, nil, "unsupported route type", nil
	}
}

func directTargetHash(pkt meshpkt.Packet) []byte {
	// Data-bearing direct packets are addressed by the 1-byte destination hash at the start of
	// their payload (e.g. TXT_MSG: [dest_hash:1][src_hash:1][mac:2][ct]). The path hops are the
	// repeaters the packet has already traversed (its trace), NOT the final recipient — so the
	// payload dest hash is the correct routing target and takes priority.
	if payloadCarriesDestHash(pkt.Type) && len(pkt.Payload) > 0 {
		return []byte{pkt.Payload[0]}
	}
	if pkt.Type == meshpkt.PayloadTrace {
		trace, err := meshpkt.DecodeTracePayload(pkt.Payload)
		if err == nil {
			if hashes := trace.RouteHashes(); len(hashes) > 0 {
				return cloneBytes(hashes[0])
			}
		}
	}
	if hops := pkt.Hops(); len(hops) > 0 {
		return cloneBytes(hops[0])
	}
	return nil
}

func ackLikePacket(pkt meshpkt.Packet) bool {
	if pkt.Type == meshpkt.PayloadAck {
		return true
	}
	if pkt.Type != meshpkt.PayloadMultipart {
		return false
	}
	mp, err := meshpkt.DecodeMultipartPayload(pkt.Payload)
	return err == nil && mp.InnerType == meshpkt.PayloadAck && len(mp.InnerPayload) >= 4
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

// shouldForwardDigest returns true if a packet with this content digest has not
// been forwarded within DedupTTL. It also opportunistically prunes expired entries.
func (b *Bridge) shouldForwardDigest(digest [32]byte) bool {
	now := time.Now()

	b.mu.Lock()
	defer b.mu.Unlock()
	for k, t := range b.seen {
		if now.Sub(t) > b.cfg.DedupTTL {
			delete(b.seen, k)
		}
	}
	if t, ok := b.seen[digest]; ok && now.Sub(t) <= b.cfg.DedupTTL {
		return false
	}
	b.seen[digest] = now
	return true
}

// shouldForwardPacket decides whether an inbound MeshCore packet should be forwarded into Sidepath.
// Deduplication keys on the route-independent CoreScope-compatible content digest
// (meshpkt.ContentDigest), so the same logical packet arriving over different routes, paths, or
// transport encodings is forwarded only once. The packet is already decoded by classify, so it is
// not parsed again here. ADVERTs are node announcements and bypass content dedup — repeated adverts
// must keep propagating so nodes stay discoverable.
func (b *Bridge) shouldForwardPacket(pkt meshpkt.Packet) bool {
	if pkt.Type == meshpkt.PayloadAdvert {
		return true
	}
	return b.shouldForwardDigest(meshpkt.ContentDigest(pkt))
}

// shouldBridgeOut returns true the first time a given Sidepath channel datagram (keyed by its id
// hex) is offered for outbound bridging within DedupTTL. This guarantees each Sidepath channel
// message is emitted onto MeshCore at most once, even if it reaches us over multiple Sidepath paths.
func (b *Bridge) shouldBridgeOut(datagramIDHex string) bool {
	now := time.Now()
	b.mu.Lock()
	defer b.mu.Unlock()
	for k, t := range b.seenOut {
		if now.Sub(t) > b.cfg.DedupTTL {
			delete(b.seenOut, k)
		}
	}
	if t, ok := b.seenOut[datagramIDHex]; ok && now.Sub(t) <= b.cfg.DedupTTL {
		return false
	}
	b.seenOut[datagramIDHex] = now
	return true
}

// BridgeChannelOut emits a Sidepath channel message onto the real MeshCore network as a GRP_TXT,
// exactly once per Sidepath datagram. channelPayload is the bare MeshCore GRP_TXT channel_payload
// (hash|mac|ciphertext) carried inside the Sidepath channel datagram — already MeshCore-compatible,
// so it is wrapped verbatim in a flood OTA packet and injected via send_mesh_packet.
//
// Returns bridged=false (with nil error) when this datagram id was already bridged. On success it
// returns the CoreScope-compatible short MeshCore content hash of the emitted packet, used as the
// ACK_BRIDGED correlation id.
func (b *Bridge) BridgeChannelOut(ctx context.Context, datagramIDHex string, channelPayload []byte) (meshHash []byte, bridged bool, err error) {
	if len(channelPayload) == 0 {
		return nil, false, fmt.Errorf("empty channel payload")
	}
	if !b.shouldBridgeOut(datagramIDHex) {
		return nil, false, nil
	}
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Type:    meshpkt.PayloadGrpTxt,
		Route:   meshpkt.RouteFlood,
		Payload: channelPayload,
	})
	if err != nil {
		return nil, false, fmt.Errorf("encode GRP_TXT: %w", err)
	}
	if err := b.SendMeshPacket(ctx, 0, raw); err != nil {
		return nil, false, fmt.Errorf("send_mesh_packet: %w", err)
	}
	hash, err := meshpkt.DecodeContentHash(raw)
	if err != nil {
		return nil, false, fmt.Errorf("compute MeshCore content hash: %w", err)
	}
	return hash[:], true, nil
}

// shouldInjectRaw returns true the first time a given raw MeshCore packet is offered for outbound
// injection within DedupTTL. A Sidepath node's outbound MeshCore packet (e.g. a DM ACK) reaches us
// over multiple Sidepath paths as distinct datagrams carrying identical inner bytes; this guarantees
// we inject it onto the radio at most once.
//
// This is INTENTIONALLY a hash of the exact raw OTA wire bytes (sha256.Sum256(rawMeshBytes)), NOT
// the route-independent logical content hash (meshpkt.ContentHash) used for inbound dedup. Its job
// is to suppress re-injecting the identical byte-for-byte wire packet; two logically-equal packets
// with different routes/paths are distinct wire representations and may legitimately be injected.
func (b *Bridge) shouldInjectRaw(rawMeshBytes []byte) bool {
	h := sha256.Sum256(rawMeshBytes)
	now := time.Now()
	b.mu.Lock()
	defer b.mu.Unlock()
	for k, t := range b.seenRawOut {
		if now.Sub(t) > b.cfg.DedupTTL {
			delete(b.seenRawOut, k)
		}
	}
	if t, ok := b.seenRawOut[h]; ok && now.Sub(t) <= b.cfg.DedupTTL {
		return false
	}
	b.seenRawOut[h] = now
	return true
}

// BridgeRawOut injects an opaque, fully-formed MeshCore OTA packet that originated on the Sidepath
// mesh (e.g. an ACK a Sidepath node built for a received MeshCore DM) onto the real MeshCore radio,
// exactly once per identical packet within DedupTTL. The bytes are passed verbatim to
// send_mesh_packet; the bridge does not decode or alter them.
//
// Returns injected=false (nil error) when this packet was already injected recently.
func (b *Bridge) BridgeRawOut(ctx context.Context, rawMeshBytes []byte) (injected bool, err error) {
	if len(rawMeshBytes) == 0 {
		return false, fmt.Errorf("empty MeshCore packet")
	}
	if !b.shouldInjectRaw(rawMeshBytes) {
		return false, nil
	}
	if err := b.SendMeshPacket(ctx, 0, rawMeshBytes); err != nil {
		return false, fmt.Errorf("send_mesh_packet: %w", err)
	}
	return true, nil
}

// SendMeshPacket injects an opaque, fully-formed MeshCore OTA packet onto the radio via the
// meshcore-go backend's send_mesh_packet IPC. It dials a short-lived request/response connection
// (separate from the long-lived watch_rf stream).
func (b *Bridge) SendMeshPacket(ctx context.Context, priority byte, pkt []byte) error {
	d := net.Dialer{Timeout: 250 * time.Millisecond}
	conn, err := d.DialContext(ctx, "unix", b.cfg.Socket)
	if err != nil {
		return fmt.Errorf("dial %s: %w", b.cfg.Socket, err)
	}
	defer conn.Close()

	params, err := json.Marshal(sendMeshPacketParams{Priority: priority, Packet: pkt})
	if err != nil {
		return err
	}
	req := request{ID: 1, Device: b.cfg.Device, Method: "send_mesh_packet", Params: params}
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return fmt.Errorf("send request: %w", err)
	}

	var resp response
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&resp); err != nil {
		return fmt.Errorf("read response: %w", err)
	}
	if !resp.OK {
		if resp.Error == "" {
			resp.Error = "unknown backend error"
		}
		return fmt.Errorf("send_mesh_packet rejected: %s", resp.Error)
	}
	return nil
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
