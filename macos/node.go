//go:build darwin

// Package macos implements a BLEEdge node for macOS using CoreBluetooth via go-ble.
// CoreBluetooth does not expose LE Coded PHY control, so this node always
// operates in 1m mode and is NOT valid for the Long Range demonstration.
// It is useful for development and smoke-testing the routing engine over regular BLE.
package macos

import (
	"context"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/bleedge/bleedge/core"
	"github.com/go-ble/ble"
	"github.com/go-ble/ble/darwin"
	"github.com/pkg/errors"
)

var (
	serviceUUID  = ble.MustParse("9B7E6A10-7D91-4C19-A3B8-6E2A11F3A001")
	nodeInfoUUID = ble.MustParse("9B7E6A10-7D91-4C19-A3B8-6E2A11F3A002")
	packetInUUID = ble.MustParse("9B7E6A10-7D91-4C19-A3B8-6E2A11F3A003")
	packetOutUUID = ble.MustParse("9B7E6A10-7D91-4C19-A3B8-6E2A11F3A004")
)

// Node is a macOS BLEEdge node.
type Node struct {
	nodeID      core.NodeID
	identity    *core.Identity
	description string
	caps        core.Capabilities
	router    *core.Router
	reassem   *core.Reassembler
	allowlist map[core.NodeID]bool
	verbose   bool

	mu        sync.Mutex
	// peer addr string → link
	peers     map[string]*MacPeerLink
	// notifiers for PACKET_OUT (server-side subscribers)
	notifiers map[string]ble.Notifier

	logFn            func(string)
	onMessage        func(core.NodeID, core.PayloadType, []byte)
	onPeerConnect    func(core.NodeID)
	onPeerDisconnect func(core.NodeID)
}

// Config holds startup parameters.
type Config struct {
	Identity         *core.Identity
	Description      string // node label for ANNOUNCE/NODE_INFO; empty = platform default
	Caps             core.Capabilities
	Allowlist        []core.NodeID
	Verbose          bool
	LogFn            func(string)
	OnMessage        func(from core.NodeID, ptype core.PayloadType, payload []byte) // called for received DATA packets
	OnPeerConnect    func(id core.NodeID)                   // called when outgoing peer connects
	OnPeerDisconnect func(id core.NodeID)                   // called when peer disconnects
}

