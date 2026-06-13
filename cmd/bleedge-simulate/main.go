// bleedge-simulate is an in-memory mesh simulator with no BLE dependencies.
// It exercises all core routing logic and prints clearly labelled pass/fail results.
//go:build v2obsolete

package main

import (
	"bytes"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/burningtree/bleedge/core"
)

// ---- in-memory peer link ----------------------------------------------------

type chanLink struct {
	peerID core.NodeID
	ch     chan []byte
	rssi   int
}

func (l *chanLink) PeerID() core.NodeID { return l.peerID }
func (l *chanLink) SendFrame(b []byte) error {
	select {
	case l.ch <- b:
	default:
	}
	return nil
}
func (l *chanLink) RSSI() int       { return l.rssi }
func (l *chanLink) TxPHY() core.PHY { return core.PHYCoded }
func (l *chanLink) RxPHY() core.PHY { return core.PHYCoded }

// ---- simulated node ---------------------------------------------------------

type simNode struct {
	name        string
	router      *core.Router
	reassembler *core.Reassembler
	peers       map[core.NodeID]*chanLink
	rxCh        chan []byte // inbound frames for this node
	mu          sync.Mutex
	delivered   []core.Packet
	acked       []core.Packet
}

func newSimNode(name string, id core.NodeID) *simNode {
	return &simNode{
		name:        name,
		router:      core.NewRouter(id),
		reassembler: core.NewReassembler(),
		peers:       make(map[core.NodeID]*chanLink),
		rxCh:        make(chan []byte, 256),
	}
}

func (n *simNode) id() core.NodeID { return n.router.LocalID }

// connect wires two nodes together bidirectionally.
func connect(a, b *simNode) {
	ab := make(chan []byte, 256)
	ba := make(chan []byte, 256)

	linkAB := &chanLink{peerID: b.id(), ch: ab, rssi: -70}
	linkBA := &chanLink{peerID: a.id(), ch: ba, rssi: -70}

	a.mu.Lock()
	a.peers[b.id()] = linkAB
	a.mu.Unlock()

	b.mu.Lock()
	b.peers[a.id()] = linkBA
	b.mu.Unlock()

	a.router.Neighbors.Upsert(core.Neighbor{ID: b.id(), TxPHY: core.PHYCoded, RxPHY: core.PHYCoded})
	b.router.Neighbors.Upsert(core.Neighbor{ID: a.id(), TxPHY: core.PHYCoded, RxPHY: core.PHYCoded})

	// Forward frames from a→b's channel into b's rxCh
	go func() {
		for frame := range ab {
			b.rxCh <- frame
		}
	}()
	go func() {
		for frame := range ba {
			a.rxCh <- frame
		}
	}()
}

// disconnect removes a peer link from a (one-directional severing).
func disconnect(from, target *simNode) {
	from.mu.Lock()
	delete(from.peers, target.id())
	from.mu.Unlock()
	from.router.Neighbors.Remove(target.id())
}

// run starts the node's receive loop in a background goroutine.
func (n *simNode) run(done <-chan struct{}) {
	go func() {
		for {
			select {
			case <-done:
				return
			case frame := <-n.rxCh:
				n.handleFrame(frame)
			}
		}
	}()
}

func (n *simNode) handleFrame(raw []byte) {
	f, err := core.DecodeFrame(raw)
	if err != nil {
		return
	}
	data, done, err := n.reassembler.AddFrame(f)
	if err != nil || !done {
		return
	}
	pkt, err := core.DecodePacket(data)
	if err != nil {
		return
	}

	// identify incoming peer from frame's packet source
	src := pkt.Source
	actions := n.router.HandlePacket(pkt, &src)

	for _, a := range actions {
		switch a.Type {
		case core.ActionDeliverLocal:
			n.mu.Lock()
			if a.Packet.Type == core.PacketTypeAck {
				n.acked = append(n.acked, a.Packet)
			} else {
				n.delivered = append(n.delivered, a.Packet)
			}
			n.mu.Unlock()

		case core.ActionSendAck, core.ActionRelayNextHop:
			if a.NextHop == nil {
				break
			}
			n.mu.Lock()
			link, ok := n.peers[*a.NextHop]
			n.mu.Unlock()
			if ok {
				n.sendPktToLink(a.Packet, link)
			}

		case core.ActionRelayFlood:
			time.AfterFunc(core.FloodJitter(), func() {
				n.mu.Lock()
				links := make([]*chanLink, 0)
				for pid, l := range n.peers {
					if a.NextHop != nil && pid == *a.NextHop {
						continue
					}
					links = append(links, l)
				}
				n.mu.Unlock()
				for _, l := range links {
					n.sendPktToLink(a.Packet, l)
				}
			})
		}
	}
}

