package core

import (
	"bytes"
	"testing"

	"github.com/fxamacker/cbor/v2"
)

func testNodeID(b byte) NodeID {
	var id NodeID
	id[NodeIDBytes-1] = b
	return id
}

func testIdentity(offset byte) *Identity {
	var seed [SeedSize]byte
	for i := range seed {
		seed[i] = byte(i) + offset
	}
	return IdentityFromSeed(seed)
}

func TestDatagramEncodeDecodeRoundTrip(t *testing.T) {
	src := testNodeID(1)
	dst := testNodeID(2)
	dg := Datagram{
		Version:     DatagramVersion,
		ID:          NewDatagramID(),
		Source:      src,
		Destination: dst,
		TTL:         2,
		Route:       []NodeID{dst},
		Protocol:    ProtocolBLEEdgeChat,
		Flags:       uint16(FlagAckRequested),
		Payload:     []byte("hello"),
	}
	enc, err := dg.Encode()
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	got, err := DecodeDatagram(enc)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.Version != DatagramVersion || got.ID != dg.ID || got.Source != src || got.Destination != dst || got.TTL != 2 {
		t.Fatalf("round trip mismatch: %+v", got)
	}
	if got.Protocol != ProtocolBLEEdgeChat || !got.AckRequested() || !bytes.Equal(got.Payload, dg.Payload) {
		t.Fatalf("payload/protocol mismatch: %+v", got)
	}
}

func TestFrameFragmentReassembleByPeerAndTransferID(t *testing.T) {
	data := bytes.Repeat([]byte("abc"), 80)
	tid := NewTransferID()
	frames := FragmentDatagram(data, 60, tid)
	if len(frames) < 2 {
		t.Fatal("expected fragmentation")
	}
	r := NewReassembler()
	if out, done, err := r.AddFrame("peer-a", frames[0]); err != nil || done || out != nil {
		t.Fatalf("first add = out %v done %v err %v", out, done, err)
	}
	if out, done, err := r.AddFrame("peer-b", frames[1]); err != nil || done || out != nil {
		t.Fatalf("cross-peer fragment must not complete same assembly")
	}
	var result []byte
	for _, f := range frames[1:] {
		out, done, err := r.AddFrame("peer-a", f)
		if err != nil {
			t.Fatalf("add: %v", err)
		}
		if done {
			result = out
		}
	}
	if !bytes.Equal(result, data) {
		t.Fatalf("reassembled mismatch")
	}
}

func TestSignedAnnounceUpdatesTopologyAndRejectsTamper(t *testing.T) {
	alice := testIdentity(1)
	bob := testIdentity(30)
	r := NewRouterForIdentity(alice)
	body := NewAnnounceBody(bob, 2, 7, 1_700_000_000, Capabilities(CapRelay|CapCodedPHY), []NodeID{alice.NodeID()}, "Bob", "", "linux/arm64")
	payload, err := body.ToControl()
	if err != nil {
		t.Fatal(err)
	}
	dg := Datagram{Version: DatagramVersion, ID: NewDatagramID(), Source: bob.NodeID(), Destination: BroadcastNodeID, TTL: AnnounceTTL, Protocol: ProtocolBLEEdgeControl, Payload: payload}
	acts := r.HandleDatagram(dg, nil)
	if len(acts) == 0 || acts[0].Type != ActionDeliverLocal {
		t.Fatalf("expected local delivery/relay actions, got %+v", acts)
	}
	if n, ok := r.Topology.GetNode(bob.NodeID()); !ok || n.Name != "Bob" || n.Epoch != 2 || n.Seq != 7 {
		t.Fatalf("topology not updated: %+v ok=%v", n, ok)
	}

	body.Name = "Mallory"
	payload, _ = body.ToControl()
	dg.ID = NewDatagramID()
	dg.Payload = payload
	acts = r.HandleDatagram(dg, nil)
	if len(acts) != 1 || acts[0].Type != ActionDrop || acts[0].Reason != string(DropBadSignature) {
		t.Fatalf("tampered announce should drop, got %+v", acts)
	}
}

