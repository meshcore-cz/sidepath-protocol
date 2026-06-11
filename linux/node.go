package linux

import (
	"context"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"

	"github.com/bleedge/bleedge/core"
)

// Node is the top-level Linux BLE node that coordinates all subsystems.
type Node struct {
	nodeID      core.NodeID
	identity    *core.Identity
	description string
	name        string
	platform    string
	phyMode     core.PHYMode
	caps        core.Capabilities
	router      *core.Router
	reassembler *core.Reassembler

	adapter    *Adapter
	scanner    *Scanner
	advertiser *Advertiser
	gattServer *GattServer

	mu    sync.RWMutex
	peers map[core.NodeID]*BLEPeerLink // connected peers

	announceSeq uint32
	verbose     bool
	jsonLog     bool

	onDeliver func(pkt core.Packet)
}

// NodeConfig holds configuration for a Node.
type NodeConfig struct {
	AdapterName string
	Identity    *core.Identity // nil = load/generate from ~/.bleedge/seed
	PHYMode     core.PHYMode
	Allowlist   []core.NodeID
	Name        string // primary display label; empty = deterministic default from pubkey
	Platform    string // OS/device string; empty = core.PlatformDescription()
	Description string // free-form bio advertised in ANNOUNCE/NODE_INFO; default empty
	Verbose     bool
	JSONLog     bool
}

// NewNode creates and initializes a Linux BLE node.
func NewNode(cfg NodeConfig) (*Node, error) {
	identity := cfg.Identity
	if identity == nil {
		var err error
		identity, err = core.LoadOrCreateIdentity(filepath.Join(os.Getenv("HOME"), ".bleedge", "seed"))
		if err != nil {
			return nil, fmt.Errorf("identity: %w", err)
		}
	}
	nodeID := identity.NodeID()

	adapter, err := NewAdapter(cfg.AdapterName)
	if err != nil {
		return nil, fmt.Errorf("adapter: %w", err)
	}

	name := cfg.Name
	if name == "" {
		name = core.DefaultNodeName(identity.Pub)
	}
	platform := cfg.Platform
	if platform == "" {
		platform = core.PlatformDescription()
	}
	description := cfg.Description

	router := core.NewRouterForIdentity(identity)
	router.Description = description
	router.Name = name
	router.Platform = platform
	for _, id := range cfg.Allowlist {
		router.Allowlist[id] = true
	}

	n := &Node{
		nodeID:      nodeID,
		identity:    identity,
		description: description,
		name:        name,
		platform:    platform,
		phyMode:     cfg.PHYMode,
		caps:        core.Capabilities(core.LinuxCapabilities),
		router:      router,
		reassembler: core.NewReassembler(),
		adapter:     adapter,
		peers:       make(map[core.NodeID]*BLEPeerLink),
		verbose:     cfg.Verbose,
		jsonLog:     cfg.JSONLog,
	}

	n.gattServer = NewGattServer(adapter, identity.Pub, n.caps, description, name, platform, n.handleIncomingFrame)
	n.advertiser = NewAdvertiser(adapter, nodeID)
	n.scanner = NewScanner(adapter)

	return n, nil
}

// Start powers on the adapter and begins all BLE operations.
func (n *Node) Start(ctx context.Context) error {
	if err := n.adapter.PowerOn(ctx); err != nil {
		log.Printf("[node] power on: %v (may already be on)", err)
	}

	log.Printf("bleedge listener started node=%s phy=%s", n.nodeID, n.phyMode)
	if n.adapter.CodedPHYSupported {
		log.Printf("adapter supports coded-phy=yes")
	} else {
		log.Printf("adapter supports coded-phy=no")
		if n.phyMode == core.PHYModeCodedOnly {
			return fmt.Errorf("adapter does not support LE Coded PHY but mode is coded-only")
		}
	}

	// Start GATT server
	if err := n.gattServer.Register(); err != nil {
		log.Printf("[node] GATT server: %v", err)
	}

	// Start advertising
	if err := n.advertiser.Start(ctx); err != nil {
		log.Printf("[node] advertiser: %v", err)
	}

	// Start scanning
	if err := n.scanner.Start(ctx); err != nil {
		log.Printf("[node] scanner: %v", err)
	}

	// Connect to existing known devices
	go n.connectExisting(ctx)

	// Watch for new scan results
	go n.watchScanResults(ctx)

	// Periodic ANNOUNCE
	go n.announceLoop(ctx)

	return nil
}

