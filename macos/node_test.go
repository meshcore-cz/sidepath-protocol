//go:build darwin

package macos

import (
	"testing"

	"github.com/meshcore-cz/sidepath-protocol/core"
)

func testIdentity(offset byte) *core.Identity {
	var seed [core.SeedSize]byte
	for i := range seed {
		seed[i] = byte(i) + offset
	}
	return core.IdentityFromSeed(seed)
}

func singleFrameDatagram(t *testing.T, dg core.Datagram) []byte {
	t.Helper()
	data, err := dg.Encode()
	if err != nil {
		t.Fatalf("encode datagram: %v", err)
	}
	frames := core.FragmentDatagramNew(data, core.MaxFrameSize)
	if len(frames) != 1 {
		t.Fatalf("expected single frame, got %d", len(frames))
	}
	return frames[0].Encode()
}

func TestDirectInboundPacketIncrementsRXForOutboundPeer(t *testing.T) {
	local := testIdentity(1)
	peer := testIdentity(2)
	n := New(Config{Identity: local})
	n.peers["addr-1"] = &MacPeerLink{peerID: peer.NodeID(), addr: "addr-1"}

	raw := singleFrameDatagram(t, core.Datagram{
		Version:     core.DatagramVersion,
		ID:          core.NewDatagramID(),
		Source:      peer.NodeID(),
		Destination: local.NodeID(),
		TTL:         1,
		Protocol:    core.ProtocolSidepathChat,
		Payload:     []byte("trace-ish response"),
	})

	n.handleIncomingFrameForAddr("addr-1", raw)

	rx, _, lastRX := n.PacketStats(peer.NodeID())
	if rx != 1 {
		t.Fatalf("rx packets = %d, want 1", rx)
	}
	if lastRX.IsZero() {
		t.Fatal("last RX was not recorded")
	}
}

func TestDirectPeerFallbackIncrementsRXWhenAddressMappingIsMissing(t *testing.T) {
	local := testIdentity(3)
	peer := testIdentity(4)
	n := New(Config{Identity: local})

	raw := singleFrameDatagram(t, core.Datagram{
		Version:     core.DatagramVersion,
		ID:          core.NewDatagramID(),
		Source:      peer.NodeID(),
		Destination: local.NodeID(),
		TTL:         1,
		Protocol:    core.ProtocolSidepathChat,
		Payload:     []byte("trace-ish response"),
	})

	n.handleIncomingFrame(raw, nil, "unmapped-addr")

	rx, _, lastRX := n.PacketStats(peer.NodeID())
	if rx != 1 {
		t.Fatalf("rx packets = %d, want 1", rx)
	}
	if lastRX.IsZero() {
		t.Fatal("last RX was not recorded")
	}
}
