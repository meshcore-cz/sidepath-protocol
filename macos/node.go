//go:build darwin

// Package macos implements a Sidepath node for macOS. All CoreBluetooth lives in a native Swift
// helper process (sidepath-macos-ble-helper); this package speaks a length-prefixed stdio protocol
// to it (see ble_helper.go) and keeps the routing/dedup/neighbor/announce logic. CoreBluetooth does
// not expose LE Coded PHY, so the node always operates in 1m mode (not valid for Long Range demos).
package macos

import (
	"bytes"
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/fxamacker/cbor/v2"
	"github.com/meshcore-cz/sidepath-protocol/core"
)

// Node is a macOS Sidepath node.
type Node struct {
	nodeID        core.NodeID
	identity      *core.Identity
	description   string
	name          string
	platform      string
	caps          core.Capabilities
	router        *core.Router
	reassem       *core.Reassembler
	allowlist     map[core.NodeID]bool
	verbose       bool
	announceEpoch uint64
	announceNow   chan struct{}

	helper *bleHelper

	mu sync.Mutex
	// peer addr string → link (outgoing connections, central role)
	peers map[string]*MacPeerLink
	// addrs with an in-flight connect (scan reports duplicates; dedupe attempts)
	connecting map[string]bool
	// centrals subscribed to our PACKET_OUT (peripheral role): central_id → true
	subscribers map[string]bool
	// server-side peer central_id → learned NodeID
	serverPeerIDs map[string]core.NodeID
	// scan addr → NodeID learned from its NODE_INFO, so onScan can dedup an already-connected peer
	// by identity even though the advertisement carries only an address
	addrNode map[string]core.NodeID
	// scan addr → last advertisement RSSI. Scans are keyed by BLE address (identity isn't known
	// until NODE_INFO is read post-connect), so RSSI is stashed here and carried onto the NodeID
	// once it resolves; from the neighbor table onward everything is NodeID-keyed.
	addrRSSI map[string]int

	// joined channels keyed by lowercase PSK hex (see channels.go)
	channels map[string]*Channel

	logFn            func(string)
	onMessage        func(core.Datagram)
	onPeerConnect    func(core.NodeID)
	onPeerDisconnect func(core.NodeID)
	onTrace          func(core.TraceResponseBody, time.Duration)
	onAck            func(ackedID core.DatagramID, from core.NodeID)

	// traceStarts records the send time of each trace we originated, keyed by tag, so a response
	// can be matched back to compute round-trip time. Guarded by traceMu.
	traceMu     sync.Mutex
	traceStarts map[uint32]time.Time
}

type TransmitInfo struct {
	DatagramID    core.DatagramID
	DatagramBytes int
	FragmentCount int
}

// Config holds startup parameters.
type Config struct {
	Identity    *core.Identity
	Name        string // primary display label; empty = deterministic default from pubkey
	Platform    string // OS/device string; empty = core.PlatformDescription()
	Description string // free-form bio for ANNOUNCE/NODE_INFO; default empty
	Caps        core.Capabilities
	// Bridges lists the external networks this gateway bridges, advertised in the v2 ANNOUNCE
	// `bridges` section (§8.3). Non-empty makes the node emit v2 announces; empty keeps it on v1.
	Bridges          []core.BridgeAd
	Allowlist        []core.NodeID
	Verbose          bool
	AnnounceEpoch    uint64
	LogFn            func(string)
	OnMessage        func(dg core.Datagram) // called for locally delivered application datagrams
	OnPeerConnect    func(id core.NodeID)   // called when outgoing peer connects
	OnPeerDisconnect func(id core.NodeID)   // called when peer disconnects
	// OnTrace is called when a response arrives for a trace this node originated, with the round-trip
	// time measured from SendTrace. Responses with no matching pending request are ignored.
	OnTrace func(resp core.TraceResponseBody, rtt time.Duration)
	// OnAck is called when an ACK is delivered to this node for a datagram it sent (e.g. a direct
	// chat sent with FlagAckRequested), carrying the acked datagram's ID and the acking node.
	OnAck func(ackedID core.DatagramID, from core.NodeID)
}

// New creates a Node. Call Run to start it.
func New(cfg Config) *Node {
	name := cfg.Name
	if name == "" {
		name = core.DefaultNodeName(cfg.Identity.Pub)
	}
	platform := cfg.Platform
	if platform == "" {
		platform = core.PlatformDescription()
	}
	description := cfg.Description
	router := core.NewRouterForIdentity(cfg.Identity)
	router.Description = description
	router.Name = name
	router.Platform = platform
	router.Bridges = cfg.Bridges
	n := &Node{
		nodeID:        cfg.Identity.NodeID(),
		identity:      cfg.Identity,
		description:   description,
		name:          name,
		platform:      platform,
		caps:          cfg.Caps,
		router:        router,
		reassem:       core.NewReassembler(),
		allowlist:     make(map[core.NodeID]bool),
		verbose:       cfg.Verbose,
		announceEpoch: cfg.AnnounceEpoch,
		announceNow:   make(chan struct{}, 1),
		peers:         make(map[string]*MacPeerLink),
		connecting:    make(map[string]bool),
		subscribers:   make(map[string]bool),
		serverPeerIDs: make(map[string]core.NodeID),
		addrNode:      make(map[string]core.NodeID),
		addrRSSI:      make(map[string]int),
		channels:      make(map[string]*Channel),
		traceStarts:   make(map[uint32]time.Time),
		logFn:         cfg.LogFn,
	}
	if n.announceEpoch == 0 {
		n.announceEpoch = 1
	}
	n.JoinPublicChannel() // MeshCore's default Public channel, like the Android app
	if n.logFn == nil {
		n.logFn = func(s string) { log.Println(s) }
	}
	n.onMessage = cfg.OnMessage
	n.onPeerConnect = cfg.OnPeerConnect
	n.onPeerDisconnect = cfg.OnPeerDisconnect
	n.onTrace = cfg.OnTrace
	n.onAck = cfg.OnAck
	for _, id := range cfg.Allowlist {
		n.allowlist[id] = true
		n.router.Allowlist[id] = true
	}
	return n
}