// Stop shuts down all BLE operations.
func (n *Node) Stop(ctx context.Context) {
	n.scanner.Stop(ctx)
	n.advertiser.Stop(ctx)    //nolint:errcheck
	n.gattServer.Unregister() //nolint:errcheck
}

// SetDeliveryHandler sets a callback invoked when a packet is delivered locally.
func (n *Node) SetDeliveryHandler(fn func(pkt core.Packet)) { n.onDeliver = fn }

// NodeID returns this node's ID.
func (n *Node) NodeID() core.NodeID { return n.nodeID }

// SendPacket fragments and sends a packet to all or specific peers.
func (n *Node) SendPacket(pkt core.Packet) error {
	// Record our own packet so a flood echo doesn't get re-flooded back out.
	n.router.MarkOriginated(pkt.ID)

	data, err := pkt.Encode()
	if err != nil {
		return err
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, pkt.ID)

	n.mu.RLock()
	defer n.mu.RUnlock()

	for _, link := range n.peers {
		for _, f := range frames {
			if err := link.SendFrame(f.Encode()); err != nil {
				log.Printf("[node] send frame to %s: %v", link.PeerID(), err)
			}
		}
	}
	// Also push to server-side subscribers (peers that connected TO us via GATT server)
	for _, f := range frames {
		n.gattServer.NotifyFrame(f.Encode())
	}
	return nil
}

// SendTrace sends a source-routed trace request. If route is empty, the current
// topology is used. Trace never falls back to flood; only nodes on the selected
// route track may relay it.
func (n *Node) SendTrace(dst core.NodeID, route []core.NodeID) (uint32, error) {
	tag := core.RandomUint32()
	if len(route) == 0 {
		if selected, ok := n.router.SelectRoute(dst); ok {
			if len(selected) == 0 {
				route = []core.NodeID{dst}
			} else {
				route = selected
			}
		}
		if len(route) == 0 {
			return 0, fmt.Errorf("no route known to %s", dst)
		}
	} else if route[len(route)-1] != dst {
		route = append(append([]core.NodeID(nil), route...), dst)
	}

	flags, err := core.TraceFlagsForHashWidth(core.TraceHashWidth8)
	if err != nil {
		return 0, err
	}
	routeData, err := core.TraceRouteData(route, core.TraceHashWidth8)
	if err != nil {
		return 0, err
	}
	payload := core.EncodeTracePayload(core.TracePayload{
		Tag:       tag,
		AuthCode:  0,
		Flags:     flags,
		RouteData: routeData,
	})
	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      n.nodeID,
		Destination: dst,
		Mode:        core.RoutingModeSourceRoute,
		TTL:         uint8(len(route) + 2),
		Route:       route,
		PayloadType: core.PayloadTypeTraceRequest,
		Payload:     payload,
	}
	n.router.MarkOriginated(pkt.ID)
	return tag, n.transmitToRoute(pkt)
}

func (n *Node) transmitToRoute(pkt core.Packet) error {
	if len(pkt.Route) == 0 {
		return fmt.Errorf("source route is empty")
	}
	data, err := pkt.Encode()
	if err != nil {
		return err
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, pkt.ID)
	firstHop := pkt.Route[0]
	n.mu.RLock()
	link, ok := n.peers[firstHop]
	n.mu.RUnlock()
	if !ok {
		return fmt.Errorf("first hop %s not connected", firstHop)
	}
	for _, f := range frames {
		if err := link.SendFrame(f.Encode()); err != nil {
			return err
		}
	}
	return nil
}

