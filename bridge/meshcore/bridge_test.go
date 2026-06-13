package meshcore

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/meshcore-cz/meshpkt"
)

func hexBytes(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("bad hex %q: %v", s, err)
	}
	return b
}

func decodePkt(t *testing.T, hexStr string) meshpkt.Packet {
	t.Helper()
	pkt, err := meshpkt.DecodePacket(hexBytes(t, hexStr))
	if err != nil {
		t.Fatalf("DecodePacket(%s): %v", hexStr, err)
	}
	return pkt
}

func TestClassifyFloodPacket(t *testing.T) {
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteFlood,
		Type:         meshpkt.PayloadGrpTxt,
		PathHashSize: 2,
		Payload:      []byte{0xaa, 0xbb, 0xcc},
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	pkt, mode, target, reason, err := classify(raw)
	if err != nil {
		t.Fatalf("classify: %v", err)
	}
	if mode != ForwardFlood || len(target) != 0 || reason != "" {
		t.Fatalf("classify flood got mode=%v target=%x reason=%q", mode, target, reason)
	}
	if pkt.Type != meshpkt.PayloadGrpTxt {
		t.Fatalf("type=%s", pkt.Type)
	}
}

// A TXT_MSG is addressed by its payload dest hash even when a path (trace of traversed repeaters)
// is present — the path hops are NOT the recipient, so the dest hash takes priority.
func TestClassifyDirectTxtMsgRoutesByDestHashNotPath(t *testing.T) {
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteDirect,
		Type:         meshpkt.PayloadTxtMsg,
		PathHashSize: 2,
		Path:         []byte{0x12, 0x34, 0x56, 0x78},
		Payload:      []byte{0xab, 0xcd, 0, 0},
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	_, mode, target, reason, err := classify(raw)
	if err != nil {
		t.Fatalf("classify: %v", err)
	}
	if mode != ForwardDirect || string(target) != string([]byte{0xab}) || reason != "" {
		t.Fatalf("classify direct got mode=%v target=%x reason=%q (want dest hash ab)", mode, target, reason)
	}
}

// A DM with no known MeshCore path is sent RouteFlood, but it still carries a payload dest hash and
// is destined for a single node — classify must route it directly (caller floods as a fallback).
func TestClassifyFloodTxtMsgRoutesByDestHash(t *testing.T) {
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteFlood,
		Type:         meshpkt.PayloadTxtMsg,
		PathHashSize: 2,
		Payload:      []byte{0xbe, 0x72, 0, 0},
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	_, mode, target, reason, err := classify(raw)
	if err != nil {
		t.Fatalf("classify: %v", err)
	}
	if mode != ForwardDirect || string(target) != string([]byte{0xbe}) || reason != "" {
		t.Fatalf("classify flood txt got mode=%v target=%x reason=%q (want dest hash be)", mode, target, reason)
	}
}

func TestClassifyDirectPacketWithPayloadDestHash(t *testing.T) {
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteDirect,
		Type:         meshpkt.PayloadTxtMsg,
		PathHashSize: 2,
		Payload:      []byte{0xab, 0xcd, 0, 0},
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	_, mode, target, reason, err := classify(raw)
	if err != nil {
		t.Fatalf("classify: %v", err)
	}
	if mode != ForwardDirect || string(target) != string([]byte{0xab}) || reason != "" {
		t.Fatalf("classify direct got mode=%v target=%x reason=%q", mode, target, reason)
	}
}

func TestClassifyDirectPacketWithoutTargetHash(t *testing.T) {
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteDirect,
		Type:         meshpkt.PayloadAck,
		PathHashSize: 2,
		Payload:      []byte{1, 2, 3, 4},
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	_, mode, target, reason, err := classify(raw)
	if err != nil {
		t.Fatalf("classify: %v", err)
	}
	if mode != 0 || len(target) != 0 || reason == "" {
		t.Fatalf("classify direct got mode=%v target=%x reason=%q", mode, target, reason)
	}
}

