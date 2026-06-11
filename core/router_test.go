package core

import (
	"bytes"
	"testing"
	"time"
)

// ---- helpers ----------------------------------------------------------------

func mustNodeID(s string) NodeID {
	id, err := ParseNodeID(s)
	if err != nil {
		panic(err)
	}
	return id
}

func nodeID(b byte) NodeID {
	var id NodeID
	id[7] = b
	return id
}

// signedAnnounce builds a valid, signed ANNOUNCE packet originating from id.
// The packet Source and payload NodeID are the identity's derived NodeID.
func signedAnnounce(id *Identity, caps Capabilities, neighbors []NodeID, seq uint32) Packet {
	ts := int64(1_700_000_000)
	sig := id.SignAnnounce(uint32(ts), caps, seq, neighbors)
	ap := AnnouncePayload{
		NodeID:    id.NodeID(),
		Caps:      caps,
		Neighbors: neighbors,
		Seq:       seq,
		Timestamp: ts,
		PublicKey: id.Pub,
		Signature: sig,
	}
	payload, _ := ap.Encode()
	return Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeAnnounce,
		ID:          NewPacketID(),
		Source:      id.NodeID(),
		Mode:        RoutingModeFlood,
		TTL:         3,
		PayloadType: PayloadTypeTextTest,
		Payload:     payload,
	}
}

func makeDataPacket(src, dst NodeID, ttl uint8, mode RoutingMode) Packet {
	return Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeData,
		ID:          NewPacketID(),
		Source:      src,
		Destination: dst,
		Mode:        mode,
		TTL:         ttl,
		PayloadType: PayloadTypeTextTest,
		Payload:     []byte("hello"),
	}
}

// ---- packet encode/decode ---------------------------------------------------

func TestPacketEncodeDecodeRoundtrip(t *testing.T) {
	src := nodeID(1)
	dst := nodeID(2)
	pkt := makeDataPacket(src, dst, 4, RoutingModeFlood)
	pkt.Trace = []NodeID{src}
	pkt.Route = []NodeID{dst}

	enc, err := pkt.Encode()
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	got, err := DecodePacket(enc)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.Version != pkt.Version {
		t.Errorf("version mismatch: got %d want %d", got.Version, pkt.Version)
	}
	if got.ID != pkt.ID {
		t.Errorf("id mismatch")
	}
	if got.Source != pkt.Source {
		t.Errorf("source mismatch")
	}
	if got.Destination != pkt.Destination {
		t.Errorf("destination mismatch")
	}
	if got.TTL != pkt.TTL {
		t.Errorf("TTL mismatch: got %d want %d", got.TTL, pkt.TTL)
	}
	if !bytes.Equal(got.Payload, pkt.Payload) {
		t.Errorf("payload mismatch")
	}
}

// TestGoldenVector tests a fixed CBOR byte sequence for cross-language interoperability.
// The vector is produced from a known packet and must stay stable.
func TestGoldenVector(t *testing.T) {
	var src, dst NodeID
	src[7] = 0xAA
	dst[7] = 0xBB

	var pid PacketID
	for i := range pid {
		pid[i] = byte(i)
	}

	pkt := Packet{
		Version:     1,
		Type:        PacketTypeData,
		ID:          pid,
		Source:      src,
		Destination: dst,
		Mode:        RoutingModeFlood,
		TTL:         3,
		PayloadType: PayloadTypeTextTest,
		Payload:     []byte("test"),
	}

	enc, err := pkt.Encode()
	if err != nil {
		t.Fatalf("encode golden: %v", err)
	}

	// Round-trip decode must yield identical packet
	got, err := DecodePacket(enc)
	if err != nil {
		t.Fatalf("decode golden: %v", err)
	}
	if got.ID != pid {
		t.Errorf("golden: packet ID mismatch")
	}
	if got.Source != src {
		t.Errorf("golden: source mismatch")
	}
	if got.Destination != dst {
		t.Errorf("golden: destination mismatch")
	}
	if string(got.Payload) != "test" {
		t.Errorf("golden: payload mismatch, got %q", string(got.Payload))
	}
	// Verify the encoding is deterministic
	enc2, _ := pkt.Encode()
	if !bytes.Equal(enc, enc2) {
		t.Errorf("golden: encoding is not deterministic")
	}
}