func (n *Node) logf(format string, args ...any) {
	n.logFn(fmt.Sprintf(format, args...))
}

// Run spawns the native CoreBluetooth helper, starts advertising + scanning through it, and drives
// the node from the helper's events. Blocks until ctx is done.
func (n *Node) Run(ctx context.Context) error {
	n.logf("node id=%s  phy=1m (CoreBluetooth, no Coded PHY)", n.nodeID)
	n.logf("NOTE: macOS/CoreBluetooth does not support LE Coded PHY (Long Range) — 1M only")

	h, err := startBLEHelper(func(s string) { n.logf("%s", s) }, n.onHelperEvent)
	if err != nil {
		return fmt.Errorf("start BLE helper: %w", err)
	}
	n.helper = h
	n.helper.start(n.buildNodeInfo())
	n.logf("advertising Sidepath service; scanning for peers")

	go n.announceLoop(ctx)

	<-ctx.Done()
	_ = h.cmd.Process.Kill()
	return nil
}

// onHelperEvent dispatches one decoded event from the BLE helper. Runs on the helper reader
// goroutine; handlers must be safe to call from there.
func (n *Node) onHelperEvent(header map[string]any, payload []byte) {
	switch asString(header["type"]) {
	case "ready":
		n.logf("BLE helper ready")
	case "log":
		n.logf("helper: %s", asString(header["message"]))
	case "error":
		n.logf("helper error: %s", asString(header["message"]))
	case "scan":
		n.onScan(asString(header["addr"]), asInt(header["rssi"]))
	case "peer_connected":
		n.onPeerConnected(asString(header["addr"]), asInt(header["mtu"]), payload)
	case "peer_failed":
		addr := asString(header["addr"])
		n.mu.Lock()
		delete(n.connecting, addr)
		n.mu.Unlock()
		n.logf("connect %s failed: %s", addr, asString(header["error"]))
	case "peer_disconnected":
		n.onPeerDisconnected(asString(header["addr"]), asString(header["reason"]))
	case "central_frame":
		n.handleIncomingFrameForAddr(asString(header["addr"]), payload)
	case "subscribed":
		n.onSubscribed(asString(header["central_id"]))
	case "unsubscribed":
		n.onUnsubscribed(asString(header["central_id"]))
	case "peripheral_frame":
		n.handleIncomingFrame(payload, nil, asString(header["central_id"]))
	}
}

// onScan reacts to a discovered Sidepath advertisement: connect once per addr.
func (n *Node) onScan(addr string, rssi int) {
	if addr == "" {
		return
	}
	n.mu.Lock()
	// Stash the advertisement RSSI for this address; it's carried onto the NodeID at connect time
	// (onPeerConnected) and refreshed live below once the address→NodeID mapping is known.
	n.addrRSSI[addr] = rssi
	id, known := n.addrNode[addr]
	_, connected := n.peers[addr]
	// Dedup by NodeID, not just address: if we already learned which node lives at this address
	// (from a prior NODE_INFO read) and we already hold a link to it in either direction, don't
	// re-dial. Otherwise we'd reconnect every scan and the collapse rule would drop it again — a
	// busy loop against an already-connected peer.
	linked := known && n.haveLinkToNodeLocked(id)
	if connected || n.connecting[addr] || linked {
		n.mu.Unlock()
		if known {
			n.router.Neighbors.SetRSSI(id, rssi) // keep a known neighbor's RSSI fresh
		}
		return
	}
	n.connecting[addr] = true
	n.mu.Unlock()
	n.logf("scan found addr=%s rssi=%d", addr, rssi)
	n.helper.connect(addr)
}

// haveLinkToNodeLocked reports whether we already have a link to [id] in either direction. Caller
// must hold n.mu.
func (n *Node) haveLinkToNodeLocked(id core.NodeID) bool {
	for _, l := range n.peers {
		if l.peerID == id {
			return true
		}
	}
	for _, pid := range n.serverPeerIDs {
		if pid == id {
			return true
		}
	}
	return false
}

// onPeerConnected finalizes an outgoing connection the helper completed (connect + discover +
// NODE_INFO read + MTU + subscribe). [nodeInfo] is the peer's NODE_INFO value.
func (n *Node) onPeerConnected(addr string, mtu int, nodeInfo []byte) {
	var peerID core.NodeID
	var peerPub []byte
	if ni, ok := core.DecodeNodeInfo(nodeInfo); ok {
		peerID = core.NodeIDFromPubKey(ni.PubKey)
		peerPub = ni.PubKey
		n.logf("peer node_id=%s caps=0x%04x", peerID, ni.ProvisionalCaps)
	}

	cleanup := func() {
		n.mu.Lock()
		delete(n.connecting, addr)
		n.mu.Unlock()
		n.helper.disconnect(addr)
	}

	if len(n.allowlist) > 0 && !n.allowlist[peerID] {
		n.logf("peer %s (node_id=%s) not in allowlist — disconnecting", addr, peerID)
		cleanup()
		return
	}
	if peerID == n.nodeID {
		n.logf("connected to self — disconnecting")
		cleanup()
		return
	}
	n.mu.Lock()
	// Remember which node lives at this address so onScan can dedup it later without re-dialing,
	// even on the collapse/duplicate paths below that don't keep the link.
	n.addrNode[addr] = peerID
	rssi := n.addrRSSI[addr] // advertisement RSSI stashed by onScan (0 if this peer was never scanned)
	dup := false
	for a, l := range n.peers {
		if l.peerID == peerID && a != addr {
			dup = true
			break
		}
	}
	if dup {
		n.mu.Unlock()
		n.logf("peer node_id=%s already connected — dropping duplicate addr=%s", peerID, addr)
		cleanup()
		return
	}
	// §4.4 collapse: if this node already dialed our GATT server (we hold an inbound link from it)
	// and our NodeID is the larger, drop this outbound and keep the inbound — exactly one of the two
	// nodes does this, leaving a single link instead of a redundant mutual pair.
	haveInbound := false
	for _, id := range n.serverPeerIDs {
		if id == peerID {
			haveInbound = true
			break
		}
	}
	if haveInbound && bytes.Compare(n.nodeID[:], peerID[:]) >= 0 {
		n.mu.Unlock()
		n.logf("peer node_id=%s inbound link exists and we are larger — keeping inbound, dropping outbound addr=%s", peerID, addr)
		cleanup()
		return
	}
	link := &MacPeerLink{
		peerID:      peerID,
		addr:        addr,
		helper:      n.helper,
		mtu:         mtu,
		txPHY:       core.PHY1M,
		rxPHY:       core.PHY1M,
		rssi:        rssi,
		connectedAt: time.Now(),
	}
	n.peers[addr] = link
	delete(n.connecting, addr)
	n.logMultiLinkLocked()
	n.mu.Unlock()
	n.logf("connected peer=%s mtu=%d tx-phy=1M rx-phy=1M", peerID, mtu)

	if n.onPeerConnect != nil {
		n.onPeerConnect(peerID)
	}
	n.router.Neighbors.Upsert(core.Neighbor{
		ID: peerID, Direction: core.DirectionOutgoing, TxPHY: core.PHY1M, RxPHY: core.PHY1M, PublicKey: peerPub, RSSI: rssi,
	})
	n.requestAnnounce()
}