func (n *simNode) sendPktToLink(pkt core.Packet, link *chanLink) {
	data, err := pkt.Encode()
	if err != nil {
		return
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, pkt.ID)
	for _, f := range frames {
		link.SendFrame(f.Encode()) //nolint:errcheck
	}
}

func (n *simNode) sendPacket(pkt core.Packet, peerID core.NodeID) {
	n.mu.Lock()
	link, ok := n.peers[peerID]
	n.mu.Unlock()
	if !ok {
		return
	}
	n.sendPktToLink(pkt, link)
}

func (n *simNode) broadcast(pkt core.Packet) {
	n.mu.Lock()
	links := make([]*chanLink, 0, len(n.peers))
	for _, l := range n.peers {
		links = append(links, l)
	}
	n.mu.Unlock()
	for _, l := range links {
		n.sendPktToLink(pkt, l)
	}
}

func (n *simNode) waitDelivered(count int, timeout time.Duration) []core.Packet {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		n.mu.Lock()
		if len(n.delivered) >= count {
			out := make([]core.Packet, len(n.delivered))
			copy(out, n.delivered)
			n.mu.Unlock()
			return out
		}
		n.mu.Unlock()
		time.Sleep(5 * time.Millisecond)
	}
	n.mu.Lock()
	out := make([]core.Packet, len(n.delivered))
	copy(out, n.delivered)
	n.mu.Unlock()
	return out
}

func (n *simNode) waitAcked(count int, timeout time.Duration) []core.Packet {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		n.mu.Lock()
		if len(n.acked) >= count {
			out := make([]core.Packet, len(n.acked))
			copy(out, n.acked)
			n.mu.Unlock()
			return out
		}
		n.mu.Unlock()
		time.Sleep(5 * time.Millisecond)
	}
	n.mu.Lock()
	out := make([]core.Packet, len(n.acked))
	copy(out, n.acked)
	n.mu.Unlock()
	return out
}

func (n *simNode) clearDelivered() {
	n.mu.Lock()
	n.delivered = nil
	n.acked = nil
	n.mu.Unlock()
}

// ---- test helpers -----------------------------------------------------------

type result struct {
	name   string
	passed bool
	detail string
}

var results []result

func check(name string, passed bool, detail string) {
	results = append(results, result{name, passed, detail})
	status := "PASS"
	if !passed {
		status = "FAIL"
	}
	fmt.Printf("[%s] %s", status, name)
	if detail != "" {
		fmt.Printf(": %s", detail)
	}
	fmt.Println()
}

// ---- topology setup ---------------------------------------------------------
//
// Alice ── Bob ── Carol ── Gateway
//           │
//           └──── Dave
//
// NodeIDs are deterministic for reproducible tests.

var (
	idAlice   = mustID(0x01)
	idBob     = mustID(0x02)
	idCarol   = mustID(0x03)
	idGateway = mustID(0x04)
	idDave    = mustID(0x05)
)

func mustID(b byte) core.NodeID {
	var id core.NodeID
	id[7] = b
	return id
}

func buildTopology() (alice, bob, carol, gateway, dave *simNode, done chan struct{}) {
	alice = newSimNode("Alice", idAlice)
	bob = newSimNode("Bob", idBob)
	carol = newSimNode("Carol", idCarol)
	gateway = newSimNode("Gateway", idGateway)
	dave = newSimNode("Dave", idDave)

	connect(alice, bob)
	connect(bob, carol)
	connect(carol, gateway)
	connect(bob, dave)

	done = make(chan struct{})
	alice.run(done)
	bob.run(done)
	carol.run(done)
	gateway.run(done)
	dave.run(done)
	return
}