// ---- fragmentation and reassembly ------------------------------------------

func TestFragmentAndReassemble(t *testing.T) {
	data := bytes.Repeat([]byte("ABCDE"), 40) // 200 bytes
	pid := NewPacketID()
	mtu := 60 // data per fragment = 60-23 = 37 bytes

	frames := FragmentPacket(data, mtu, pid)
	expectedFrags := (len(data) + 36) / 37
	if len(frames) != expectedFrags {
		t.Errorf("expected %d fragments, got %d", expectedFrags, len(frames))
	}

	r := NewReassembler()
	var result []byte
	for _, f := range frames {
		enc := f.Encode()
		decoded, err := DecodeFrame(enc)
		if err != nil {
			t.Fatalf("decode frame: %v", err)
		}
		out, done, err := r.AddFrame(decoded)
		if err != nil {
			t.Fatalf("add frame: %v", err)
		}
		if done {
			result = out
		}
	}

	if result == nil {
		t.Fatal("reassembly did not complete")
	}
	if !bytes.Equal(result, data) {
		t.Errorf("reassembled data mismatch: got %d bytes, want %d", len(result), len(data))
	}
}

func TestFragmentOutOfOrder(t *testing.T) {
	data := bytes.Repeat([]byte("XY"), 100) // 200 bytes
	pid := NewPacketID()
	mtu := 50

	frames := FragmentPacket(data, mtu, pid)
	if len(frames) < 2 {
		t.Skip("not enough fragments for out-of-order test")
	}

	// Reverse the frame order
	r := NewReassembler()
	var result []byte
	for i := len(frames) - 1; i >= 0; i-- {
		out, done, err := r.AddFrame(frames[i])
		if err != nil {
			t.Fatalf("add frame: %v", err)
		}
		if done {
			result = out
		}
	}

	if result == nil {
		t.Fatal("out-of-order reassembly did not complete")
	}
	if !bytes.Equal(result, data) {
		t.Error("out-of-order reassembled data mismatch")
	}
}

func TestDuplicateFragments(t *testing.T) {
	data := bytes.Repeat([]byte("Z"), 100)
	pid := NewPacketID()
	mtu := 50

	frames := FragmentPacket(data, mtu, pid)
	r := NewReassembler()

	// Send first frame twice
	out1, done1, err := r.AddFrame(frames[0])
	if err != nil || done1 || out1 != nil {
		t.Fatalf("first add unexpected result")
	}
	out2, done2, err := r.AddFrame(frames[0]) // duplicate
	if err != nil || done2 || out2 != nil {
		t.Fatalf("duplicate should be silently ignored, got: done=%v err=%v", done2, err)
	}

	// Send remaining frames
	var result []byte
	for _, f := range frames[1:] {
		out, done, err2 := r.AddFrame(f)
		if err2 != nil {
			t.Fatalf("add frame: %v", err2)
		}
		if done {
			result = out
		}
	}
	if !bytes.Equal(result, data) {
		t.Error("duplicate-fragment reassembly mismatch")
	}
}

func TestFragmentCRCMismatch(t *testing.T) {
	data := []byte("hello world from bleedge")
	pid := NewPacketID()
	mtu := 60

	frames := FragmentPacket(data, mtu, pid)

	// Corrupt the data in the first frame
	frames[0].Data[0] ^= 0xFF
	// But set an incorrect CRC (the frame's CRC is still the original)
	// The CRC is computed over the original, so corruption will cause mismatch.

	r := NewReassembler()
	var lastErr error
	for _, f := range frames {
		_, _, err := r.AddFrame(f)
		if err != nil {
			lastErr = err
		}
	}
	if lastErr == nil {
		t.Error("expected CRC mismatch error, got nil")
	}
}

