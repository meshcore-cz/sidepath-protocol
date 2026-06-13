//go:build darwin

// Package macos implements a BLEEdge node for macOS using CoreBluetooth via go-ble.
// CoreBluetooth does not expose LE Coded PHY control, so this node always
// operates in 1m mode and is NOT valid for the Long Range demonstration.
// It is useful for development and smoke-testing the routing engine over regular BLE.
package macos

import (
	"bytes"
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/burningtree/bleedge/core"
	"github.com/fxamacker/cbor/v2"
	"github.com/go-ble/ble"
	"github.com/go-ble/ble/darwin"
	"github.com/pkg/errors"
)

var (
	serviceUUID   = ble.MustParse("9B7E6A10-7D91-4C19-A3B8-6E2A11F3A001")
	nodeInfoUUID  = ble.MustParse("9B7E6A10-7D91-4C19-A3B8-6E2A11F3A002")
	packetInUUID  = ble.MustParse("9B7E6A10-7D91-4C19-A3B8-6E2A11F3A003")
	packetOutUUID = ble.MustParse("9B7E6A10-7D91-4C19-A3B8-6E2A11F3A004")
)

const (
	interFrameDelay       = 20 * time.Millisecond
	notifyRetryMinDelay   = 40 * time.Millisecond
	notifyRetryMaxDelay   = 500 * time.Millisecond
	notifyGlobalPace      = 8 * time.Millisecond
	notifyBackpressureLog = 2 * time.Second
	notifyLogAfterRetries = 3
)

// Node is a macOS BLEEdge node.
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

	mu sync.Mutex
	// CoreBluetooth's notification transmit queue is global-ish, not per subscriber.
	notifyMu     sync.Mutex
	notifyNextAt time.Time
	// peer addr string → link
	peers map[string]*MacPeerLink
	// notifiers for PACKET_OUT (server-side subscribers)
	notifiers map[string]*serverNotifier
	// server-side peer addr string → learned NodeID
	serverPeerIDs map[string]core.NodeID

	// joined channels keyed by lowercase PSK hex (see channels.go)
	channels map[string]*Channel

	logFn            func(string)
	onMessage        func(core.Datagram)
	onPeerConnect    func(core.NodeID)
	onPeerDisconnect func(core.NodeID)
}

type TransmitInfo struct {
	DatagramID    core.DatagramID
	DatagramBytes int
	FragmentCount int
}

type serverNotifier struct {
	addr      string
	notifier  ble.Notifier
	queue     chan []byte
	priorityQ chan []byte
	done      chan struct{}
}

// Config holds startup parameters.
type Config struct {
	Identity         *core.Identity
	Name             string // primary display label; empty = deterministic default from pubkey
	Platform         string // OS/device string; empty = core.PlatformDescription()
	Description      string // free-form bio for ANNOUNCE/NODE_INFO; default empty
	Caps             core.Capabilities
	Allowlist        []core.NodeID
	Verbose          bool
	AnnounceEpoch    uint64
	LogFn            func(string)
	OnMessage        func(dg core.Datagram) // called for locally delivered application datagrams
	OnPeerConnect    func(id core.NodeID)   // called when outgoing peer connects
	OnPeerDisconnect func(id core.NodeID)   // called when peer disconnects
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
		peers:         make(map[string]*MacPeerLink),
		notifiers:     make(map[string]*serverNotifier),
		serverPeerIDs: make(map[string]core.NodeID),
		channels:      make(map[string]*Channel),
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
	for _, id := range cfg.Allowlist {
		n.allowlist[id] = true
		n.router.Allowlist[id] = true
	}
	return n
}

func (n *Node) logf(format string, args ...any) {
	n.logFn(fmt.Sprintf(format, args...))
}