// onPeerDisconnected handles a dropped outgoing connection. [reason] is the BLE disconnect reason
// reported by the helper ("clean" for a local close).
func (n *Node) onPeerDisconnected(addr, reason string) {
	n.mu.Lock()
	link := n.peers[addr]
	delete(n.peers, addr)
	delete(n.connecting, addr)
	// Is the node still reachable by another link (its inbound connection to our GATT server, or a
	// second outbound)? The collapse rule deliberately drops a redundant outbound while keeping the
	// inbound, so a dropped link here does not necessarily mean the peer is gone.
	stillLinked := link != nil && n.haveLinkToNodeLocked(link.peerID)
	n.mu.Unlock()
	if link == nil {
		return
	}
	if reason == "" {
		reason = "unknown"
	}
	dur := time.Since(link.connectedAt).Round(time.Millisecond)
	if stillLinked {
		// Redundant link closed (typically a §4.4 collapse) — the peer is still connected the other
		// way, so keep the neighbor and don't signal a disconnect to the app/UI.
		n.logf("dropped redundant link to peer=%s after=%s reason=%q (still connected via another link) addr=%s",
			link.peerID, dur, reason, addr)
		return
	}
	n.logf("disconnected peer=%s after=%s reason=%q addr=%s", link.peerID, dur, reason, addr)
	n.router.Neighbors.Remove(link.peerID)
	if n.onPeerDisconnect != nil {
		n.onPeerDisconnect(link.peerID)
	}
}

// onSubscribed records a central that subscribed to our PACKET_OUT (peripheral role).
func (n *Node) onSubscribed(centralID string) {
	if centralID == "" {
		return
	}
	n.mu.Lock()
	n.subscribers[centralID] = true
	n.mu.Unlock()
	n.logf("server: peer %s subscribed to PACKET_OUT", centralID)
	n.requestAnnounce()
}

// onUnsubscribed handles a central dropping its PACKET_OUT subscription.
func (n *Node) onUnsubscribed(centralID string) {
	n.mu.Lock()
	delete(n.subscribers, centralID)
	delete(n.serverPeerIDs, centralID)
	n.mu.Unlock()
	n.logf("server: peer %s unsubscribed from PACKET_OUT", centralID)
}

// handleIncomingFrameForAddr routes a PACKET_OUT indication received from a connected peer (central
// role), resolving the peer's NodeID from the addr so dedup/reassembly key consistently.
func (n *Node) handleIncomingFrameForAddr(addr string, data []byte) {
	n.mu.Lock()
	link := n.peers[addr]
	n.mu.Unlock()
	if link != nil {
		peerID := link.peerID
		n.handleIncomingFrame(data, &peerID, "")
		return
	}
	n.handleIncomingFrame(data, nil, addr)
}

// handleIncomingFrame receives a raw GATT frame, reassembles, decodes and routes.
func (n *Node) handleIncomingFrame(raw []byte, fromPeer *core.NodeID, fromAddr string) {
	frame, err := core.DecodeFrame(raw)
	if err != nil {
		n.logf("decode frame error: %v", err)
		return
	}
	peerKey := fromAddr
	if fromPeer != nil {
		peerKey = fromPeer.String()
	}
	data, complete, err := n.reassem.AddFrame(peerKey, frame)
	if err != nil {
		n.logf("reassemble error: %v", err)
		return
	}
	if !complete {
		return
	}

	dg, err := core.DecodeDatagram(data)
	if err != nil {
		n.logf("decode datagram error: %v", err)
		return
	}
	n.logf("rx datagram id=%x source=%s ttl=%d path=%v", dg.ID[:4], dg.Source, dg.TTL, nodeIDs(dg.Path))

	// Learn the directly-connected neighbor (last trace hop, or source if fresh) so
	// the neighbor table — and our ANNOUNCE — stay populated even when all peers
	// connected inbound to us.
	n.learnNeighbor(dg, fromAddr)

	actions := n.router.HandleDatagram(dg, fromPeer)
	n.executeActions(actions)
}

// isDuplicateDrop reports whether the router dropped the packet as an already-seen duplicate.
func isDuplicateDrop(actions []core.Action) bool {
	for _, a := range actions {
		if a.Type == core.ActionDrop && a.Reason == string(core.DropDuplicate) {
			return true
		}
	}
	return false
}