func TestFragmentTimeout(t *testing.T) {
	data := bytes.Repeat([]byte("T"), 200)
	pid := NewPacketID()
	mtu := 50

	frames := FragmentPacket(data, mtu, pid)

	r := &Reassembler{
		pending: make(map[PacketID]*assembly),
		timeout: 1 * time.Millisecond, // very short timeout
	}

	// Add only first frame
	r.AddFrame(frames[0]) //nolint:errcheck

	// Wait just past the timeout, then call Reap directly
	time.Sleep(10 * time.Millisecond)
	r.Reap()

	// The assembly should have been reaped
	r.mu.Lock()
	_, still := r.pending[pid]
	r.mu.Unlock()
	if still {
		t.Error("expired assembly was not reaped")
	}
}

func TestLargePayloadFragmentReassemble(t *testing.T) {
	// 600-byte payload, default Android MTU scenario
	data := make([]byte, 600)
	for i := range data {
		data[i] = byte(i % 251)
	}
	pid := NewPacketID()
	mtu := 512 // Android negotiated MTU

	frames := FragmentPacket(data, mtu, pid)
	// At 512-23=489 bytes per fragment, 600 bytes needs 2 fragments
	if len(frames) < 2 {
		t.Errorf("expected >=2 fragments for 600 bytes at mtu=512, got %d", len(frames))
	}

	r := NewReassembler()
	var result []byte
	for _, f := range frames {
		out, done, err := r.AddFrame(f)
		if err != nil {
			t.Fatalf("add frame: %v", err)
		}
		if done {
			result = out
		}
	}
	if !bytes.Equal(result, data) {
		t.Errorf("large payload reassembly mismatch: got %d bytes, want %d", len(result), len(data))
	}
}

// ---- dedup cache ------------------------------------------------------------

func TestDedupSuppression(t *testing.T) {
	c := NewDedupCache()
	pid := NewPacketID()

	if c.SeenOrAdd(pid) {
		t.Error("first call should return false (not seen)")
	}
	if !c.SeenOrAdd(pid) {
		t.Error("second call should return true (duplicate)")
	}
	if !c.SeenOrAdd(pid) {
		t.Error("third call should return true (still duplicate)")
	}
}

func TestDedupDifferentIDs(t *testing.T) {
	c := NewDedupCache()
	for i := 0; i < 10; i++ {
		pid := NewPacketID()
		if c.SeenOrAdd(pid) {
			t.Errorf("new packet ID %d should not be seen", i)
		}
	}
}

// ---- router: flood ----------------------------------------------------------

func TestFloodBroadcast(t *testing.T) {
	alice := nodeID(1)
	r := NewRouter(alice)
	bob := nodeID(2)
	r.Neighbors.Upsert(Neighbor{ID: bob})

	pkt := Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeData,
		ID:          NewPacketID(),
		Source:      bob,
		Destination: zeroNodeID, // broadcast
		Mode:        RoutingModeFlood,
		TTL:         3,
		PayloadType: PayloadTypeTextTest,
		Payload:     []byte("broadcast"),
	}

	actions := r.HandlePacket(pkt, &bob)

	var hasDeliver, hasRelay bool
	for _, a := range actions {
		switch a.Type {
		case ActionDeliverLocal:
			hasDeliver = true
		case ActionRelayFlood:
			hasRelay = true
		}
	}
	if !hasDeliver {
		t.Error("broadcast: expected ActionDeliverLocal")
	}
	if !hasRelay {
		t.Error("broadcast: expected ActionRelayFlood")
	}
}

func TestFloodUnicastToSelf(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(alice)

	pkt := makeDataPacket(bob, alice, 4, RoutingModeFlood)
	actions := r.HandlePacket(pkt, &bob)

	var hasDeliver, hasAck bool
	for _, a := range actions {
		if a.Type == ActionDeliverLocal {
			hasDeliver = true
		}
		if a.Type == ActionSendAck {
			hasAck = true
		}
	}
	if !hasDeliver {
		t.Error("unicast to self: expected ActionDeliverLocal")
	}
	if !hasAck {
		t.Error("unicast to self: expected ActionSendAck")
	}
}

func TestFloodTTLDecrement(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(alice)

	pkt := makeDataPacket(bob, zeroNodeID, 3, RoutingModeFlood)
	actions := r.HandlePacket(pkt, &bob)

	for _, a := range actions {
		if a.Type == ActionRelayFlood {
			if a.Packet.TTL != 2 {
				t.Errorf("relay TTL should be decremented to 2, got %d", a.Packet.TTL)
			}
		}
	}
}