// Run initialises the darwin BLE device, registers the GATT service,
// and starts advertising + scanning concurrently. Blocks until ctx is done.
func (n *Node) Run(ctx context.Context) error {
	dev, err := darwin.NewDevice()
	if err != nil {
		return fmt.Errorf("cannot open CoreBluetooth device: %w", err)
	}
	ble.SetDefaultDevice(dev)

	n.logf("node id=%s  phy=1m (CoreBluetooth, no Coded PHY)", n.nodeID)
	n.logf("NOTE: macOS/CoreBluetooth does not support LE Coded PHY (Long Range) — 1M only")

	if err := ble.AddService(n.buildGATTService()); err != nil {
		return fmt.Errorf("AddService: %w", err)
	}

	// Announce loop
	go n.announceLoop(ctx)

	// Advertising (runs in background goroutine; errors are non-fatal)
	advCtx, advCancel := context.WithCancel(ctx)
	defer advCancel()
	go func() {
		n.logf("advertising BLEEdge service")
		err := ble.AdvertiseNameAndServices(advCtx, "BLEEdge", serviceUUID)
		if err != nil && !isCtxErr(err) {
			n.logf("advertiser stopped: %v", err)
		}
	}()

	// Scanner — finds BLEEdge peers and connects
	n.logf("scanning for BLEEdge peers")
	err = ble.Scan(ctx, false, n.onAdvertisement, n.scanFilter)
	if isCtxErr(err) {
		return nil
	}
	return err
}

// buildGATTService constructs the BLEEdge GATT service with all three characteristics.
func (n *Node) buildGATTService() *ble.Service {
	svc := ble.NewService(serviceUUID)

	// NODE_INFO — readable
	niChar := ble.NewCharacteristic(nodeInfoUUID)
	niChar.HandleRead(ble.ReadHandlerFunc(func(req ble.Request, rsp ble.ResponseWriter) {
		data := n.buildNodeInfo()
		rsp.Write(data)
	}))
	svc.AddCharacteristic(niChar)

	// PACKET_IN — writable (write without response)
	piChar := ble.NewCharacteristic(packetInUUID)
	piChar.HandleWrite(ble.WriteHandlerFunc(func(req ble.Request, rsp ble.ResponseWriter) {
		n.handleIncomingFrame(req.Data(), nil, req.Conn().RemoteAddr().String())
	}))
	svc.AddCharacteristic(piChar)

	// PACKET_OUT — notifiable
	poChar := ble.NewCharacteristic(packetOutUUID)
	poChar.HandleNotify(ble.NotifyHandlerFunc(func(req ble.Request, notifier ble.Notifier) {
		addr := req.Conn().RemoteAddr().String()
		sn := n.newServerNotifier(addr, notifier)
		n.mu.Lock()
		if old := n.notifiers[addr]; old != nil {
			old.close()
		}
		n.notifiers[addr] = sn
		n.mu.Unlock()
		n.logf("server: peer %s subscribed to PACKET_OUT", addr)
		<-notifier.Context().Done()
		sn.close()
		n.mu.Lock()
		if n.notifiers[addr] == sn {
			delete(n.notifiers, addr)
		}
		delete(n.serverPeerIDs, addr)
		n.mu.Unlock()
		n.logf("server: peer %s unsubscribed from PACKET_OUT", addr)
	}))
	svc.AddCharacteristic(poChar)

	return svc
}

// scanFilter accepts only advertisements containing the BLEEdge service UUID.
func (n *Node) scanFilter(adv ble.Advertisement) bool {
	for _, u := range adv.Services() {
		if u.Equal(serviceUUID) {
			return true
		}
	}
	return false
}

// onAdvertisement is called for each matching advertisement.
// It initiates a connection in a new goroutine.
func (n *Node) onAdvertisement(adv ble.Advertisement) {
	addr := adv.Addr().String()
	n.mu.Lock()
	_, already := n.peers[addr]
	n.mu.Unlock()
	if already {
		return
	}

	n.logf("scan found addr=%s rssi=%d", addr, adv.RSSI())
	go n.connectPeer(addr, adv)
}