func (n *Node) learnNeighbor(dg core.Datagram, fromAddr string) {
	var nb core.NodeID
	if len(dg.Path) > 0 {
		nb = dg.Path[len(dg.Path)-1]
	} else {
		nb = dg.Source
	}
	var zero core.NodeID
	if nb == n.nodeID || nb == zero {
		return
	}
	if fromAddr != "" {
		var dropOutbound string
		n.mu.Lock()
		if n.subscribers[fromAddr] {
			n.serverPeerIDs[fromAddr] = nb
			// §4.4 collapse from the inbound side: this node has now dialed our GATT server. If we
			// also hold an outbound link to it and our NodeID is the larger, drop the outbound and
			// keep this inbound (covers the inbound arriving after the outbound was established).
			if bytes.Compare(n.nodeID[:], nb[:]) >= 0 {
				for a, link := range n.peers {
					if link.peerID == nb {
						dropOutbound = a
						break
					}
				}
			}
			n.logMultiLinkLocked()
		}
		n.mu.Unlock()
		if dropOutbound != "" {
			n.logf("peer node_id=%s now inbound and we are larger — dropping redundant outbound addr=%s", nb, dropOutbound)
			n.helper.disconnect(dropOutbound)
		}
	}
	if _, ok := n.router.Neighbors.Get(nb); ok {
		n.router.Neighbors.Touch(nb)
	} else {
		n.router.Neighbors.Upsert(core.Neighbor{ID: nb, Direction: core.DirectionIncoming})
	}
}

func (n *Node) isReachablePeer(id core.NodeID) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, link := range n.peers {
		if link.peerID == id {
			return true
		}
	}
	for _, peerID := range n.serverPeerIDs {
		if peerID == id {
			return true
		}
	}
	return false
}

func (n *Node) selectTraceRoute(dst core.NodeID) ([]core.NodeID, bool) {
	if n.isReachablePeer(dst) {
		return []core.NodeID{dst}, true
	}
	path := n.router.Topology.BFSPath(n.nodeID, dst)
	if len(path) == 0 {
		return nil, false
	}
	if !n.isReachablePeer(path[0]) {
		return nil, false
	}
	return path, true
}

// RSSIFor returns the best known signal strength (dBm) for id from the neighbor
// table or its live link, or 0 when no sample is available. RSSI is only ever
// sampled from BLE advertisement scans, so a dialed peer with no recent scan
// reports 0 (unknown).
func (n *Node) RSSIFor(id core.NodeID) int { return n.rssiForPeer(&id) }

func (n *Node) rssiForPeer(id *core.NodeID) int {
	if id == nil {
		return 0
	}
	if nb, ok := n.router.Neighbors.Get(*id); ok && nb.RSSI != 0 {
		return nb.RSSI
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, link := range n.peers {
		if link.peerID == *id {
			return link.rssi
		}
	}
	return 0
}

func clampRSSI(v int) int {
	if v < -128 {
		return -128
	}
	if v > 127 {
		return 127
	}
	return v
}

func (n *Node) executeActions(actions []core.Action) {
	for _, a := range actions {
		switch a.Type {
		case core.ActionDeliverLocal:
			n.deliverLocal(a.Datagram)
		case core.ActionRelayFlood:
			go func(a core.Action) {
				time.Sleep(n.router.FloodJitter())
				n.relayFlood(a)
			}(a)
		// ACK and next-hop relay both notify a subscribed central (CoreBluetooth
		// UpdateValue). executeActions runs inside the PACKET_IN write callback — a
		// CoreBluetooth delegate callback — and re-entering CoreBluetooth from there
		// crashes (cgo SIGSEGV). Dispatch off the callback goroutine, like RelayFlood.
		case core.ActionRelayNextHop:
			go n.relayNextHop(a)
		case core.ActionSendAck:
			go n.sendAck(a)
		case core.ActionDrop:
			n.logf("drop reason=%s", a.Reason)
		}
	}
}

func (n *Node) deliverLocal(dg core.Datagram) {
	if dg.Protocol == core.ProtocolSidepathControl {
		ctrl, err := core.DecodeControl(dg.Payload)
		if err == nil {
			switch ctrl.Kind {
			case core.ControlAck:
				n.logf("ack received from=%s", dg.Source)
				if n.onAck != nil {
					var ack core.AckBody
					if err := cbor.Unmarshal(ctrl.Body, &ack); err == nil {
						n.onAck(ack.AckedID, dg.Source)
					}
				}
			case core.ControlAnnounce:
				return
			case core.ControlTraceRequest:
				if err := n.returnTrace(dg, ctrl.Body); err != nil {
					n.logf("trace response error: %v", err)
				}
			case core.ControlTraceResponse:
				n.handleTraceResponse(ctrl.Body)
			}
		}
		return
	}
	n.logf("deliver protocol=0x%04x payload=%q path=%v", dg.Protocol, string(dg.Payload), nodeIDs(dg.Path))
	if n.onMessage != nil {
		n.onMessage(dg)
	}
}

func (n *Node) returnTrace(req core.Datagram, body []byte) error {
	recvAt := time.Now()
	var tr core.TraceRequestBody
	if err := cbor.Unmarshal(body, &tr); err != nil {
		return err
	}
	resp := core.TraceResponseBody{Tag: tr.Tag, Metric: tr.Metric, ForwardPath: append([]core.NodeID(nil), req.Path...), ForwardSamples: tr.ForwardSamples}
	payload, err := resp.ToControl()
	if err != nil {
		return err
	}
	route := reverseTraceRoute(req.Path, req.Source)
	dg := core.Datagram{Version: core.DatagramVersion, ID: core.NewDatagramID(), Source: n.nodeID, Destination: req.Source, TTL: uint8(len(route)), Route: route, Protocol: core.ProtocolSidepathControl, Payload: payload}
	n.router.MarkOriginated(dg.ID)
	n.logf("trace request tag=0x%08x from=%s fwd-path=%v return-route=%v — replying", tr.Tag, req.Source, nodeIDs(req.Path), nodeIDs(route))
	// returnTrace runs inside the inbound PACKET_IN write callback (a CoreBluetooth
	// delegate callback); notifying the central from there re-enters CoreBluetooth and
	// crashes. Send the response from a separate goroutine.
	go func() {
		if err := n.transmitToRoute(dg); err != nil {
			n.logf("trace response tag=0x%08x send error after %s: %v", tr.Tag, time.Since(recvAt), err)
			return
		}
		n.logf("trace response tag=0x%08x sent to=%s in %s", tr.Tag, req.Source, time.Since(recvAt))
	}()
	return nil
}

func reverseTraceRoute(trace []core.NodeID, dst core.NodeID) []core.NodeID {
	if len(trace) == 0 {
		return []core.NodeID{dst}
	}
	// `trace` is the forward path of intermediate relays only (the destination is never recorded),
	// so the return route is the reversed relay list followed by the trace originator.
	route := make([]core.NodeID, len(trace), len(trace)+1)
	for i, hop := range trace {
		route[len(trace)-1-i] = hop
	}
	if len(route) == 0 || route[len(route)-1] != dst {
		route = append(route, dst)
	}
	return route
}

func (n *Node) relayFlood(a core.Action) {
	data, err := a.Datagram.Encode()
	if err != nil {
		return
	}
	frames := core.FragmentDatagramNew(data, core.MaxFrameSize)
	n.floodFrames(frames, a.ExcludePeer)
}

func (n *Node) relayNextHop(a core.Action) {
	if a.NextHop == nil {
		return
	}
	data, err := a.Datagram.Encode()
	if err != nil {
		return
	}
	frames := core.FragmentDatagramNew(data, core.MaxFrameSize)
	if n.sendToNode(frames, *a.NextHop) {
		return
	}
	n.logf("relay-next-hop: peer %s not connected", *a.NextHop)
}

func (n *Node) sendAck(a core.Action) {
	n.logf("send ack route=%v", nodeIDs(a.Datagram.Route))
	n.relayNextHop(a)
	// Fallback to flood if next-hop missing
	if a.NextHop == nil {
		n.relayFlood(core.Action{Type: core.ActionRelayFlood, Datagram: a.Datagram})
	}
}

func (n *Node) announceLoop(ctx context.Context) {
	seq := uint32(0)
	send := func() {
		n.syncNeighborDirections()
		dg, err := n.router.BuildAnnounce(n.caps, n.announceEpoch, seq)
		if err != nil {
			n.logf("announce build error: %v", err)
			return
		}
		seq++
		data, err := dg.Encode()
		if err != nil {
			n.logf("announce encode error: %v", err)
			return
		}
		frames := core.FragmentDatagramNew(data, core.MaxFrameSize)
		n.floodFrames(frames, nil)
		n.logf("announce sent epoch=%d seq=%d neighbors=%d", n.announceEpoch, seq-1, len(n.router.Neighbors.IDs()))
	}
	send()
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-n.announceNow:
			send()
		case <-ticker.C:
			send()
		}
	}
}