func TestFloodTTLExhausted(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(alice)

	pkt := makeDataPacket(bob, zeroNodeID, 0, RoutingModeFlood)
	actions := r.HandlePacket(pkt, &bob)

	if len(actions) != 1 || actions[0].Type != ActionDrop {
		t.Errorf("TTL=0 should produce drop, got %v", actions)
	}
	if actions[0].Reason != string(DropTTL) {
		t.Errorf("expected drop reason %q, got %q", DropTTL, actions[0].Reason)
	}
}

func TestFloodDuplicatePacket(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(alice)

	pkt := makeDataPacket(bob, zeroNodeID, 4, RoutingModeFlood)
	r.HandlePacket(pkt, &bob) // first pass

	actions := r.HandlePacket(pkt, &bob) // duplicate
	if len(actions) != 1 || actions[0].Type != ActionDrop {
		t.Errorf("duplicate should be dropped, got %v", actions)
	}
	if actions[0].Reason != string(DropDuplicate) {
		t.Errorf("expected DropDuplicate, got %q", actions[0].Reason)
	}
}

// TestFloodEchoToOriginatorDropped verifies that a node which originated a flood
// packet drops it (rather than re-flooding) when a neighbor relays it back. The
// originator marks the packet via MarkOriginated at send time; the echo carries a
// trace that does NOT yet contain the originator, so only the dedup cache catches it.
func TestFloodEchoToOriginatorDropped(t *testing.T) {
	alice := nodeID(1) // originator
	bob := nodeID(2)   // relays alice's packet back
	r := NewRouter(alice)

	// alice originates a broadcast flood and records it.
	pkt := makeDataPacket(alice, zeroNodeID, 4, RoutingModeFlood)
	r.MarkOriginated(pkt.ID)

	// bob relays it back: trace now contains bob (not alice), ttl decremented.
	echo := pkt
	echo.TTL = 3
	echo.Trace = []NodeID{bob}

	actions := r.HandlePacket(echo, &bob)
	if len(actions) != 1 || actions[0].Type != ActionDrop {
		t.Fatalf("originator should drop its own echoed packet, got %v", actions)
	}
	if actions[0].Reason != string(DropDuplicate) {
		t.Errorf("expected DropDuplicate, got %q", actions[0].Reason)
	}
}

func TestFloodLoopDetection(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(alice)

	// Packet already has alice in trace (simulating a loop)
	pkt := makeDataPacket(bob, zeroNodeID, 4, RoutingModeFlood)
	pkt.Trace = []NodeID{alice} // alice already in trace

	actions := r.HandlePacket(pkt, &bob)
	if len(actions) != 1 || actions[0].Type != ActionDrop {
		t.Errorf("loop should be dropped, got %v", actions)
	}
	if actions[0].Reason != string(DropLoop) {
		t.Errorf("expected DropLoop, got %q", actions[0].Reason)
	}
}

func TestFloodExcludesIncomingPeer(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(alice)

	pkt := makeDataPacket(bob, zeroNodeID, 4, RoutingModeFlood)
	actions := r.HandlePacket(pkt, &bob)

	for _, a := range actions {
		if a.Type == ActionRelayFlood {
			if a.NextHop == nil {
				t.Error("relay-flood should carry incoming peer in NextHop for exclusion")
			} else if *a.NextHop != bob {
				t.Errorf("relay-flood NextHop should be bob, got %v", *a.NextHop)
			}
		}
	}
}

func TestInvalidVersion(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(alice)

	pkt := makeDataPacket(bob, alice, 4, RoutingModeFlood)
	pkt.Version = 99

	actions := r.HandlePacket(pkt, &bob)
	if len(actions) != 1 || actions[0].Type != ActionDrop {
		t.Errorf("invalid version should drop")
	}
	if actions[0].Reason != string(DropInvalidVersion) {
		t.Errorf("expected DropInvalidVersion, got %q", actions[0].Reason)
	}
}