// A node is never in its own topology, so SelectRoute must seed the search with its direct
// neighbors to find a multi-hop source route to a node reachable via a relay (the MeshCore
// direct-bridge needs this — see Topology.BFSPathFromSource).
func TestSelectRouteFindsMultiHopViaLocalNeighbors(t *testing.T) {
	local := testNodeID(0x01)
	relay := testNodeID(0x02)
	dst := testNodeID(0x03)

	r := NewRouter(local)
	// We are directly connected to the relay only.
	r.Neighbors.Upsert(Neighbor{ID: relay})
	// Topology (from others' ANNOUNCEs): the relay neighbors both us and the destination.
	r.Topology.Update(TopoNode{ID: relay, Neighbors: []NodeID{local, dst}, Epoch: 1, Seq: 1})
	r.Topology.Update(TopoNode{ID: dst, Neighbors: []NodeID{relay}, Epoch: 1, Seq: 1})

	route := r.SelectRoute(dst)
	if len(route) != 2 || route[0] != relay || route[1] != dst {
		t.Fatalf("SelectRoute(dst) = %v, want [relay dst]", route)
	}

	// A direct neighbor still routes directly.
	if got := r.SelectRoute(relay); len(got) != 1 || got[0] != relay {
		t.Fatalf("SelectRoute(relay) = %v, want [relay]", got)
	}
	// An unreachable node has no route.
	if got := r.SelectRoute(testNodeID(0x09)); got != nil {
		t.Fatalf("SelectRoute(unknown) = %v, want nil", got)
	}
}

func TestRouterBuildsAckOnlyWhenRequested(t *testing.T) {
	alice := testIdentity(1)
	bob := testIdentity(30)
	r := NewRouterForIdentity(bob)
	dg := Datagram{
		Version:     DatagramVersion,
		ID:          NewDatagramID(),
		Source:      alice.NodeID(),
		Destination: bob.NodeID(),
		TTL:         1,
		Route:       []NodeID{bob.NodeID()},
		Protocol:    ProtocolBLEEdgeChat,
		Flags:       uint16(FlagAckRequested),
		Payload:     []byte("x"),
	}
	acts := r.HandleDatagram(dg, nil)
	if len(acts) != 2 || acts[0].Type != ActionDeliverLocal || acts[1].Type != ActionSendAck {
		t.Fatalf("actions = %+v", acts)
	}
	ack := acts[1].Datagram
	if ack.Protocol != ProtocolBLEEdgeControl || ack.Destination != alice.NodeID() || len(ack.Route) != 1 || ack.Route[0] != alice.NodeID() {
		t.Fatalf("bad ack: %+v", ack)
	}
}

func TestBridgedBodyRoundTrip(t *testing.T) {
	id := NewDatagramID()
	bridge := NodeID{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
	hash := []byte{0xde, 0xad, 0xbe, 0xef}

	payload, err := BridgedBody{BridgedID: id, BridgeID: bridge, MeshHash: hash}.ToControl()
	if err != nil {
		t.Fatal(err)
	}
	ctrl, err := DecodeControl(payload)
	if err != nil {
		t.Fatal(err)
	}
	if ctrl.Kind != ControlBridged {
		t.Fatalf("kind = %d, want ControlBridged(%d)", ctrl.Kind, ControlBridged)
	}
	var got BridgedBody
	if err := cbor.Unmarshal(ctrl.Body, &got); err != nil {
		t.Fatal(err)
	}
	if got.BridgedID != id {
		t.Errorf("BridgedID = %x, want %x", got.BridgedID, id)
	}
	if got.BridgeID != bridge {
		t.Errorf("BridgeID = %x, want %x", got.BridgeID, bridge)
	}
	if string(got.MeshHash) != string(hash) {
		t.Errorf("MeshHash = %x, want %x", got.MeshHash, hash)
	}
}