func TestClassifyDirectAdvertIsFlooded(t *testing.T) {
	// A DIRECT-routed ADVERT has no routable target hash, but adverts are broadcast node
	// announcements and must still be flooded onto BLEEdge for discovery.
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteDirect,
		Type:         meshpkt.PayloadAdvert,
		PathHashSize: 2,
		Payload:      []byte{1, 2, 3, 4},
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	_, mode, target, reason, err := classify(raw)
	if err != nil {
		t.Fatalf("classify: %v", err)
	}
	if mode != ForwardFlood || len(target) != 0 || reason != "" {
		t.Fatalf("classify direct advert got mode=%v target=%x reason=%q", mode, target, reason)
	}
}

// Inbound dedup keys on the route-independent CoreScope content digest: route variants of the same
// logical packet forward only once, a different payload forwards, and an expired entry forwards again.
func TestShouldForwardDedupByContentDigest(t *testing.T) {
	b := New(Config{DedupTTL: time.Hour})

	// Three route variants of the same logical GRP_TXT payload (aabbcc): FLOOD, DIRECT w/ path, and
	// TRANSPORT_FLOOD w/ transport codes.
	flood := decodePkt(t, "1500aabbcc")
	direct := decodePkt(t, "164211223344aabbcc")
	transport := decodePkt(t, "140102030400aabbcc")

	if !b.shouldForwardPacket(flood) {
		t.Fatal("first sighting should forward")
	}
	if b.shouldForwardPacket(direct) {
		t.Fatal("DIRECT route variant of the same content must be suppressed")
	}
	if b.shouldForwardPacket(transport) {
		t.Fatal("TRANSPORT route variant of the same content must be suppressed")
	}

	// A different logical payload forwards.
	if !b.shouldForwardPacket(decodePkt(t, "1500aabbcd")) {
		t.Fatal("a different content should forward")
	}

	// Expire the content-hash entry and confirm it forwards again.
	b.seen[meshpkt.ContentDigest(flood)] = time.Now().Add(-2 * time.Hour)
	if !b.shouldForwardPacket(flood) {
		t.Fatal("content past TTL should forward again")
	}
}

func TestClassifyAdvertBypassesForwardDedup(t *testing.T) {
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteFlood,
		Type:         meshpkt.PayloadAdvert,
		PathHashSize: 2,
		Payload:      []byte{1, 2, 3, 4},
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	pkt, mode, _, _, err := classify(raw)
	if err != nil {
		t.Fatalf("classify: %v", err)
	}
	if pkt.Type != meshpkt.PayloadAdvert || mode != ForwardFlood {
		t.Fatalf("classify advert got type=%s mode=%v", pkt.Type, mode)
	}

	b := New(Config{DedupTTL: time.Hour})
	if !b.shouldForwardPacket(pkt) {
		t.Fatal("first advert should forward")
	}
	if !b.shouldForwardPacket(pkt) {
		t.Fatal("duplicate advert should bypass content dedup")
	}
}

// BridgeChannelOut returns the CoreScope-compatible short (8-byte) MeshCore content hash of the
// emitted GRP_TXT packet. Uses a fake meshcore-go backend socket that acks every request.
func TestBridgeChannelOutReturnsContentHash(t *testing.T) {
	// Short dir: macOS caps unix socket paths at ~104 bytes, and t.TempDir() embeds the long test name.
	dir, err := os.MkdirTemp("", "mcb")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	sock := filepath.Join(dir, "b.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = bufio.NewReader(c).ReadBytes('\n')
				_, _ = c.Write([]byte("{\"id\":1,\"ok\":true}\n"))
			}(conn)
		}
	}()

	b := New(Config{Socket: sock, DedupTTL: time.Minute})
	cp := hexBytes(t, "11aabbccddeeff0011223344")
	hash, bridged, err := b.BridgeChannelOut(context.Background(), "dg-1", cp)
	if err != nil {
		t.Fatalf("BridgeChannelOut: %v", err)
	}
	if !bridged {
		t.Fatal("expected bridged=true on first emit")
	}
	if len(hash) != 8 {
		t.Fatalf("hash len = %d, want 8", len(hash))
	}

	// It must equal the shared content hash of the GRP_TXT packet that was emitted.
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{Type: meshpkt.PayloadGrpTxt, Route: meshpkt.RouteFlood, Payload: cp})
	if err != nil {
		t.Fatal(err)
	}
	want, err := meshpkt.DecodeContentHash(raw)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(hash, want[:]) {
		t.Fatalf("hash = %x, want CoreScope content hash %x", hash, want)
	}
}