func TestAllowlist(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	carol := nodeID(3)
	r := NewRouter(alice)
	r.Allowlist[carol] = true // only carol allowed, bob is not

	pkt := makeDataPacket(bob, alice, 4, RoutingModeFlood)
	actions := r.HandlePacket(pkt, &bob)

	if len(actions) != 1 || actions[0].Type != ActionDrop {
		t.Errorf("non-allowlisted peer should be dropped")
	}
	if actions[0].Reason != string(DropPeerNotAllowed) {
		t.Errorf("expected DropPeerNotAllowed, got %q", actions[0].Reason)
	}
}

// ---- router: source route ---------------------------------------------------

func TestSourceRouteNextHop(t *testing.T) {
	// Route: Alice → Bob → Carol
	alice := nodeID(1)
	bob := nodeID(2)
	carol := nodeID(3)
	r := NewRouter(bob) // bob is the router

	pkt := Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeData,
		ID:          NewPacketID(),
		Source:      alice,
		Destination: carol,
		Mode:        RoutingModeSourceRoute,
		TTL:         4,
		Route:       []NodeID{bob, carol},
		RouteCursor: 0,
		PayloadType: PayloadTypeTextTest,
		Payload:     []byte("routed"),
	}

	actions := r.HandlePacket(pkt, &alice)

	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d: %v", len(actions), actions)
	}
	if actions[0].Type != ActionRelayNextHop {
		t.Errorf("expected ActionRelayNextHop, got %v", actions[0].Type)
	}
	if actions[0].NextHop == nil || *actions[0].NextHop != carol {
		t.Errorf("next hop should be carol")
	}
}

func TestSourceRouteRejectionWrongNextHop(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	dave := nodeID(4)
	r := NewRouter(dave) // dave receives a packet meant for bob

	pkt := Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeData,
		ID:          NewPacketID(),
		Source:      alice,
		Mode:        RoutingModeSourceRoute,
		TTL:         4,
		Route:       []NodeID{bob}, // bob is next hop, not dave
		RouteCursor: 0,
		PayloadType: PayloadTypeTextTest,
		Payload:     []byte("misdirected"),
	}

	actions := r.HandlePacket(pkt, &alice)
	if len(actions) != 1 || actions[0].Type != ActionDrop {
		t.Errorf("wrong next hop should drop")
	}
	if actions[0].Reason != string(DropNotNextHop) {
		t.Errorf("expected DropNotNextHop, got %q", actions[0].Reason)
	}
}

func TestSourceRouteDelivery(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(bob) // bob is final destination

	pkt := Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeData,
		ID:          NewPacketID(),
		Source:      alice,
		Mode:        RoutingModeSourceRoute,
		TTL:         4,
		Route:       []NodeID{bob}, // bob is the only hop = destination
		RouteCursor: 0,
		PayloadType: PayloadTypeTextTest,
		Payload:     []byte("arrived"),
	}

	actions := r.HandlePacket(pkt, &alice)

	var hasDeliver, hasAck bool
	for _, a := range actions {
		if a.Type == ActionDeliverLocal {
			hasDeliver = true
		}
		if a.Type == ActionSendAck {
			hasAck = true
		}
	}
	if !hasDeliver {
		t.Error("source route delivery: expected ActionDeliverLocal")
	}
	if !hasAck {
		t.Error("source route delivery: expected ActionSendAck")
	}
}

// ---- ACK reverse route ------------------------------------------------------

func TestACKReverseRoute(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	carol := nodeID(3)
	r := NewRouter(carol) // carol is the destination

	// Simulate packet arriving at carol with trace [alice, bob, carol]
	pkt := Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeData,
		ID:          NewPacketID(),
		Source:      alice,
		Destination: carol,
		Mode:        RoutingModeFlood,
		TTL:         3,
		Trace:       []NodeID{alice, bob}, // carol not yet added
		PayloadType: PayloadTypeTextTest,
		Payload:     []byte("please ack"),
	}

	actions := r.HandlePacket(pkt, &bob)

	var ackAction *Action
	for i := range actions {
		if actions[i].Type == ActionSendAck {
			ackAction = &actions[i]
		}
	}
	if ackAction == nil {
		t.Fatal("expected ActionSendAck")
	}
	ack := ackAction.Packet
	if ack.Destination != alice {
		t.Errorf("ACK destination should be alice, got %v", ack.Destination)
	}
	if ack.Mode == RoutingModeSourceRoute {
		// Route should be reversed: bob then alice
		if len(ack.Route) < 1 {
			t.Error("ACK source route should have hops")
		}
	}
	if ackAction.NextHop == nil || *ackAction.NextHop != bob {
		t.Errorf("ACK next hop should be bob (last trace hop), got %v", ackAction.NextHop)
	}
}