func (n *Node) requestAnnounce() {
	select {
	case n.announceNow <- struct{}{}:
	default:
	}
}

// buildNodeInfo encodes the NODE_INFO characteristic (see core.EncodeNodeInfo).
func (n *Node) buildNodeInfo() []byte {
	return core.EncodeNodeInfo(n.identity.Pub, n.caps)
}

// SendText sends a plaintext test message to dst (zero = broadcast).
func (n *Node) SendText(dst core.NodeID, text string, ttl uint8) error {
	ctx := core.ChatContext{DatagramID: core.NewDatagramID(), Source: n.nodeID, Destination: core.BroadcastNodeID}
	payload, err := core.BuildPublicText(n.identity, ctx, text, time.Now().Unix())
	if err != nil {
		return err
	}
	dg := core.Datagram{Version: core.DatagramVersion, ID: ctx.DatagramID, Source: n.nodeID, Destination: core.BroadcastNodeID, TTL: ttl, Protocol: core.ProtocolSidepathChat, Payload: payload}
	return n.transmit(dg)
}

// SendMeshCoreRaw floods an opaque, complete MeshCore over-the-air packet into
// the Sidepath mesh as a v3 MESHCORE_PACKET datagram. The bytes are carried
// verbatim; Sidepath routing treats the payload as opaque.
func (n *Node) SendMeshCoreRaw(payload []byte) error {
	_, err := n.SendMeshCoreRawWithInfo(payload)
	return err
}

func (n *Node) SendMeshCoreRawWithInfo(payload []byte) (TransmitInfo, error) {
	dg := n.router.NewBroadcast(core.ProtocolMeshCorePacket, payload, core.DefaultFloodTTL)
	return n.transmitWithInfo(dg)
}

// SendMeshCoreRawTo sends an opaque MeshCore packet to a specific reachable
// Sidepath neighbor as a v3 MESHCORE_PACKET datagram.
func (n *Node) SendMeshCoreRawTo(dst core.NodeID, payload []byte) error {
	_, err := n.SendMeshCoreRawToWithInfo(dst, payload)
	return err
}

func (n *Node) SendMeshCoreRawToWithInfo(dst core.NodeID, payload []byte) (TransmitInfo, error) {
	dg, ok := n.router.NewUnicast(dst, core.ProtocolMeshCorePacket, payload, 0, core.DefaultFloodTTL, true)
	if !ok {
		return TransmitInfo{}, fmt.Errorf("no direct Sidepath route to %s", dst)
	}
	return n.transmitWithInfo(dg)
}

// SendBridgedAck returns an ACK_BRIDGED control datagram to dst — the original sender of a channel
// message this node just relayed onto an external network (e.g. MeshCore). meshHash is a short
// correlation hash of the emitted external packet. Informational only.
func (n *Node) SendBridgedAck(dst core.NodeID, bridgedID core.DatagramID, meshHash []byte) error {
	dg, ok := n.router.BuildBridged(dst, bridgedID, meshHash)
	if !ok {
		return fmt.Errorf("no route to bridged sender %s", dst)
	}
	return n.transmit(dg)
}