func TestAdvertSummaryIncludesDecodedFields(t *testing.T) {
	advPayload, err := meshpkt.EncodeAdvertPayload(meshpkt.Advert{
		PublicKey: bytesOf(0xab, 32),
		Timestamp: time.Unix(1_781_273_821, 0).UTC(),
		NodeType:  meshpkt.AdvertNodeRepeater,
		HasGPS:    true,
		Lat:       50.087451,
		Lon:       14.420671,
		HasFeat1:  true,
		Feature1:  0x1234,
		Name:      "Repeater1",
	})
	if err != nil {
		t.Fatalf("EncodeAdvertPayload: %v", err)
	}
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteFlood,
		Type:         meshpkt.PayloadAdvert,
		PathHashSize: 2,
		Payload:      advPayload,
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	pkt, err := meshpkt.DecodePacket(raw)
	if err != nil {
		t.Fatalf("DecodePacket: %v", err)
	}

	got := advertSummary(Packet{Bytes: raw, Mesh: pkt, Mode: ForwardFlood, RSSI: -87, SNR: 6.5})
	for _, want := range []string{
		`ADVERT name="Repeater1"`,
		"nodeType=repeater",
		"pub=abababababababab",
		"ts=2026-06-12T14:17:01Z",
		"sig=unsigned",
		"gps=50.087451,14.420671",
		"feat1=0x1234",
		"rssi=-87 snr=6.5",
	} {
		if !strings.Contains(got, want) {
			t.Fatalf("advertSummary missing %q in:\n%s", want, got)
		}
	}
}

func TestAdvertSummaryIncludesDecodeError(t *testing.T) {
	raw, err := meshpkt.EncodePacket(meshpkt.Packet{
		Route:        meshpkt.RouteFlood,
		Type:         meshpkt.PayloadAdvert,
		PathHashSize: 2,
		Payload:      []byte{1, 2, 3},
	})
	if err != nil {
		t.Fatalf("EncodePacket: %v", err)
	}
	pkt, err := meshpkt.DecodePacket(raw)
	if err != nil {
		t.Fatalf("DecodePacket: %v", err)
	}
	got := advertSummary(Packet{Bytes: raw, Mesh: pkt, Mode: ForwardFlood})
	if !strings.Contains(got, "ADVERT decode-error=") || !strings.Contains(got, "type=ADVERT") {
		t.Fatalf("advertSummary should include decode error and packet summary, got:\n%s", got)
	}
}

func bytesOf(v byte, n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = v
	}
	return b
}

func TestShouldBridgeOutDedup(t *testing.T) {
	b := New(Config{DedupTTL: time.Minute})
	if !b.shouldBridgeOut("aabbcc") {
		t.Fatal("first sight should bridge")
	}
	if b.shouldBridgeOut("aabbcc") {
		t.Fatal("same datagram id must not bridge twice")
	}
	if !b.shouldBridgeOut("ddeeff") {
		t.Fatal("a different datagram id should bridge")
	}
}

func TestShouldInjectRawDedup(t *testing.T) {
	b := New(Config{DedupTTL: time.Minute})
	packet := []byte{0x10, 0x20, 0x30, 0x40}
	other := []byte{0x10, 0x20, 0x30, 0x41}
	if !b.shouldInjectRaw(packet) {
		t.Fatal("first sight should inject")
	}
	if b.shouldInjectRaw(packet) {
		t.Fatal("identical raw packet must not inject twice")
	}
	if !b.shouldInjectRaw(other) {
		t.Fatal("a different raw packet should inject")
	}
}