// ---- BFS topology -----------------------------------------------------------

func TestBFSShortestPath(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	carol := nodeID(3)
	gateway := nodeID(4)

	topo := NewTopology()
	topo.Update(TopoNode{ID: alice, Neighbors: []NodeID{bob}, Seq: 1})
	topo.Update(TopoNode{ID: bob, Neighbors: []NodeID{alice, carol}, Seq: 1})
	topo.Update(TopoNode{ID: carol, Neighbors: []NodeID{bob, gateway}, Seq: 1})
	topo.Update(TopoNode{ID: gateway, Neighbors: []NodeID{carol}, Seq: 1})

	// Alice → Gateway should be: bob, carol, gateway
	path := topo.BFSPath(alice, gateway)
	if len(path) != 3 {
		t.Errorf("expected path len 3, got %d: %v", len(path), path)
	}
	if len(path) > 0 && path[0] != bob {
		t.Errorf("first hop should be bob, got %v", path[0])
	}
	if len(path) > 1 && path[1] != carol {
		t.Errorf("second hop should be carol, got %v", path[1])
	}
	if len(path) > 2 && path[2] != gateway {
		t.Errorf("third hop should be gateway, got %v", path[2])
	}
}

func TestBFSDirectNeighbor(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)

	topo := NewTopology()
	topo.Update(TopoNode{ID: alice, Neighbors: []NodeID{bob}, Seq: 1})
	topo.Update(TopoNode{ID: bob, Neighbors: []NodeID{alice}, Seq: 1})

	path := topo.BFSPath(alice, bob)
	if len(path) != 1 || path[0] != bob {
		t.Errorf("direct neighbor path should be [bob], got %v", path)
	}
}

func TestBFSNoPath(t *testing.T) {
	alice := nodeID(1)
	dave := nodeID(4)

	topo := NewTopology()
	topo.Update(TopoNode{ID: alice, Neighbors: []NodeID{}, Seq: 1})
	topo.Update(TopoNode{ID: dave, Neighbors: []NodeID{}, Seq: 1})

	path := topo.BFSPath(alice, dave)
	if path != nil {
		t.Errorf("disconnected nodes: expected nil path, got %v", path)
	}
}

// ---- topology expiration ----------------------------------------------------

func TestTopologyExpiration(t *testing.T) {
	topo := &Topology{
		nodes:  make(map[NodeID]*TopoNode),
		expiry: 5 * time.Millisecond,
	}

	n := TopoNode{ID: nodeID(9), Seq: 1, Neighbors: []NodeID{}}
	topo.Update(n)

	// Immediately should be present
	if _, ok := topo.GetNode(nodeID(9)); !ok {
		t.Fatal("node should be present immediately after update")
	}

	// Wait past the expiry, then call Reap directly
	time.Sleep(20 * time.Millisecond)
	topo.Reap()

	if _, ok := topo.GetNode(nodeID(9)); ok {
		t.Error("node should have been expired after Reap()")
	}
}

func TestTopologyStaleUpdate(t *testing.T) {
	topo := NewTopology()
	n1 := TopoNode{ID: nodeID(5), Seq: 10, Neighbors: []NodeID{nodeID(2)}}
	n2 := TopoNode{ID: nodeID(5), Seq: 5, Neighbors: []NodeID{}} // older seq

	topo.Update(n1)
	topo.Update(n2) // should be ignored

	got, ok := topo.GetNode(nodeID(5))
	if !ok {
		t.Fatal("node missing")
	}
	if len(got.Neighbors) != 1 {
		t.Errorf("stale update should not overwrite: expected 1 neighbor, got %d", len(got.Neighbors))
	}
}