func (n *Node) connectPeer(addr string, adv ble.Advertisement) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	filter := func(a ble.Advertisement) bool { return a.Addr().String() == addr }
	cln, err := ble.Connect(ctx, filter)
	if err != nil {
		n.logf("connect %s failed: %v", addr, err)
		return
	}

	// Discover the full profile
	p, err := cln.DiscoverProfile(true)
	if err != nil {
		n.logf("discover profile %s failed: %v", addr, cln.Addr())
		cln.CancelConnection()
		return
	}

	svc := p.Find(ble.NewService(serviceUUID))
	if svc == nil {
		n.logf("peer %s has no BLEEdge service", addr)
		cln.CancelConnection()
		return
	}

	gattSvc, ok := svc.(*ble.Service)
	if !ok {
		cln.CancelConnection()
		return
	}

	var nodeInfoChar, packetInChar, packetOutChar *ble.Characteristic
	for _, c := range gattSvc.Characteristics {
		switch {
		case c.UUID.Equal(nodeInfoUUID):
			nodeInfoChar = c
		case c.UUID.Equal(packetInUUID):
			packetInChar = c
		case c.UUID.Equal(packetOutUUID):
			packetOutChar = c
		}
	}

	if packetInChar == nil {
		n.logf("peer %s missing PACKET_IN characteristic", addr)
		cln.CancelConnection()
		return
	}

	// Read NODE_INFO
	var peerID core.NodeID
	var peerPub []byte
	if nodeInfoChar != nil {
		data, err := cln.ReadCharacteristic(nodeInfoChar)
		if err == nil {
			if ni, ok := core.DecodeNodeInfo(data); ok {
				peerID = core.NodeIDFromPubKey(ni.PubKey)
				peerPub = ni.PubKey
				n.logf("peer node_id=%s caps=0x%04x", peerID, ni.ProvisionalCaps)
			}
		}
	}

	// Allowlist check
	if len(n.allowlist) > 0 && !n.allowlist[peerID] {
		n.logf("peer %s (node_id=%s) not in allowlist — disconnecting", addr, peerID)
		cln.CancelConnection()
		return
	}

	// Whoever discovers a peer connects to it. BLE discovery can be asymmetric, so we
	// must NOT gate on NodeID ordering (that risks a deadlock where the only node that
	// can see a peer is the one told to wait). Drop self-connections and duplicates.
	if peerID == n.nodeID {
		n.logf("connected to self — disconnecting")
		cln.CancelConnection()
		return
	}
	n.mu.Lock()
	dup := false
	for a, l := range n.peers {
		if l.peerID == peerID && a != addr {
			dup = true
			break
		}
	}
	n.mu.Unlock()
	if dup {
		n.logf("peer node_id=%s already connected — dropping duplicate addr=%s", peerID, addr)
		cln.CancelConnection()
		return
	}

	// Negotiate MTU
	mtu, err := cln.ExchangeMTU(512)
	if err != nil {
		mtu = 23
	}
	n.logf("connected peer=%s mtu=%d tx-phy=1M rx-phy=1M", peerID, mtu)

	link := &MacPeerLink{
		peerID: peerID,
		addr:   addr,
		cln:    cln,
		piChar: packetInChar,
		mtu:    mtu,
		txPHY:  core.PHY1M,
		rxPHY:  core.PHY1M,
		rssi:   adv.RSSI(),
	}

	n.mu.Lock()
	n.peers[addr] = link
	n.mu.Unlock()

	if n.onPeerConnect != nil {
		n.onPeerConnect(peerID)
	}

	n.router.Neighbors.Upsert(core.Neighbor{
		ID:        peerID,
		Direction: core.DirectionOutgoing,
		RSSI:      adv.RSSI(),
		TxPHY:     core.PHY1M,
		RxPHY:     core.PHY1M,
		PublicKey: peerPub,
	})

	// Subscribe to PACKET_OUT
	if packetOutChar != nil {
		cln.Subscribe(packetOutChar, false, func(data []byte) {
			n.handleIncomingFrame(data, &peerID, "")
		})
	}

	// Wait for disconnect
	<-cln.Disconnected()
	n.logf("disconnected peer=%s", peerID)
	n.mu.Lock()
	delete(n.peers, addr)
	n.mu.Unlock()
	n.router.Neighbors.Remove(peerID)
	if n.onPeerDisconnect != nil {
		n.onPeerDisconnect(peerID)
	}
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
		n.mu.Lock()
		if _, ok := n.notifiers[fromAddr]; ok {
			n.serverPeerIDs[fromAddr] = nb
		}
		n.mu.Unlock()
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
	if dg.Protocol == core.ProtocolBLEEdgeControl {
		ctrl, err := core.DecodeControl(dg.Payload)
		if err == nil {
			switch ctrl.Kind {
			case core.ControlAck:
				n.logf("ack received from=%s", dg.Source)
			case core.ControlAnnounce:
				return
			case core.ControlTraceRequest:
				if err := n.returnTrace(dg, ctrl.Body); err != nil {
					n.logf("trace response error: %v", err)
				}
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
	dg := core.Datagram{Version: core.DatagramVersion, ID: core.NewDatagramID(), Source: n.nodeID, Destination: req.Source, TTL: uint8(len(route)), Route: route, Protocol: core.ProtocolBLEEdgeControl, Payload: payload}
	n.router.MarkOriginated(dg.ID)
	// returnTrace runs inside the inbound PACKET_IN write callback (a CoreBluetooth
	// delegate callback); notifying the central from there re-enters CoreBluetooth and
	// crashes. Send the response from a separate goroutine.
	go func() {
		if err := n.transmitToRoute(dg); err != nil {
			n.logf("trace response send error: %v", err)
		}
	}()
	return nil
}

func reverseTraceRoute(trace []core.NodeID, dst core.NodeID) []core.NodeID {
	if len(trace) == 0 {
		return []core.NodeID{dst}
	}
	hops := trace[:len(trace)-1]
	route := make([]core.NodeID, len(hops), len(hops)+1)
	for i, hop := range hops {
		route[len(hops)-1-i] = hop
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
	n.mu.Lock()
	links := make([]*MacPeerLink, 0, len(n.peers))
	for addr, link := range n.peers {
		if a.ExcludePeer != nil && link.peerID == *a.ExcludePeer {
			continue // don't relay back to incoming peer
		}
		links = append(links, link)
		_ = addr
	}
	notifiers := make([]*serverNotifier, 0, len(n.notifiers))
	for _, notifier := range n.notifiers {
		notifiers = append(notifiers, notifier)
	}
	n.mu.Unlock()

	for _, link := range links {
		n.sendFramesToLink(link, frames)
	}
	// Also notify server-side subscribers
	priority := a.Datagram.Protocol == core.ProtocolBLEEdgeChat || a.Datagram.Protocol == core.ProtocolBLEEdgeControl
	for _, notifier := range notifiers {
		n.notifyFrames(notifier, frames, priority)
	}
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
	if n.sendFramesToPeer(frames, *a.NextHop) {
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
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			dg, err := n.router.BuildAnnounce(n.caps, n.announceEpoch, seq)
			if err != nil {
				continue
			}
			seq++
			n.router.MarkOriginated(dg.ID)
			data, err := dg.Encode()
			if err != nil {
				continue
			}
			frames := core.FragmentDatagramNew(data, core.MaxFrameSize)
			n.mu.Lock()
			links := make([]*MacPeerLink, 0, len(n.peers))
			for _, link := range n.peers {
				links = append(links, link)
			}
			notifiers := make([]*serverNotifier, 0, len(n.notifiers))
			for _, notifier := range n.notifiers {
				notifiers = append(notifiers, notifier)
			}
			n.mu.Unlock()
			for _, link := range links {
				n.sendFramesToLink(link, frames)
			}
			for _, notifier := range notifiers {
				n.notifyFrames(notifier, frames, true)
			}
		}
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
	dg := core.Datagram{Version: core.DatagramVersion, ID: ctx.DatagramID, Source: n.nodeID, Destination: core.BroadcastNodeID, TTL: ttl, Protocol: core.ProtocolBLEEdgeChat, Payload: payload}
	return n.transmit(dg)
}

// SendMeshCoreRaw floods an opaque, complete MeshCore over-the-air packet into
// the BLEEdge mesh as a v3 MESHCORE_PACKET datagram. The bytes are carried
// verbatim; BLEEdge routing treats the payload as opaque.
func (n *Node) SendMeshCoreRaw(payload []byte) error {
	_, err := n.SendMeshCoreRawWithInfo(payload)
	return err
}

func (n *Node) SendMeshCoreRawWithInfo(payload []byte) (TransmitInfo, error) {
	dg := n.router.NewBroadcast(core.ProtocolMeshCorePacket, payload, core.DefaultFloodTTL)
	return n.transmitWithInfo(dg)
}

// SendMeshCoreRawTo sends an opaque MeshCore packet to a specific reachable
// BLEEdge neighbor as a v3 MESHCORE_PACKET datagram.
func (n *Node) SendMeshCoreRawTo(dst core.NodeID, payload []byte) error {
	_, err := n.SendMeshCoreRawToWithInfo(dst, payload)
	return err
}

func (n *Node) SendMeshCoreRawToWithInfo(dst core.NodeID, payload []byte) (TransmitInfo, error) {
	dg, ok := n.router.NewUnicast(dst, core.ProtocolMeshCorePacket, payload, 0, core.DefaultFloodTTL, true)
	if !ok {
		return TransmitInfo{}, fmt.Errorf("no direct BLEEdge route to %s", dst)
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
// prefix) to every BLEEdge node whose public key matches the prefix AND to which we
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
	dg, ok := n.router.NewUnicast(dst, core.ProtocolBLEEdgeChat, payload, 0, 4, false)
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
	ctx := core.ChatContext{DatagramID: core.NewDatagramID(), Source: n.nodeID, Destination: dst}
	env, err := core.SealDirectText(n.identity, recipientPub, ctx, text, time.Now().Unix())
	if err != nil {
		return err
	}
	dg, ok := n.router.NewUnicast(dst, core.ProtocolBLEEdgeChat, env, uint16(core.FlagAckRequested), 4, false)
	if !ok {
		return fmt.Errorf("no route to %s", dst)
	}
	dg.ID = ctx.DatagramID
	return n.transmit(dg)
}

// SendChat sends an encrypted DM to dst, resolving its public key from the
// topology (learned via the node's signed ANNOUNCE). Returns an error if dst's
// key isn't known yet.
func (n *Node) SendChat(dst core.NodeID, text string) error {
	pub := n.router.PublicKeyFor(dst)
	if pub == nil {
		return fmt.Errorf("no public key known for %s (not in topology yet)", dst)
	}
	return n.SendChatTo(dst, pub, text)
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
	dg := core.Datagram{Version: core.DatagramVersion, ID: core.NewDatagramID(), Source: n.nodeID, Destination: dst, TTL: uint8(len(route)), Route: route, Protocol: core.ProtocolBLEEdgeControl, Payload: payload}
	n.router.MarkOriginated(dg.ID)
	return tag, n.transmitToRoute(dg)
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
	n.mu.Lock()
	links := make([]*MacPeerLink, 0, len(n.peers))
	for _, link := range n.peers {
		links = append(links, link)
	}
	notifiers := make([]*serverNotifier, 0, len(n.notifiers))
	for _, notifier := range n.notifiers {
		notifiers = append(notifiers, notifier)
	}
	n.mu.Unlock()

	for _, link := range links {
		n.sendFramesToLink(link, frames)
	}
	// Also send to server-side subscribers (peers that connected TO us)
	priority := dg.Protocol == core.ProtocolBLEEdgeChat || dg.Protocol == core.ProtocolBLEEdgeControl
	for _, notifier := range notifiers {
		n.notifyFrames(notifier, frames, priority)
	}
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
	if n.sendFramesToPeer(frames, firstHop) {
		return nil
	}
	return fmt.Errorf("first hop %s not connected", firstHop)
}

func (n *Node) sendFramesToPeer(frames []core.Frame, peer core.NodeID) bool {
	n.mu.Lock()
	for _, link := range n.peers {
		if link.peerID == peer {
			n.mu.Unlock()
			n.sendFramesToLink(link, frames)
			return true
		}
	}
	for addr, peerID := range n.serverPeerIDs {
		if peerID != peer {
			continue
		}
		notifier, ok := n.notifiers[addr]
		if !ok {
			continue
		}
		n.mu.Unlock()
		n.notifyFrames(notifier, frames, true)
		return true
	}
	n.mu.Unlock()
	return false
}

func (n *Node) sendFramesToLink(link *MacPeerLink, frames []core.Frame) {
	reliable := len(frames) > 1
	for i, f := range frames {
		var err error
		if reliable {
			err = link.sendFrameReliable(f.Encode())
		} else {
			err = link.sendFrame(f.Encode())
		}
		if err != nil {
			n.logf("send frame error peer=%s fragment=%d/%d reliable=%v: %v", link.peerID, i+1, len(frames), reliable, err)
		}
		if reliable && i+1 < len(frames) {
			time.Sleep(interFrameDelay)
		}
	}
}

func (n *Node) notifyFrames(notifier *serverNotifier, frames []core.Frame, priority bool) {
	for i, f := range frames {
		if !notifier.enqueue(f.Encode(), priority) {
			n.logf("notify queue full addr=%s fragment=%d/%d", notifier.addr, i+1, len(frames))
		}
		if len(frames) > 1 && i+1 < len(frames) {
			time.Sleep(interFrameDelay)
		}
	}
}

func (n *Node) newServerNotifier(addr string, notifier ble.Notifier) *serverNotifier {
	sn := &serverNotifier{
		addr:      addr,
		notifier:  notifier,
		queue:     make(chan []byte, 256),
		priorityQ: make(chan []byte, 64),
		done:      make(chan struct{}),
	}
	go n.runServerNotifier(sn)
	return sn
}

func (n *Node) runServerNotifier(sn *serverNotifier) {
	for {
		frame, ok := sn.nextFrame()
		if !ok {
			return
		}
		retries := 0
		delay := notifyRetryMinDelay
		lastLog := time.Time{}
		var lastErr error
		for {
			_, err := n.writeNotification(sn, frame)
			if err == nil {
				if retries >= notifyLogAfterRetries {
					n.logf("notify recovered addr=%s after %d retries (last error: %v)", sn.addr, retries, lastErr)
				}
				break
			}
			retries++
			lastErr = err
			if retries >= notifyLogAfterRetries && (lastLog.IsZero() || time.Since(lastLog) >= notifyBackpressureLog) {
				n.logf("notify backpressure addr=%s: %v; queued=%d priority=%d retry=%d next=%s", sn.addr, err, len(sn.queue), len(sn.priorityQ), retries, delay)
				lastLog = time.Now()
			}
			select {
			case <-sn.done:
				return
			case <-time.After(delay):
				if delay < notifyRetryMaxDelay {
					delay *= 2
					if delay > notifyRetryMaxDelay {
						delay = notifyRetryMaxDelay
					}
				}
			}
		}
	}
}

func (s *serverNotifier) nextFrame() ([]byte, bool) {
	select {
	case frame := <-s.priorityQ:
		return frame, true
	default:
	}
	select {
	case frame := <-s.priorityQ:
		return frame, true
	case frame := <-s.queue:
		return frame, true
	case <-s.done:
		return nil, false
	}
}

func (n *Node) writeNotification(sn *serverNotifier, frame []byte) (int, error) {
	n.notifyMu.Lock()
	defer n.notifyMu.Unlock()

	if wait := time.Until(n.notifyNextAt); wait > 0 {
		timer := time.NewTimer(wait)
		select {
		case <-sn.done:
			timer.Stop()
			return 0, context.Canceled
		case <-timer.C:
		}
	}

	written, err := sn.notifier.Write(frame)
	n.notifyNextAt = time.Now().Add(notifyGlobalPace)
	return written, err
}

func (s *serverNotifier) enqueue(frame []byte, priority bool) bool {
	frame = append([]byte(nil), frame...)
	queue := s.queue
	if priority {
		queue = s.priorityQ
	}
	select {
	case <-s.done:
		return false
	case queue <- frame:
		return true
	default:
		return false
	}
}

func (s *serverNotifier) close() {
	select {
	case <-s.done:
	default:
		close(s.done)
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

// LoadOrCreateIdentity loads the Ed25519 identity seed from ~/.bleedge/seed or
// creates and persists a new one. NodeID is derived as pubkey[:8].
func LoadOrCreateIdentity() (*core.Identity, error) {
	path := filepath.Join(os.Getenv("HOME"), ".bleedge", "seed")
	return core.LoadOrCreateIdentity(path)
}

func LoadIncrementEpoch() (uint64, error) {
	path := filepath.Join(os.Getenv("HOME"), ".bleedge", "epoch")
	return core.LoadIncrementEpoch(path)
}

func isCtxErr(err error) bool {
	c := errors.Cause(err)
	return c == context.DeadlineExceeded || c == context.Canceled
}

func nodeIDs(ids []core.NodeID) []string {
	out := make([]string, len(ids))
	for i, id := range ids {
		out[i] = id.String()
	}
	return out
}