// ---- main -------------------------------------------------------------------

func main() {
	fmt.Println(strings.Repeat("=", 60))
	fmt.Println("BLEEdge In-Memory Simulator")
	fmt.Println(strings.Repeat("=", 60))
	fmt.Println()

	testFloodRouting()
	testDeduplication()
	testTTLExhaustion()
	testLoopDetection()
	testSourceRoute()
	testBFSRouteCalculation()
	testRouteFallbackToFlood()
	testACKViaReversedTrace()
	testPeerDisconnect()
	testTopologyExpiration()
	testFragmentation600()
	testAllOver500BytePayloads()

	fmt.Println()
	fmt.Println(strings.Repeat("=", 60))
	passed, failed := 0, 0
	for _, r := range results {
		if r.passed {
			passed++
		} else {
			failed++
		}
	}
	fmt.Printf("Results: %d passed, %d failed\n", passed, failed)
	fmt.Println(strings.Repeat("=", 60))
}

// ---- test cases -------------------------------------------------------------

func testFloodRouting() {
	fmt.Println("\n--- Flood Routing ---")
	alice, bob, carol, gateway, dave, done := buildTopology()
	defer close(done)

	msg := []byte("hello from alice")
	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      idAlice,
		Destination: core.NodeID{}, // broadcast
		Mode:        core.RoutingModeFlood,
		TTL:         5,
		PayloadType: core.PayloadTypeTextTest,
		Payload:     msg,
	}

	alice.broadcast(pkt)

	// All other nodes should deliver the packet
	time.Sleep(300 * time.Millisecond)

	delivered := func(n *simNode) bool {
		n.mu.Lock()
		defer n.mu.Unlock()
		for _, p := range n.delivered {
			if bytes.Equal(p.Payload, msg) {
				return true
			}
		}
		return false
	}

	check("flood: bob received", delivered(bob), "")
	check("flood: carol received", delivered(carol), "")
	check("flood: gateway received", delivered(gateway), "")
	check("flood: dave received", delivered(dave), "")
}

func testDeduplication() {
	fmt.Println("\n--- Deduplication ---")
	_, bob, _, _, _, done := buildTopology()
	defer close(done)

	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      idAlice,
		Destination: core.NodeID{},
		Mode:        core.RoutingModeFlood,
		TTL:         4,
		PayloadType: core.PayloadTypeTextTest,
		Payload:     []byte("dedup test"),
	}

	incomingPeer := idAlice
	actions1 := bob.router.HandlePacket(pkt, &incomingPeer)
	actions2 := bob.router.HandlePacket(pkt, &incomingPeer)

	firstHasDelivery := false
	for _, a := range actions1 {
		if a.Type == core.ActionDeliverLocal || a.Type == core.ActionRelayFlood {
			firstHasDelivery = true
		}
	}
	secondDropped := len(actions2) == 1 && actions2[0].Type == core.ActionDrop &&
		actions2[0].Reason == string(core.DropDuplicate)

	check("dedup: first pass processes packet", firstHasDelivery, "")
	check("dedup: second pass drops duplicate", secondDropped,
		fmt.Sprintf("got %d actions, first=%v", len(actions2), func() string {
			if len(actions2) > 0 {
				return string(actions2[0].Type)
			}
			return "none"
		}()))
}

func testTTLExhaustion() {
	fmt.Println("\n--- TTL Exhaustion ---")
	_, bob, _, _, _, done := buildTopology()
	defer close(done)

	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      idAlice,
		Destination: core.NodeID{},
		Mode:        core.RoutingModeFlood,
		TTL:         0,
		PayloadType: core.PayloadTypeTextTest,
		Payload:     []byte("ttl zero"),
	}
	incomingPeer := idAlice
	actions := bob.router.HandlePacket(pkt, &incomingPeer)
	dropped := len(actions) == 1 && actions[0].Type == core.ActionDrop &&
		actions[0].Reason == string(core.DropTTL)
	check("ttl: TTL=0 packet dropped", dropped, "")

	// TTL=1 should deliver but not relay
	pkt2 := pkt
	pkt2.ID = core.NewPacketID()
	pkt2.TTL = 1
	actions2 := bob.router.HandlePacket(pkt2, &incomingPeer)
	hasRelay := false
	for _, a := range actions2 {
		if a.Type == core.ActionRelayFlood {
			hasRelay = true
		}
	}
	check("ttl: TTL=1 packet not relayed", !hasRelay, "")
}