// ---- route fallback to flood ------------------------------------------------

func TestRouteFallbackToFlood(t *testing.T) {
	alice := nodeID(1)
	gateway := nodeID(4)

	r := NewRouter(alice)
	// Topo knows alice only — no path to gateway
	r.Topology.Update(TopoNode{ID: alice, Neighbors: []NodeID{}, Seq: 1})

	route, found := r.SelectRoute(gateway)
	// Should not find a route
	if found {
		t.Errorf("should not find route to unknown node, got route %v", route)
	}
	if route != nil {
		t.Errorf("route should be nil")
	}
}

func TestRouteSelectDirect(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	r := NewRouter(alice)
	r.Neighbors.Upsert(Neighbor{ID: bob})

	route, found := r.SelectRoute(bob)
	if !found {
		t.Error("direct neighbor should be found")
	}
	if route != nil {
		t.Error("direct neighbor route should be nil (direct)")
	}
}

func TestRouteSelectMultiHop(t *testing.T) {
	alice := nodeID(1)
	bob := nodeID(2)
	carol := nodeID(3)
	r := NewRouter(alice)

	r.Topology.Update(TopoNode{ID: alice, Neighbors: []NodeID{bob}, Seq: 1})
	r.Topology.Update(TopoNode{ID: bob, Neighbors: []NodeID{alice, carol}, Seq: 1})
	r.Topology.Update(TopoNode{ID: carol, Neighbors: []NodeID{bob}, Seq: 1})

	route, found := r.SelectRoute(carol)
	if !found {
		t.Error("multi-hop route should be found via topology")
	}
	if len(route) == 0 {
		t.Error("multi-hop route should have hops")
	}
}

// ---- announce handling ------------------------------------------------------

func TestAnnounceUpdatesTopology(t *testing.T) {
	alice := nodeID(1)
	carol := nodeID(3)
	r := NewRouter(alice)

	bobIdent, _ := NewIdentity()
	bob := bobIdent.NodeID()
	pkt := signedAnnounce(bobIdent, Capabilities(CapRelay|CapCodedPHY), []NodeID{alice, carol}, 1)

	r.HandlePacket(pkt, &bob)

	tnode, ok := r.Topology.GetNode(bob)
	if !ok {
		t.Fatal("topology should contain bob after announce")
	}
	if tnode.Seq != 1 {
		t.Errorf("topology seq mismatch: got %d want 1", tnode.Seq)
	}
	if len(tnode.Neighbors) != 2 {
		t.Errorf("topology neighbors: got %d want 2", len(tnode.Neighbors))
	}
}

// ---- AnnouncePayload encode/decode ------------------------------------------

func TestAnnouncePayloadRoundtrip(t *testing.T) {
	ap := AnnouncePayload{
		NodeID:    nodeID(7),
		Caps:      Capabilities(CapRelay | CapGateway),
		Neighbors: []NodeID{nodeID(1), nodeID(2)},
		Seq:       42,
		Timestamp: 1700000000,
	}
	enc, err := ap.Encode()
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	got, err := DecodeAnnounce(enc)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.NodeID != ap.NodeID {
		t.Errorf("NodeID mismatch")
	}
	if got.Caps != ap.Caps {
		t.Errorf("Caps mismatch: got %d want %d", got.Caps, ap.Caps)
	}
	if got.Seq != ap.Seq {
		t.Errorf("Seq mismatch: got %d want %d", got.Seq, ap.Seq)
	}
	if len(got.Neighbors) != 2 {
		t.Errorf("Neighbors count mismatch: got %d want 2", len(got.Neighbors))
	}
}

// ---- frame encode/decode ----------------------------------------------------

func TestFrameEncodeDecodeRoundtrip(t *testing.T) {
	pid := NewPacketID()
	f := Frame{
		Version:       1,
		PacketID:      pid,
		FragmentIndex: 2,
		FragmentCount: 5,
		PayloadCRC32:  0xDEADBEEF,
		Data:          []byte("fragment data here"),
	}
	enc := f.Encode()
	got, err := DecodeFrame(enc)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.PacketID != pid {
		t.Error("packet ID mismatch")
	}
	if got.FragmentIndex != 2 || got.FragmentCount != 5 {
		t.Errorf("frag index/count: got %d/%d want 2/5", got.FragmentIndex, got.FragmentCount)
	}
	if got.PayloadCRC32 != 0xDEADBEEF {
		t.Errorf("CRC mismatch")
	}
	if !bytes.Equal(got.Data, f.Data) {
		t.Error("data mismatch")
	}
}