// MeshCoreCandidatesForHash resolves a MeshCore node hash (a 1–2 byte public-key
// prefix) to every Sidepath node whose public key matches the prefix AND to which we
// currently have a route — direct neighbors or multi-hop source routes via topology.
//
// MeshCore direct messages address the recipient by only the first byte(s) of its
// public key, so the prefix can match multiple distinct nodes on our mesh. Returning
// all of them lets the caller fan the message out to every plausible target; the
// (wrong) recipients simply fail the MeshCore MAC check and drop it.
func (n *Node) MeshCoreCandidatesForHash(hash []byte) []core.NodeID {
	if len(hash) == 0 || len(hash) > core.PublicKeyBytes {
		return nil
	}
	seen := make(map[core.NodeID]bool)
	var out []core.NodeID
	consider := func(id core.NodeID) {
		if id == n.nodeID || seen[id] {
			return
		}
		pub := n.router.PublicKeyFor(id)
		if len(pub) < len(hash) || !bytes.Equal(pub[:len(hash)], hash) {
			return
		}
		if len(n.router.SelectRoute(id)) == 0 {
			return // no direct/source route to this node
		}
		seen[id] = true
		out = append(out, id)
	}
	// Neighbors first (covers inbound-only peers not yet in topology), then the wider
	// topology for multi-hop reachable nodes.
	for _, nb := range n.router.Neighbors.All() {
		consider(nb.ID)
	}
	for _, tn := range n.router.Topology.Nodes() {
		consider(tn.ID)
	}
	return out
}

// SendTyping sends an ephemeral "I'm typing" hint to dst (empty TYPING payload,
// never ACKed). Best-effort — used to show activity while preparing a reply.
func (n *Node) SendTyping(dst core.NodeID) error {
	ctx := core.ChatContext{DatagramID: core.NewDatagramID(), Source: n.nodeID, Destination: dst}
	payload, err := core.BuildTyping(n.identity, ctx, time.Now().Unix())
	if err != nil {
		return err
	}
	dg, ok := n.router.NewUnicast(dst, core.ProtocolSidepathChat, payload, 0, 4, false)
	if !ok {
		return fmt.Errorf("no route to %s", dst)
	}
	dg.ID = ctx.DatagramID
	return n.transmit(dg)
}

// SendChatTo sends an end-to-end encrypted direct message to dst, encrypted to
// recipientPub (the recipient's 32-byte Ed25519 public key). For replies the
// public key is the one carried in the inbound envelope.
func (n *Node) SendChatTo(dst core.NodeID, recipientPub []byte, text string) error {
	_, err := n.SendChatToWithID(dst, recipientPub, text)
	return err
}

// SendChatToWithID is SendChatTo but returns the sent datagram's ID, so callers
// can correlate the ACK (the message is sent with FlagAckRequested).
func (n *Node) SendChatToWithID(dst core.NodeID, recipientPub []byte, text string) (core.DatagramID, error) {
	ctx := core.ChatContext{DatagramID: core.NewDatagramID(), Source: n.nodeID, Destination: dst}
	env, err := core.SealDirectText(n.identity, recipientPub, ctx, text, time.Now().Unix())
	if err != nil {
		return core.DatagramID{}, err
	}
	dg, ok := n.router.NewUnicast(dst, core.ProtocolSidepathChat, env, uint16(core.FlagAckRequested), 4, false)
	if !ok {
		return core.DatagramID{}, fmt.Errorf("no route to %s", dst)
	}
	dg.ID = ctx.DatagramID
	if err := n.transmit(dg); err != nil {
		return core.DatagramID{}, err
	}
	return dg.ID, nil
}

// SendChat sends an encrypted DM to dst, resolving its public key from the
// topology (learned via the node's signed ANNOUNCE). Returns an error if dst's
// key isn't known yet.
func (n *Node) SendChat(dst core.NodeID, text string) error {
	_, err := n.SendChatWithID(dst, text)
	return err
}

// SendChatWithID is SendChat but returns the sent datagram's ID for ACK
// correlation.
func (n *Node) SendChatWithID(dst core.NodeID, text string) (core.DatagramID, error) {
	pub := n.router.PublicKeyFor(dst)
	if pub == nil {
		return core.DatagramID{}, fmt.Errorf("no public key known for %s (not in topology yet)", dst)
	}
	return n.SendChatToWithID(dst, pub, text)
}

// RouteTo returns the currently selected source route to dst. A nil route with
// ok=true means dst is a direct neighbor; ok=false means no known route.
func (n *Node) RouteTo(dst core.NodeID) ([]core.NodeID, bool) {
	return n.selectTraceRoute(dst)
}

// SendTrace sends a trace request to dst. If route is empty, the current topology
// is used to select a direct/source route. Trace never falls back to flood:
// only repeaters on the selected track may relay it.
func (n *Node) SendTrace(dst core.NodeID, route []core.NodeID) (uint32, error) {
	tag := core.RandomUint32()
	if len(route) == 0 {
		route, _ = n.selectTraceRoute(dst)
		if len(route) == 0 {
			return 0, fmt.Errorf("no route known to %s", dst)
		}
	} else if route[len(route)-1] != dst {
		route = append(append([]core.NodeID(nil), route...), dst)
	}
	payload, err := (core.TraceRequestBody{Tag: tag, Metric: core.TraceMetricRSSIDBM}).ToControl()
	if err != nil {
		return 0, err
	}
	dg := core.Datagram{Version: core.DatagramVersion, ID: core.NewDatagramID(), Source: n.nodeID, Destination: dst, TTL: uint8(len(route)), Route: route, Protocol: core.ProtocolSidepathControl, Payload: payload}
	n.router.MarkOriginated(dg.ID)
	// Record the send time before transmitting so handleTraceResponse can compute round-trip time
	// even for a near-instant reply; drop it again if the send fails.
	n.traceMu.Lock()
	n.traceStarts[tag] = time.Now()
	n.traceMu.Unlock()
	if err := n.transmitToRoute(dg); err != nil {
		n.traceMu.Lock()
		delete(n.traceStarts, tag)
		n.traceMu.Unlock()
		return 0, err
	}
	return tag, nil
}