func testLoopDetection() {
	fmt.Println("\n--- Loop Detection ---")
	_, bob, _, _, _, done := buildTopology()
	defer close(done)

	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      idAlice,
		Destination: core.NodeID{},
		Mode:        core.RoutingModeFlood,
		TTL:         4,
		Trace:       []core.NodeID{idBob}, // bob already in trace = loop
		PayloadType: core.PayloadTypeTextTest,
		Payload:     []byte("loop test"),
	}
	incomingPeer := idAlice
	actions := bob.router.HandlePacket(pkt, &incomingPeer)
	dropped := len(actions) == 1 && actions[0].Type == core.ActionDrop &&
		actions[0].Reason == string(core.DropLoop)
	check("loop: packet with self in trace dropped", dropped, "")
}

func testSourceRoute() {
	fmt.Println("\n--- Source Route (Alice → Bob → Carol → Gateway) ---")
	alice, bob, carol, gateway, _, done := buildTopology()
	defer close(done)
	_ = carol

	// Build source-routed packet from alice with explicit route
	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      idAlice,
		Destination: idGateway,
		Mode:        core.RoutingModeSourceRoute,
		TTL:         6,
		Route:       []core.NodeID{idBob, idCarol, idGateway},
		RouteCursor: 0,
		PayloadType: core.PayloadTypeTextTest,
		Payload:     []byte("source routed"),
	}

	alice.sendPacket(pkt, idBob)

	pkts := gateway.waitDelivered(1, 500*time.Millisecond)
	check("source-route: gateway received packet", len(pkts) == 1, fmt.Sprintf("received %d", len(pkts)))
	if len(pkts) > 0 {
		check("source-route: payload correct", string(pkts[0].Payload) == "source routed", "")
	}

	// Verify bob received an ACK (alice will receive it)
	// Alice needs to be running as a proper node to receive ack - check gateway sent ack actions
	ackInActions := func() bool {
		testPkt := pkt
		testPkt.ID = core.NewPacketID()
		testPkt.RouteCursor = 2 // at gateway
		testPkt.Trace = []core.NodeID{idBob, idCarol}
		incomingPeer := idCarol
		actions := gateway.router.HandlePacket(testPkt, &incomingPeer)
		for _, a := range actions {
			if a.Type == core.ActionSendAck {
				return true
			}
		}
		return false
	}()
	check("source-route: gateway sends ACK", ackInActions, "")
	_ = bob
}

func testBFSRouteCalculation() {
	fmt.Println("\n--- BFS Route Calculation ---")

	// Build topology knowledge in alice's router
	alice := newSimNode("Alice", idAlice)

	alice.router.Topology.Update(core.TopoNode{
		ID:        idAlice,
		Neighbors: []core.NodeID{idBob},
		Seq:       1,
	})
	alice.router.Topology.Update(core.TopoNode{
		ID:        idBob,
		Neighbors: []core.NodeID{idAlice, idCarol, idDave},
		Seq:       1,
	})
	alice.router.Topology.Update(core.TopoNode{
		ID:        idCarol,
		Neighbors: []core.NodeID{idBob, idGateway},
		Seq:       1,
	})
	alice.router.Topology.Update(core.TopoNode{
		ID:        idGateway,
		Neighbors: []core.NodeID{idCarol},
		Seq:       1,
	})
	alice.router.Topology.Update(core.TopoNode{
		ID:        idDave,
		Neighbors: []core.NodeID{idBob},
		Seq:       1,
	})

	path := alice.router.Topology.BFSPath(idAlice, idGateway)
	expectedPath := []core.NodeID{idBob, idCarol, idGateway}
	pathOK := len(path) == len(expectedPath)
	if pathOK {
		for i := range path {
			if path[i] != expectedPath[i] {
				pathOK = false
				break
			}
		}
	}
	check("bfs: Alice→Gateway path is [Bob,Carol,Gateway]", pathOK,
		fmt.Sprintf("got %d hops: %v", len(path), path))

	// Direct path Alice → Bob
	direct := alice.router.Topology.BFSPath(idAlice, idBob)
	check("bfs: Alice→Bob direct path [Bob]", len(direct) == 1 && direct[0] == idBob,
		fmt.Sprintf("got %v", direct))

	// No path to unknown node
	unknown := mustID(0xFF)
	noPath := alice.router.Topology.BFSPath(idAlice, unknown)
	check("bfs: no path to unknown node", noPath == nil, fmt.Sprintf("got %v", noPath))
}