// handleIncomingFrame is called by the GATT server when a frame arrives on PACKET_IN.
func (n *Node) handleIncomingFrame(raw []byte, sender dbus.Sender) {
	frame, err := core.DecodeFrame(raw)
	if err != nil {
		log.Printf("[node] decode frame: %v", err)
		return
	}

	data, done, err := n.reassembler.AddFrame(frame)
	if err != nil {
		log.Printf("[node] reassemble: %v", err)
		return
	}
	if !done {
		return
	}

	pkt, err := core.DecodePacket(data)
	if err != nil {
		log.Printf("[node] decode packet: %v", err)
		return
	}

	if n.verbose {
		log.Printf("rx packet id=%s source=%s ttl=%d trace=%v", pkt.ID, pkt.Source, pkt.TTL, pkt.Trace)
	}

	incomingPeer := directNeighborFromPacket(pkt)
	pkt = n.recordTraceMetric(pkt, incomingPeer)

	// Learn the directly-connected neighbor: the last trace hop (or the source if
	// the packet is fresh) is always the peer that sent us this frame. This keeps
	// the neighbor table populated even when all our connections are inbound
	// (others connected to us), so our ANNOUNCE advertises real neighbors.
	n.learnNeighbor(pkt)

	actions := n.router.HandlePacket(pkt, incomingPeer)
	n.executeActions(actions)
}

func directNeighborFromPacket(pkt core.Packet) *core.NodeID {
	var nb core.NodeID
	if len(pkt.Trace) > 0 {
		nb = pkt.Trace[len(pkt.Trace)-1]
	} else {
		nb = pkt.Source
	}
	if nb == (core.NodeID{}) {
		return nil
	}
	return &nb
}

// learnNeighbor records the directly-connected peer that delivered a packet
// (last trace hop, or source for a freshly-originated packet). Existing entries
// are refreshed without clobbering richer info captured at connect time.
func (n *Node) learnNeighbor(pkt core.Packet) {
	var nb core.NodeID
	if len(pkt.Trace) > 0 {
		nb = pkt.Trace[len(pkt.Trace)-1]
	} else {
		nb = pkt.Source
	}
	if nb == n.nodeID || nb == (core.NodeID{}) {
		return
	}
	if _, ok := n.router.Neighbors.Get(nb); ok {
		n.router.Neighbors.Touch(nb)
	} else {
		n.router.Neighbors.Upsert(core.Neighbor{ID: nb, Direction: core.DirectionIncoming})
	}
}

func (n *Node) recordTraceMetric(pkt core.Packet, incomingPeer *core.NodeID) core.Packet {
	if pkt.Type != core.PacketTypeData {
		return pkt
	}
	if pkt.PayloadType != core.PayloadTypeTraceRequest && pkt.PayloadType != core.PayloadTypeTraceResponse {
		return pkt
	}
	pkt.TraceMetric = append(pkt.TraceMetric, int8(clampRSSI(n.rssiForPeer(incomingPeer))))
	return pkt
}