// handleTraceResponse processes a response to a trace this node originated: it matches the response
// tag to the pending request to compute round-trip time, logs the result, and notifies any
// registered OnTrace callback. Responses whose tag has no pending request (already completed, or
// never sent by us) are logged and dropped. Runs on the inbound BLE callback goroutine.
func (n *Node) handleTraceResponse(body []byte) {
	var resp core.TraceResponseBody
	if err := cbor.Unmarshal(body, &resp); err != nil {
		n.logf("trace response decode error: %v", err)
		return
	}
	n.traceMu.Lock()
	start, ok := n.traceStarts[resp.Tag]
	if ok {
		delete(n.traceStarts, resp.Tag)
	}
	n.traceMu.Unlock()
	if !ok {
		n.logf("trace response tag=0x%08x ignored (no matching request)", resp.Tag)
		return
	}
	rtt := time.Since(start)
	n.logf("trace response tag=0x%08x received fwd-path=%v rtt=%s", resp.Tag, nodeIDs(resp.ForwardPath), rtt)
	if n.onTrace != nil {
		n.onTrace(resp, rtt)
	}
}

// PublicKeyFor returns a node's 32-byte Ed25519 public key from the topology, or nil.
func (n *Node) PublicKeyFor(id core.NodeID) []byte {
	return n.router.PublicKeyFor(id)
}

// sendData builds, originates and transmits a DATA packet.
func (n *Node) transmit(dg core.Datagram) error {
	_, err := n.transmitWithInfo(dg)
	return err
}

func (n *Node) transmitWithInfo(dg core.Datagram) (TransmitInfo, error) {
	n.router.MarkOriginated(dg.ID)
	data, err := dg.Encode()
	if err != nil {
		return TransmitInfo{}, err
	}
	frames := core.FragmentDatagramNew(data, core.MaxFrameSize)
	info := TransmitInfo{DatagramID: dg.ID, DatagramBytes: len(data), FragmentCount: len(frames)}
	n.floodFrames(frames, nil)
	return info, nil
}

func (n *Node) transmitToRoute(dg core.Datagram) error {
	if len(dg.Route) == 0 {
		return fmt.Errorf("source route is empty")
	}
	n.router.MarkOriginated(dg.ID)
	data, err := dg.Encode()
	if err != nil {
		return err
	}
	frames := core.FragmentDatagramNew(data, core.MaxFrameSize)
	firstHop := dg.Route[0]
	if n.sendToNode(frames, firstHop) {
		return nil
	}
	return fmt.Errorf("first hop %s not connected", firstHop)
}

// linkRef is one physical BLE link to a remote node: an outbound connection we dialed (outbound set)
// or an inbound subscriber that dialed us (centralID set). Sidepath (§4.4) treats every link to the
// same NodeID as an equivalent transport, so routing selects a link by NodeID and never depends on
// which one a packet arrived over. Collapsing a redundant inbound/outbound pair is an optimization,
// not a correctness requirement.
type linkRef struct {
	nodeID    core.NodeID  // zero when not yet learned (cannot be deduped by identity)
	outbound  *MacPeerLink // non-nil for an outbound link
	centralID string       // non-empty for an inbound (subscriber) link
}

// sendLink writes frames over a single physical link. Sends are fire-and-forget — failures surface
// asynchronously as disconnects, which prune the link so a later send naturally picks a backup.
func (n *Node) sendLink(ref linkRef, frames []core.Frame) {
	if ref.outbound != nil {
		n.sendFramesToLink(ref.outbound, frames)
	} else {
		n.notifyFrames(ref.centralID, frames)
	}
}

// linksToLocked snapshots every usable link to peer, outbound first (preferred) then inbound
// (backup). Caller holds n.mu.
func (n *Node) linksToLocked(peer core.NodeID) []linkRef {
	var refs []linkRef
	for _, l := range n.peers {
		if l.peerID == peer {
			refs = append(refs, linkRef{nodeID: peer, outbound: l})
		}
	}
	for cid, pid := range n.serverPeerIDs {
		if pid == peer && n.subscribers[cid] {
			refs = append(refs, linkRef{nodeID: peer, centralID: cid})
		}
	}
	return refs
}

// sendToNode routes frames to peer over any usable link, preferring the first (tasks 4, 5). Because
// sends are fire-and-forget the "retry" is link selection: a vanished outbound or unsubscribed inbound
// is pruned by the disconnect path, so the next call falls through to a live backup. Re-delivery over
// a different link is safe — relays and the recipient dedup on datagram ID (task 8).
func (n *Node) sendToNode(frames []core.Frame, peer core.NodeID) bool {
	n.mu.Lock()
	links := n.linksToLocked(peer)
	n.mu.Unlock()
	if len(links) == 0 {
		return false
	}
	n.sendLink(links[0], frames)
	return true
}

// logicalPeerLinksLocked returns one link per logical peer (distinct NodeID) for flooding, so a node
// reachable over both an inbound and an outbound link is flooded once, not once per physical link
// (task 7). Links whose NodeID is not yet known are each their own logical peer — we cannot dedup
// them, but we must still reach them. Caller holds n.mu.
func (n *Node) logicalPeerLinksLocked() []linkRef {
	var zero core.NodeID
	seen := make(map[core.NodeID]bool)
	var refs []linkRef
	for _, l := range n.peers {
		if l.peerID == zero {
			refs = append(refs, linkRef{outbound: l})
			continue
		}
		if seen[l.peerID] {
			continue
		}
		seen[l.peerID] = true
		refs = append(refs, linkRef{nodeID: l.peerID, outbound: l})
	}
	for cid := range n.subscribers {
		pid, known := n.serverPeerIDs[cid]
		if !known || pid == zero {
			refs = append(refs, linkRef{centralID: cid}) // NodeID unknown — reach it individually
			continue
		}
		if seen[pid] {
			continue
		}
		seen[pid] = true
		refs = append(refs, linkRef{nodeID: pid, centralID: cid})
	}
	return refs
}

// logMultiLinkLocked logs any NodeID currently reached over more than one physical link (an outbound
// we dialed plus an inbound it dialed). Debug visibility for §4.4 multi-link handling (task 11):
// messages keep flowing in this state because routing selects a link by NodeID — see sendToNode.
// Caller holds n.mu.
func (n *Node) logMultiLinkLocked() {
	var zero core.NodeID
	dirs := make(map[core.NodeID][]string)
	for addr, l := range n.peers {
		if l.peerID != zero {
			dirs[l.peerID] = append(dirs[l.peerID], "out:"+addr)
		}
	}
	for cid, pid := range n.serverPeerIDs {
		if pid != zero && n.subscribers[cid] {
			dirs[pid] = append(dirs[pid], "in:"+cid)
		}
	}
	for id, links := range dirs {
		if len(links) > 1 {
			n.logf("multi-link peer=%s links=%v (routing by NodeID, delivery unaffected)", id, links)
		}
	}
}