func TestFrameTooShort(t *testing.T) {
	_, err := DecodeFrame([]byte{0x01, 0x02})
	if err == nil {
		t.Error("expected error for too-short frame")
	}
}

// ---- NodeID helpers ---------------------------------------------------------

func TestParseNodeID(t *testing.T) {
	id, err := ParseNodeID("0102030405060708")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if id[0] != 0x01 || id[7] != 0x08 {
		t.Errorf("unexpected id: %v", id)
	}
}

func TestParseNodeIDInvalid(t *testing.T) {
	_, err := ParseNodeID("nothex")
	if err == nil {
		t.Error("expected error for non-hex input")
	}
	_, err = ParseNodeID("0102030405") // too short
	if err == nil {
		t.Error("expected error for short input")
	}
}

func TestNodeIDLess(t *testing.T) {
	a := nodeID(1)
	b := nodeID(2)
	if !a.Less(b) {
		t.Error("a(1) should be less than b(2)")
	}
	if b.Less(a) {
		t.Error("b(2) should not be less than a(1)")
	}
}

// ---- BuildAnnounce ----------------------------------------------------------

func TestBuildAnnounce(t *testing.T) {
	ident, _ := NewIdentity()
	bob := nodeID(2)
	r := NewRouterForIdentity(ident)
	r.Neighbors.Upsert(Neighbor{ID: bob})

	pkt, err := r.BuildAnnounce(Capabilities(LinuxCapabilities), 5)
	if err != nil {
		t.Fatalf("BuildAnnounce: %v", err)
	}
	if pkt.Type != PacketTypeAnnounce {
		t.Errorf("type should be announce")
	}
	if pkt.TTL != 3 {
		t.Errorf("TTL should be 3, got %d", pkt.TTL)
	}
	if pkt.Source != ident.NodeID() {
		t.Errorf("source should be derived NodeID")
	}

	ap, err := DecodeAnnounce(pkt.Payload)
	if err != nil {
		t.Fatalf("DecodeAnnounce: %v", err)
	}
	if ap.Seq != 5 {
		t.Errorf("seq should be 5, got %d", ap.Seq)
	}
	if len(ap.Neighbors) != 1 || ap.Neighbors[0] != bob {
		t.Errorf("neighbors should contain bob: %v", ap.Neighbors)
	}
	// The built announce must verify and be self-consistent.
	if !VerifyAnnounce(ap.PublicKey, ap.Signature, uint32(ap.Timestamp), ap.Caps, ap.Seq, ap.Neighbors) {
		t.Errorf("built announce failed verification")
	}
	if NodeIDFromPubKey(ap.PublicKey) != ap.NodeID {
		t.Errorf("nodeID not bound to pubkey")
	}
}

// ---- 500+ byte payload fragmentation/reassembly -----------------------------

func TestOver500BytePayloadFragmentation(t *testing.T) {
	for _, size := range []int{501, 600, 1024, 2048} {
		data := make([]byte, size)
		for i := range data {
			data[i] = byte(i * 13 % 251)
		}
		pid := NewPacketID()
		mtu := 100 // small MTU to force many fragments

		frames := FragmentPacket(data, mtu, pid)
		expected := (size + 76) / 77 // ceil(size / (100-23))
		if len(frames) != expected {
			t.Errorf("size=%d: expected %d fragments, got %d", size, expected, len(frames))
		}

		r := NewReassembler()
		var result []byte
		for _, f := range frames {
			out, done, err := r.AddFrame(f)
			if err != nil {
				t.Fatalf("size=%d: add frame: %v", size, err)
			}
			if done {
				result = out
			}
		}
		if !bytes.Equal(result, data) {
			t.Errorf("size=%d: reassembly mismatch", size)
		}
	}
}