func (n *Node) rssiForPeer(id *core.NodeID) int {
	if id == nil {
		return 0
	}
	if nb, ok := n.router.Neighbors.Get(*id); ok && nb.RSSI != 0 {
		return nb.RSSI
	}
	n.mu.RLock()
	defer n.mu.RUnlock()
	if link, ok := n.peers[*id]; ok {
		return link.RSSI()
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
	for _, action := range actions {
		switch action.Type {
		case core.ActionDeliverLocal:
			n.deliverLocal(action.Packet)

		case core.ActionRelayFlood:
			time.AfterFunc(core.FloodJitter(), func() {
				n.relayFlood(action)
			})

		case core.ActionRelayNextHop:
			n.relayNextHop(action)

		case core.ActionSendAck:
			n.sendAck(action)

		case core.ActionDrop:
			if n.verbose {
				log.Printf("drop reason=%s", action.Reason)
			}
		}
	}
}

func (n *Node) deliverLocal(pkt core.Packet) {
	if pkt.Type == core.PacketTypeAck {
		log.Printf("ack received from=%s", pkt.Source)
		return
	}
	if pkt.Type == core.PacketTypeAnnounce {
		return
	}
	if pkt.PayloadType == core.PayloadTypeTraceRequest {
		if err := n.returnTrace(pkt); err != nil {
			log.Printf("[node] trace response: %v", err)
		}
		return
	}
	if pkt.PayloadType == core.PayloadTypeTraceResponse {
		if result, err := core.DecodeTraceResult(pkt.Payload); err == nil {
			result.ReturnNodes = append([]core.NodeID(nil), pkt.Trace...)
			result.ReturnSamples = append([]int8(nil), pkt.TraceMetric...)
			if payload, err := result.Encode(); err == nil {
				pkt.Payload = payload
			}
		}
	}
	if n.verbose {
		log.Printf("deliver payload-type=%d payload=%q trace=%v", pkt.PayloadType, pkt.Payload, pkt.Trace)
	} else {
		log.Printf("deliver payload-type=%d payload=%q", pkt.PayloadType, pkt.Payload)
	}
	if n.onDeliver != nil {
		n.onDeliver(pkt)
	}
}

func (n *Node) returnTrace(req core.Packet) error {
	tp, err := core.DecodeTracePayload(req.Payload)
	if err != nil {
		return err
	}
	result := core.TraceResult{
		Tag:            tp.Tag,
		AuthCode:       tp.AuthCode,
		Route:          append([]core.NodeID(nil), req.Route...),
		ForwardNodes:   append([]core.NodeID(nil), req.Trace...),
		ForwardSamples: append([]int8(nil), req.TraceMetric...),
		Metric:         core.TraceMetricRSSI,
	}
	payload, err := result.Encode()
	if err != nil {
		return err
	}
	route := reverseTraceRoute(req.Trace, req.Source)
	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      n.nodeID,
		Destination: req.Source,
		Mode:        core.RoutingModeSourceRoute,
		TTL:         uint8(len(route) + 1),
		Route:       route,
		PayloadType: core.PayloadTypeTraceResponse,
		Payload:     payload,
	}
	n.router.MarkOriginated(pkt.ID)
	return n.transmitToRoute(pkt)
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

func (n *Node) relayFlood(action core.Action) {
	data, err := action.Packet.Encode()
	if err != nil {
		log.Printf("[node] relay encode: %v", err)
		return
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, action.Packet.ID)

	n.mu.RLock()
	defer n.mu.RUnlock()

	for peerID, link := range n.peers {
		// Exclude the incoming peer
		if action.NextHop != nil && peerID == *action.NextHop {
			continue
		}
		for _, f := range frames {
			if err := link.SendFrame(f.Encode()); err != nil {
				log.Printf("[node] relay-flood to %s: %v", peerID, err)
			}
		}
	}
	// Push to server-side subscribers; dedup cache in router prevents re-forwarding loops
	for _, f := range frames {
		n.gattServer.NotifyFrame(f.Encode())
	}
}

func (n *Node) relayNextHop(action core.Action) {
	if action.NextHop == nil {
		return
	}
	n.mu.RLock()
	link, ok := n.peers[*action.NextHop]
	n.mu.RUnlock()
	if !ok {
		log.Printf("[node] relay-next-hop: peer %s not connected", *action.NextHop)
		return
	}
	data, err := action.Packet.Encode()
	if err != nil {
		return
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, action.Packet.ID)
	for _, f := range frames {
		link.SendFrame(f.Encode()) //nolint:errcheck
	}
	if n.verbose {
		log.Printf("relay next-hop=%s", *action.NextHop)
	}
}

func (n *Node) sendAck(action core.Action) {
	if action.NextHop == nil {
		return
	}
	n.mu.RLock()
	link, ok := n.peers[*action.NextHop]
	n.mu.RUnlock()
	if !ok {
		// Try flood
		n.relayFlood(core.Action{Type: core.ActionRelayFlood, Packet: action.Packet})
		return
	}
	data, err := action.Packet.Encode()
	if err != nil {
		return
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, action.Packet.ID)
	for _, f := range frames {
		link.SendFrame(f.Encode()) //nolint:errcheck
	}
	if n.verbose {
		log.Printf("send ack route=%v", action.Packet.Route)
	}
}

func (n *Node) watchScanResults(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case result := <-n.scanner.Results():
			log.Printf("scan found node=%s rssi=%d", result.Address, result.RSSI)
			go n.connectDevice(ctx, result)
		}
	}
}

