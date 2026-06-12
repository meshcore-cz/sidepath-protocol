package meshcore

import (
	"crypto/sha256"
	"strings"
	"testing"
	"time"

	"github.com/meshcore-cz/meshpkt"
)

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

func TestClassifyDirectPacketWithRouteHash(t *testing.T) {
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
	if mode != ForwardDirect || string(target) != string([]byte{0x12, 0x34}) || reason != "" {
		t.Fatalf("classify direct got mode=%v target=%x reason=%q", mode, target, reason)
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

func TestShouldForwardDedup(t *testing.T) {
	b := New(Config{DedupTTL: time.Hour})
	packet := []byte{0x11, 1, 2, 3}
	other := []byte{0x11, 9, 9, 9}

	if !b.shouldForward(packet) {
		t.Fatal("first sighting should forward")
	}
	if b.shouldForward(packet) {
		t.Fatal("identical packet within TTL should be suppressed")
	}
	if !b.shouldForward(other) {
		t.Fatal("a different packet should forward")
	}

	// Expire the dedup entry and confirm it forwards again.
	h := sha256.Sum256(packet)
	b.seen[h] = time.Now().Add(-2 * time.Hour)
	if !b.shouldForward(packet) {
		t.Fatal("packet past TTL should forward again")
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
	if !b.shouldForwardPacket(pkt, raw) {
		t.Fatal("first advert should forward")
	}
	if !b.shouldForwardPacket(pkt, raw) {
		t.Fatal("duplicate advert should bypass content dedup")
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