// New creates a Node. Call Run to start it.
func New(cfg Config) *Node {
	description := cfg.Description
	if description == "" {
		description = core.PlatformDescription()
	}
	router := core.NewRouterForIdentity(cfg.Identity)
	router.Description = description
	n := &Node{
		nodeID:      cfg.Identity.NodeID(),
		identity:    cfg.Identity,
		description: description,
		caps:        cfg.Caps,
		router:      router,
		reassem:     core.NewReassembler(),
		allowlist: make(map[core.NodeID]bool),
		verbose:   cfg.Verbose,
		peers:     make(map[string]*MacPeerLink),
		notifiers: make(map[string]ble.Notifier),
		logFn:     cfg.LogFn,
	}
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
		n.handleIncomingFrame(req.Data(), nil)
	}))
	svc.AddCharacteristic(piChar)

	// PACKET_OUT — notifiable
	poChar := ble.NewCharacteristic(packetOutUUID)
	poChar.HandleNotify(ble.NotifyHandlerFunc(func(req ble.Request, notifier ble.Notifier) {
		addr := req.Conn().RemoteAddr().String()
		n.mu.Lock()
		n.notifiers[addr] = notifier
		n.mu.Unlock()
		n.logf("server: peer %s subscribed to PACKET_OUT", addr)
		<-notifier.Context().Done()
		n.mu.Lock()
		delete(n.notifiers, addr)
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
	var peerDesc string
	if nodeInfoChar != nil {
		data, err := cln.ReadCharacteristic(nodeInfoChar)
		if err == nil && len(data) >= 34 {
			peerID = core.NodeIDFromPubKey(data[1:33])
			if len(data) >= 35 {
				descLen := int(data[34])
				if 35+descLen <= len(data) {
					peerDesc = string(data[35 : 35+descLen])
				}
			}
			n.logf("peer node_id=%s desc=%q", peerID, peerDesc)
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
		peerID:  peerID,
		addr:    addr,
		cln:     cln,
		piChar:  packetInChar,
		mtu:     mtu,
		txPHY:   core.PHY1M,
		rxPHY:   core.PHY1M,
		rssi:    adv.RSSI(),
	}

	n.mu.Lock()
	n.peers[addr] = link
	n.mu.Unlock()

	if n.onPeerConnect != nil {
		n.onPeerConnect(peerID)
	}

	n.router.Neighbors.Upsert(core.Neighbor{
		ID:          peerID,
		Direction:   core.DirectionOutgoing,
		RSSI:        adv.RSSI(),
		TxPHY:       core.PHY1M,
		RxPHY:       core.PHY1M,
		Description: peerDesc,
	})

	// Subscribe to PACKET_OUT
	if packetOutChar != nil {
		cln.Subscribe(packetOutChar, false, func(data []byte) {
			n.handleIncomingFrame(data, &peerID)
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
func (n *Node) handleIncomingFrame(raw []byte, fromPeer *core.NodeID) {
	frame, err := core.DecodeFrame(raw)
	if err != nil {
		n.logf("decode frame error: %v", err)
		return
	}
	data, complete, err := n.reassem.AddFrame(frame)
	if err != nil {
		n.logf("reassemble error: %v", err)
		return
	}
	if !complete {
		return
	}

	pkt, err := core.DecodePacket(data)
	if err != nil {
		n.logf("decode packet error: %v", err)
		return
	}

	srcHex := pkt.Source.String()[:8]
	n.logf("rx packet id=%s source=%s ttl=%d trace=%v",
		hex.EncodeToString(pkt.ID[:4]), srcHex, pkt.TTL, nodeIDs(pkt.Trace))

	// Learn the directly-connected neighbor (last trace hop, or source if fresh) so
	// the neighbor table — and our ANNOUNCE — stay populated even when all peers
	// connected inbound to us.
	n.learnNeighbor(pkt)

	actions := n.router.HandlePacket(pkt, fromPeer)
	n.executeActions(actions)
}

func (n *Node) learnNeighbor(pkt core.Packet) {
	var nb core.NodeID
	if len(pkt.Trace) > 0 {
		nb = pkt.Trace[len(pkt.Trace)-1]
	} else {
		nb = pkt.Source
	}
	var zero core.NodeID
	if nb == n.nodeID || nb == zero {
		return
	}
	if _, ok := n.router.Neighbors.Get(nb); ok {
		n.router.Neighbors.Touch(nb)
	} else {
		n.router.Neighbors.Upsert(core.Neighbor{ID: nb, Direction: core.DirectionIncoming})
	}
}

func (n *Node) executeActions(actions []core.Action) {
	for _, a := range actions {
		switch a.Type {
		case core.ActionDeliverLocal:
			n.deliverLocal(a.Packet)
		case core.ActionRelayFlood:
			go func(a core.Action) {
				time.Sleep(core.FloodJitter())
				n.relayFlood(a)
			}(a)
		case core.ActionRelayNextHop:
			n.relayNextHop(a)
		case core.ActionSendAck:
			n.sendAck(a)
		case core.ActionDrop:
			n.logf("drop reason=%s", a.Reason)
		}
	}
}

func (n *Node) deliverLocal(pkt core.Packet) {
	if pkt.Type == core.PacketTypeAck {
		n.logf("ack received from=%s", pkt.Source)
		return
	}
	if pkt.Type == core.PacketTypeAnnounce {
		return // topology update, not user data
	}
	n.logf("deliver payload-type=%d payload=%q trace=%v",
		pkt.PayloadType, string(pkt.Payload), nodeIDs(pkt.Trace))
	if n.onMessage != nil {
		n.onMessage(pkt.Source, pkt.PayloadType, pkt.Payload)
	}
}

func (n *Node) relayFlood(a core.Action) {
	data, err := a.Packet.Encode()
	if err != nil {
		return
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, a.Packet.ID)
	n.mu.Lock()
	defer n.mu.Unlock()
	for addr, link := range n.peers {
		if a.NextHop != nil && link.peerID == *a.NextHop {
			continue // don't relay back to incoming peer
		}
		for _, f := range frames {
			_ = link.sendFrame(f.Encode())
		}
		_ = addr
	}
	// Also notify server-side subscribers
	for _, notifier := range n.notifiers {
		for _, f := range frames {
			notifier.Write(f.Encode())
		}
	}
}

func (n *Node) relayNextHop(a core.Action) {
	if a.NextHop == nil {
		return
	}
	data, err := a.Packet.Encode()
	if err != nil {
		return
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, a.Packet.ID)
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, link := range n.peers {
		if link.peerID == *a.NextHop {
			for _, f := range frames {
				_ = link.sendFrame(f.Encode())
			}
			return
		}
	}
	// Check notifiers (server-side connections where we don't know peer addr yet)
	n.logf("relay-next-hop: peer %s not connected", *a.NextHop)
}

func (n *Node) sendAck(a core.Action) {
	n.logf("send ack route=%v", nodeIDs(a.Packet.Route))
	n.relayNextHop(a)
	// Fallback to flood if next-hop missing
	if a.NextHop == nil {
		n.relayFlood(core.Action{Type: core.ActionRelayFlood, Packet: a.Packet})
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
			pkt, err := n.router.BuildAnnounce(n.caps, seq)
			if err != nil {
				continue
			}
			seq++
			n.router.MarkOriginated(pkt.ID)
			data, err := pkt.Encode()
			if err != nil {
				continue
			}
			frames := core.FragmentPacket(data, core.MaxFrameSize, pkt.ID)
			n.mu.Lock()
			for _, link := range n.peers {
				for _, f := range frames {
					_ = link.sendFrame(f.Encode())
				}
			}
			for _, notifier := range n.notifiers {
				for _, f := range frames {
					notifier.Write(f.Encode())
				}
			}
			n.mu.Unlock()
		}
	}
}

// buildNodeInfo encodes version(1)+pubkey(32)+caps(1)+descLen(1)+desc(descLen).
func (n *Node) buildNodeInfo() []byte {
	desc := []byte(n.description)
	if len(desc) > 255 {
		desc = desc[:255]
	}
	data := make([]byte, 34+1+len(desc))
	data[0] = core.ProtocolVersion
	copy(data[1:33], n.identity.Pub)
	data[33] = byte(n.caps)
	data[34] = byte(len(desc))
	copy(data[35:], desc)
	return data
}

// SendText sends a plaintext test message to dst (zero = broadcast).
func (n *Node) SendText(dst core.NodeID, text string, ttl uint8) error {
	return n.sendData(dst, core.PayloadTypeTextTest, []byte(text), ttl)
}

// SendChannel broadcasts a plaintext chat message on the public channel.
func (n *Node) SendChannel(text string) error {
	return n.sendData(core.NodeID{}, core.PayloadTypeChatPlain, []byte(text), 4)
}

// SendChatTo sends an end-to-end encrypted direct message to dst, encrypted to
// recipientPub (the recipient's 32-byte Ed25519 public key). For replies the
// public key is the one carried in the inbound envelope (core.ChatEnvelopeSenderPub).
func (n *Node) SendChatTo(dst core.NodeID, recipientPub []byte, text string) error {
	env, err := core.SealChat(text, n.identity, recipientPub)
	if err != nil {
		return err
	}
	return n.sendData(dst, core.PayloadTypeChatEncrypted, env, 4)
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

// PublicKeyFor returns a node's 32-byte Ed25519 public key from the topology, or nil.
func (n *Node) PublicKeyFor(id core.NodeID) []byte {
	return n.router.PublicKeyFor(id)
}

// sendData builds, originates and transmits a DATA packet.
func (n *Node) sendData(dst core.NodeID, ptype core.PayloadType, payload []byte, ttl uint8) error {
	pkt := core.Packet{
		Version:     core.ProtocolVersion,
		Type:        core.PacketTypeData,
		ID:          core.NewPacketID(),
		Source:      n.nodeID,
		Destination: dst,
		TTL:         ttl,
		PayloadType: ptype,
		Payload:     payload,
	}
	var zero core.NodeID
	if dst == zero {
		pkt.Mode = core.RoutingModeFlood
	} else {
		route, ok := n.router.SelectRoute(dst)
		if !ok || len(route) == 0 {
			pkt.Mode = core.RoutingModeFlood
		} else {
			pkt.Mode = core.RoutingModeSourceRoute
			pkt.Route = route
		}
	}
	// Record our own packet so a flood echo doesn't get re-flooded back out.
	n.router.MarkOriginated(pkt.ID)
	data, err := pkt.Encode()
	if err != nil {
		return err
	}
	frames := core.FragmentPacket(data, core.MaxFrameSize, pkt.ID)
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, link := range n.peers {
		for _, f := range frames {
			_ = link.sendFrame(f.Encode())
		}
	}
	// Also send to server-side subscribers (peers that connected TO us)
	for _, notifier := range n.notifiers {
		for _, f := range frames {
			notifier.Write(f.Encode())
		}
	}
	return nil
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

// Description returns this node's diagnostic label.
func (n *Node) Description() string {
	return n.description
}

// DescriptionFor resolves a peer's diagnostic label (neighbor or topology).
func (n *Node) DescriptionFor(id core.NodeID) string {
	return n.router.DescriptionFor(id)
}

// LoadOrCreateIdentity loads the Ed25519 identity seed from ~/.bleedge/seed or
// creates and persists a new one. NodeID is derived as pubkey[:8].
func LoadOrCreateIdentity() (*core.Identity, error) {
	path := filepath.Join(os.Getenv("HOME"), ".bleedge", "seed")
	return core.LoadOrCreateIdentity(path)
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