func testRouteFallbackToFlood() {
	fmt.Println("\n--- Route Fallback to Flood ---")
	alice := newSimNode("Alice", idAlice)

	// Alice has no topology knowledge → SelectRoute falls back
	route, found := alice.router.SelectRoute(idGateway)
	check("fallback: no route to unknown gateway", !found,
		fmt.Sprintf("found=%v route=%v", found, route))

	// After adding topology, route should be found
	alice.router.Topology.Update(core.TopoNode{
		ID:        idAlice,
		Neighbors: []core.NodeID{idBob},
		Seq:       1,
	})
	alice.router.Topology.Update(core.TopoNode{
		ID:        idBob,
		Neighbors: []core.NodeID{idAlice, idCarol},
		Seq:       1,
	})
	alice.router.Topology.Update(core.TopoNode{
		ID:        idCarol,
		Neighbors: []core.NodeID{idBob, idGateway},
		Seq:       1,
	})
	alice.router.Topology.Update(core.TopoNode{
		ID:        idGateway,
		Neighbors: []core.NodeID{idCarol},
		Seq:       1,
	})

	route2, found2 := alice.router.SelectRoute(idGateway)
	check("fallback: route found after topology update", found2,
		fmt.Sprintf("found=%v route=%v", found2, route2))

	// Expire the Bob→Carol link by removing bob from topo — path breaks
	alice.router.Topology.ExpireNode(idBob)
	route3, found3 := alice.router.SelectRoute(idGateway)
	check("fallback: route breaks when intermediate node removed", !found3,
		fmt.Sprintf("found=%v route=%v", found3, route3))
}

func testACKViaReversedTrace() {
	fmt.Println("\n--- ACK via Reversed Trace ---")
	_, _, carol, _, _, done := buildTopology()
	defer close(done)

	// Carol receives a unicast flood from Alice (via Alice→Bob→Carol trace)
	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      idAlice,
		Destination: idCarol,
		Mode:        core.RoutingModeFlood,
		TTL:         4,
		Trace:       []core.NodeID{idAlice, idBob}, // carol appends itself
		PayloadType: core.PayloadTypeTextTest,
		Payload:     []byte("unicast to carol"),
	}

	incomingPeer := idBob
	actions := carol.router.HandlePacket(pkt, &incomingPeer)

	var ackAction *core.Action
	for i := range actions {
		if actions[i].Type == core.ActionSendAck {
			ackAction = &actions[i]
		}
	}

	check("ack: carol sends ACK", ackAction != nil, "")
	if ackAction != nil {
		ack := ackAction.Packet
		check("ack: destination is Alice", ack.Destination == idAlice, fmt.Sprintf("got %v", ack.Destination))
		check("ack: next hop is Bob", ackAction.NextHop != nil && *ackAction.NextHop == idBob,
			fmt.Sprintf("next hop: %v", ackAction.NextHop))
		if ack.Mode == core.RoutingModeSourceRoute {
			check("ack: source route reverses trace", len(ack.Route) > 0,
				fmt.Sprintf("route=%v", ack.Route))
		}
	}
}