// floodFrames sends frames to every logical peer once, skipping exclude (split horizon, §10.2.5).
func (n *Node) floodFrames(frames []core.Frame, exclude *core.NodeID) {
	n.mu.Lock()
	refs := n.logicalPeerLinksLocked()
	n.mu.Unlock()
	var zero core.NodeID
	for _, ref := range refs {
		if exclude != nil && ref.nodeID != zero && ref.nodeID == *exclude {
			continue
		}
		n.sendLink(ref, frames)
	}
}

// sendFramesToLink writes frames to a connected peer (central role) via the helper. Multi-fragment
// transmissions use reliable (acknowledged) writes so the receiver can always reassemble.
func (n *Node) sendFramesToLink(link *MacPeerLink, frames []core.Frame) {
	reliable := len(frames) > 1
	for _, f := range frames {
		link.helper.sendCentral(link.addr, f.Encode(), reliable)
	}
}

// notifyFrames pushes frames to a subscribed central (peripheral role) as PACKET_OUT indications.
// The helper owns per-central ordering and CoreBluetooth flow control, so there is nothing to pace
// or retry here.
func (n *Node) notifyFrames(centralID string, frames []core.Frame) {
	for _, f := range frames {
		n.helper.sendPeripheral(centralID, f.Encode())
	}
}

// Neighbors returns the current neighbor table entries.
func (n *Node) Neighbors() []core.Neighbor {
	return n.router.Neighbors.All()
}

// Topology returns all known topology nodes.
func (n *Node) Topology() []core.TopoNode {
	return n.router.Topology.Nodes()
}

// ConnectedPeers returns the NodeIDs of currently connected outgoing peers.
func (n *Node) ConnectedPeers() []core.NodeID {
	n.mu.Lock()
	defer n.mu.Unlock()
	ids := make([]core.NodeID, 0, len(n.peers))
	for _, link := range n.peers {
		ids = append(ids, link.peerID)
	}
	return ids
}

// PeerInfo is a connected peer and the direction(s) of its BLE link(s).
type PeerInfo struct {
	ID        core.NodeID
	Direction string // "outbound", "inbound", or "in+out"
}

// syncNeighborDirections refreshes each neighbor's stored link direction from the live link set so a
// v3 ANNOUNCE reports out/in/both consistently with what PeerLinks shows the UI (§4.4). The stored
// direction is set once at connect time and would otherwise go stale — e.g. an outbound established
// first never upgrades to in+out when the peer later dials our GATT server.
func (n *Node) syncNeighborDirections() {
	for _, p := range n.PeerLinks() {
		dir := core.DirectionOutgoing
		switch p.Direction {
		case "inbound":
			dir = core.DirectionIncoming
		case "in+out":
			dir = core.DirectionBoth
		}
		n.router.Neighbors.SetDirection(p.ID, dir)
	}
}

// PeerLinks returns every connected peer — outbound (we dialed them) and inbound (they dialed our
// GATT server) — tagged with direction and sorted by NodeID. After §4.4 collapse a peer is usually
// reachable in only one direction, so the `peers` command must show both kinds to be complete.
func (n *Node) PeerLinks() []PeerInfo {
	n.mu.Lock()
	dir := make(map[core.NodeID]string)
	for _, link := range n.peers {
		dir[link.peerID] = "outbound"
	}
	for _, id := range n.serverPeerIDs {
		if dir[id] == "outbound" {
			dir[id] = "in+out"
		} else {
			dir[id] = "inbound"
		}
	}
	n.mu.Unlock()
	out := make([]PeerInfo, 0, len(dir))
	for id, d := range dir {
		out = append(out, PeerInfo{ID: id, Direction: d})
	}
	sort.Slice(out, func(i, j int) bool { return bytes.Compare(out[i].ID[:], out[j].ID[:]) < 0 })
	return out
}

// NodeID returns this node's ID.
func (n *Node) NodeID() core.NodeID {
	return n.nodeID
}

// Identity returns this node's Ed25519 identity (used for chat decryption).
func (n *Node) Identity() *core.Identity {
	return n.identity
}

// Description returns this node's free-form bio.
func (n *Node) Description() string {
	return n.description
}

// Name returns this node's primary display label.
func (n *Node) Name() string {
	return n.name
}

// Platform returns this node's OS/device string.
func (n *Node) Platform() string {
	return n.platform
}

// DescriptionFor resolves a peer's free-form bio (neighbor or topology).
func (n *Node) DescriptionFor(id core.NodeID) string {
	return n.router.DescriptionFor(id)
}

// NameFor resolves a peer's primary display label (neighbor, topology, or the
// deterministic default from its public key).
func (n *Node) NameFor(id core.NodeID) string {
	return n.router.NameFor(id)
}

// PlatformFor resolves a peer's OS/device string (neighbor or topology).
func (n *Node) PlatformFor(id core.NodeID) string {
	return n.router.PlatformFor(id)
}

// LoadOrCreateIdentity loads the Ed25519 identity seed from ~/.sidepath/seed or
// creates and persists a new one. NodeID is derived as pubkey[:8].
func LoadOrCreateIdentity() (*core.Identity, error) {
	path := filepath.Join(os.Getenv("HOME"), ".sidepath", "seed")
	return core.LoadOrCreateIdentity(path)
}

func LoadIncrementEpoch() (uint64, error) {
	path := filepath.Join(os.Getenv("HOME"), ".sidepath", "epoch")
	return core.LoadIncrementEpoch(path)
}

func nodeIDs(ids []core.NodeID) []string {
	out := make([]string, len(ids))
	for i, id := range ids {
		out[i] = id.String()
	}
	return out
}