func (n *Node) connectExisting(ctx context.Context) {
	results, err := n.scanner.GetExistingDevices(ctx)
	if err != nil {
		log.Printf("[node] get existing devices: %v", err)
		return
	}
	for _, r := range results {
		go n.connectDevice(ctx, r)
	}
}

func (n *Node) connectDevice(ctx context.Context, result ScanResult) {
	// Whoever discovers a peer connects to it. BLE discovery can be asymmetric, so we
	// must NOT gate on NodeID ordering (that risks a deadlock where the only node that
	// can see a peer is the one told to wait). When the peer advertised its NodeID
	// (manufacturer data), we can skip self and already-connected peers before connecting.
	if result.HasNodeID {
		if result.NodeID == n.nodeID {
			return // our own advertisement
		}
		n.mu.RLock()
		_, dup := n.peers[result.NodeID]
		n.mu.RUnlock()
		if dup {
			return // already connected to this peer
		}
	}

	client := NewGattClient(n.adapter, result.DevicePath, func(frame []byte) {
		n.handleIncomingFrame(frame, dbus.Sender(string(result.DevicePath)))
	})

	if err := client.Connect(ctx); err != nil {
		log.Printf("[node] connect %s: %v", result.DevicePath, err)
		return
	}

	link := NewBLEPeerLink(client)
	realID := client.NodeID()

	// Collapse a duplicate link: if we already hold a connection to this NodeID
	// (e.g. both sides discovered each other), keep the existing one and drop this.
	n.mu.RLock()
	_, exists := n.peers[realID]
	n.mu.RUnlock()
	if exists || realID == n.nodeID {
		log.Printf("[node] peer=%s already connected (or self) — dropping duplicate", realID)
		client.Disconnect(ctx) //nolint:errcheck
		return
	}

	// PHY enforcement
	if n.phyMode == core.PHYModeCodedOnly {
		if link.TxPHY() != core.PHYCoded || link.RxPHY() != core.PHYCoded {
			log.Printf("[node] WARNING: peer=%s phy-not-coded tx=%s rx=%s (coded-only mode)",
				realID, link.TxPHY(), link.RxPHY())
			// Mark as invalid but keep connection for visibility
		} else {
			log.Printf("connected peer=%s tx-phy=coded rx-phy=coded", realID)
		}
	} else {
		log.Printf("connected peer=%s tx-phy=%s rx-phy=%s", realID, link.TxPHY(), link.RxPHY())
	}

	n.mu.Lock()
	n.peers[realID] = link
	n.mu.Unlock()

	n.router.Neighbors.Upsert(core.Neighbor{
		ID:          realID,
		TxPHY:       link.TxPHY(),
		RxPHY:       link.RxPHY(),
		RSSI:        link.RSSI(),
		Caps:        client.caps,
		Description: client.description,
		Name:        client.name,
		Platform:    client.platform,
	})
}

func (n *Node) announceLoop(ctx context.Context) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	// Send immediately on start
	n.sendAnnounce()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			n.sendAnnounce()
		}
	}
}

func (n *Node) sendAnnounce() {
	n.announceSeq++
	pkt, err := n.router.BuildAnnounce(n.caps, n.announceSeq)
	if err != nil {
		log.Printf("[node] build announce: %v", err)
		return
	}
	if err := n.SendPacket(pkt); err != nil {
		log.Printf("[node] send announce: %v", err)
	}
}