func testPeerDisconnect() {
	fmt.Println("\n--- Peer Disconnect (remove Dave from Bob's neighbors) ---")
	_, bob, _, _, dave, done := buildTopology()
	defer close(done)

	// Verify Dave is known to Bob
	before := bob.router.Neighbors.All()
	hasDave := false
	for _, n := range before {
		if n.ID == idDave {
			hasDave = true
		}
	}
	check("disconnect: Dave initially in Bob's neighbors", hasDave, "")

	disconnect(bob, dave)

	// Verify Dave removed
	after := bob.router.Neighbors.All()
	stillHasDave := false
	for _, n := range after {
		if n.ID == idDave {
			stillHasDave = true
		}
	}
	check("disconnect: Dave removed from Bob's neighbors", !stillHasDave, "")

	// A flood from Bob should no longer reach Dave
	bob.clearDelivered()
	dave.clearDelivered()

	msg := []byte("post-disconnect message")
	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      idBob,
		Destination: core.NodeID{},
		Mode:        core.RoutingModeFlood,
		TTL:         3,
		PayloadType: core.PayloadTypeTextTest,
		Payload:     msg,
	}
	bob.broadcast(pkt)
	time.Sleep(200 * time.Millisecond)

	dave.mu.Lock()
	daveReceived := len(dave.delivered) > 0
	dave.mu.Unlock()

	check("disconnect: Dave does not receive after disconnect", !daveReceived, "")
}

func testTopologyExpiration() {
	fmt.Println("\n--- Topology Expiration ---")

	alice := newSimNode("Alice", idAlice)

	// Use a very short expiry topology
	topo := &core.Topology{}
	// We can't set private fields — use the public Topology with forced expiry test
	// Instead, inject directly via the exported Update and ExpireNode
	alice.router.Topology.Update(core.TopoNode{
		ID:        idGateway,
		Neighbors: []core.NodeID{idCarol},
		Seq:       1,
	})

	_, foundBefore := alice.router.Topology.GetNode(idGateway)
	check("topo-expiry: node present after update", foundBefore, "")

	alice.router.Topology.ExpireNode(idGateway)
	_, foundAfter := alice.router.Topology.GetNode(idGateway)
	check("topo-expiry: node absent after forced expiry", !foundAfter, "")

	// Route to gateway should now fail
	_, found := alice.router.SelectRoute(idGateway)
	check("topo-expiry: SelectRoute fails after expiry", !found, "")
	_ = topo
}

func testFragmentation600() {
	fmt.Println("\n--- Fragmentation of 600-byte Payload ---")

	alice, _, _, gateway, _, done := buildTopology()
	defer close(done)
	gateway.clearDelivered()

	// Build 600-byte payload
	payload := make([]byte, 600)
	for i := range payload {
		payload[i] = byte(i % 251)
	}

	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      idAlice,
		Destination: core.NodeID{},
		Mode:        core.RoutingModeFlood,
		TTL:         6,
		PayloadType: core.PayloadTypeTextTest,
		Payload:     payload,
	}

	alice.broadcast(pkt)

	pkts := gateway.waitDelivered(1, 800*time.Millisecond)
	check("frag-600: gateway received 600-byte packet", len(pkts) == 1,
		fmt.Sprintf("received %d packets", len(pkts)))
	if len(pkts) > 0 {
		check("frag-600: payload intact (600 bytes)", len(pkts[0].Payload) == 600,
			fmt.Sprintf("got %d bytes", len(pkts[0].Payload)))
		check("frag-600: payload content correct",
			bytes.Equal(pkts[0].Payload, payload), "")
	}
}

func testAllOver500BytePayloads() {
	fmt.Println("\n--- All 500+ Byte Payload Fragment/Reassembly (direct) ---")

	for _, size := range []int{501, 600, 1024, 2048} {
		data := make([]byte, size)
		for i := range data {
			data[i] = byte(i * 13 % 251)
		}
		pid := core.NewPacketID()
		mtu := 200 // simulate BLE frame MTU

		frames := core.FragmentPacket(data, mtu, pid)
		r := core.NewReassembler()

		// Deliver out of order (reverse)
		var result []byte
		for i := len(frames) - 1; i >= 0; i-- {
			out, done, err := r.AddFrame(frames[i])
			if err != nil {
				check(fmt.Sprintf("frag-%d: no CRC error", size), false, err.Error())
				continue
			}
			if done {
				result = out
			}
		}
		check(fmt.Sprintf("frag-%d: reassembled correctly", size),
			bytes.Equal(result, data),
			fmt.Sprintf("got %d bytes want %d", len(result), len(data)))
	}
}
