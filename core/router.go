package core

import (
	"fmt"
	"math/rand"
	"time"
)

// PeerLink is the transport abstraction — must not depend on any BLE types.
type PeerLink interface {
	PeerID() NodeID
	SendFrame(frame []byte) error
	RSSI() int
	TxPHY() PHY
	RxPHY() PHY
}

type ActionType string

const (
	ActionDeliverLocal ActionType = "deliver-local"
	ActionRelayFlood   ActionType = "relay-flood"
	ActionRelayNextHop ActionType = "relay-next-hop"
	ActionSendAck      ActionType = "send-ack"
	ActionDrop         ActionType = "drop"
)

// Action is a decision produced by the Router for the transport layer to execute.
type Action struct {
	Type    ActionType
	Reason  string
	Packet  Packet
	NextHop *NodeID // for relay-next-hop and send-ack; for relay-flood this is the INCOMING peer (to exclude)
}

// Router is the pure-Go mesh routing engine with no BLE dependencies.
type Router struct {
	LocalID     NodeID
	Identity    *Identity // local signing identity; required to BuildAnnounce
	Name        string    // primary display label advertised in ANNOUNCE/NODE_INFO (unsigned)
	Platform    string    // OS/device string advertised in ANNOUNCE/NODE_INFO (unsigned)
	Description string    // free-form bio advertised in ANNOUNCE/NODE_INFO (unsigned)
	dedup       *DedupCache
	Neighbors   *NeighborTable
	Topology    *Topology
	Allowlist   map[NodeID]bool // if non-empty, only these peers are allowed
}

// NewRouter creates a router with a bare NodeID and no signing identity. It can
// route and verify incoming announces, but cannot originate signed announces;
// use NewRouterForIdentity for a real node.
func NewRouter(id NodeID) *Router {
	return &Router{
		LocalID:   id,
		dedup:     NewDedupCache(),
		Neighbors: NewNeighborTable(),
		Topology:  NewTopology(),
		Allowlist: make(map[NodeID]bool),
	}
}

// NewRouterForIdentity creates a router whose LocalID is derived from the
// identity's Ed25519 public key (pubkey[:8]) and which can sign announces.
func NewRouterForIdentity(id *Identity) *Router {
	r := NewRouter(id.NodeID())
	r.Identity = id
	return r
}

// HandlePacket processes an incoming packet and returns a list of actions the transport should take.
func (r *Router) HandlePacket(pkt Packet, incomingPeer *NodeID) []Action {
	// 1. Version check
	if pkt.Version != ProtocolVersion {
		return []Action{{Type: ActionDrop, Reason: string(DropInvalidVersion), Packet: pkt}}
	}

	// 2. Allowlist check
	if incomingPeer != nil && len(r.Allowlist) > 0 && !r.Allowlist[*incomingPeer] {
		return []Action{{Type: ActionDrop, Reason: string(DropPeerNotAllowed), Packet: pkt}}
	}

	// Handle ANNOUNCE separately
	if pkt.Type == PacketTypeAnnounce {
		return r.handleAnnounce(pkt, incomingPeer)
	}

	switch pkt.Mode {
	case RoutingModeFlood:
		return r.handleFlood(pkt, incomingPeer)
	case RoutingModeSourceRoute:
		return r.handleSourceRoute(pkt, incomingPeer)
	default:
		return []Action{{Type: ActionDrop, Reason: string(DropMalformed), Packet: pkt}}
	}
}

func (r *Router) handleAnnounce(pkt Packet, incomingPeer *NodeID) []Action {
	ap, err := DecodeAnnounce(pkt.Payload)
	if err != nil {
		return []Action{{Type: ActionDrop, Reason: string(DropMalformed), Packet: pkt}}
	}
	// Authenticate: NodeID must be bound to the carried public key, and the
	// signature must verify. Reject (and do not relay) on any failure.
	if len(ap.PublicKey) != 32 || NodeIDFromPubKey(ap.PublicKey) != ap.NodeID || ap.NodeID != pkt.Source {
		return []Action{{Type: ActionDrop, Reason: string(DropBadSignature), Packet: pkt}}
	}
	if !VerifyAnnounce(ap.PublicKey, ap.Signature, uint32(ap.Timestamp), ap.Caps, ap.Seq, ap.Neighbors) {
		return []Action{{Type: ActionDrop, Reason: string(DropBadSignature), Packet: pkt}}
	}
	r.Topology.Update(TopoNode{
		ID:          ap.NodeID,
		Caps:        ap.Caps,
		Neighbors:   ap.Neighbors,
		Seq:         ap.Seq,
		Description: ap.Description,
		Name:        ap.Name,
		Platform:    ap.Platform,
		PublicKey:   ap.PublicKey,
	})
	// Flood ANNOUNCE to the rest of the network
	return r.handleFlood(pkt, incomingPeer)
}

func (r *Router) handleFlood(pkt Packet, incomingPeer *NodeID) []Action {
	// Dedup
	if r.dedup.SeenOrAdd(pkt.ID) {
		return []Action{{Type: ActionDrop, Reason: string(DropDuplicate), Packet: pkt}}
	}
	// Loop detection
	for _, hop := range pkt.Trace {
		if hop == r.LocalID {
			return []Action{{Type: ActionDrop, Reason: string(DropLoop), Packet: pkt}}
		}
	}
	// TTL check
	if pkt.TTL == 0 {
		return []Action{{Type: ActionDrop, Reason: string(DropTTL), Packet: pkt}}
	}

	// Add self to trace
	pkt.Trace = append(pkt.Trace, r.LocalID)

	var actions []Action

	// Deliver locally?
	if pkt.IsBroadcast() || pkt.Destination == r.LocalID {
		actions = append(actions, Action{Type: ActionDeliverLocal, Packet: pkt})
		// ACK for unicast DATA. TRACE has its own request/response exchange.
		if pkt.Type == PacketTypeData && !pkt.IsBroadcast() && !noAck(pkt.PayloadType) {
			actions = append(actions, r.buildAck(pkt))
		}
	}

	// Relay?
	if pkt.TTL > 1 && (pkt.IsBroadcast() || pkt.Destination != r.LocalID) {
		if isTracePayload(pkt.PayloadType) {
			return actions
		}
		relayPkt := pkt
		relayPkt.TTL--
		hop := Action{Type: ActionRelayFlood, Packet: relayPkt}
		if incomingPeer != nil {
			// Store incoming peer in NextHop so transport can exclude it
			hop.NextHop = incomingPeer
		}
		actions = append(actions, hop)
	}

	return actions
}

func isTracePayload(pt PayloadType) bool {
	return pt == PayloadTypeTraceRequest || pt == PayloadTypeTraceResponse
}

// noAck reports payloads that are never ACKed: trace (own request/response
// exchange) and ephemeral typing hints.
func noAck(pt PayloadType) bool {
	return isTracePayload(pt) || pt == PayloadTypeTyping
}

func (r *Router) handleSourceRoute(pkt Packet, incomingPeer *NodeID) []Action {
	// Dedup + loop + TTL
	if r.dedup.SeenOrAdd(pkt.ID) {
		return []Action{{Type: ActionDrop, Reason: string(DropDuplicate), Packet: pkt}}
	}
	for _, hop := range pkt.Trace {
		if hop == r.LocalID {
			return []Action{{Type: ActionDrop, Reason: string(DropLoop), Packet: pkt}}
		}
	}
	if pkt.TTL == 0 {
		return []Action{{Type: ActionDrop, Reason: string(DropTTL), Packet: pkt}}
	}

	// Check we are the next hop
	if int(pkt.RouteCursor) >= len(pkt.Route) || pkt.Route[pkt.RouteCursor] != r.LocalID {
		return []Action{{Type: ActionDrop, Reason: string(DropNotNextHop), Packet: pkt}}
	}

	pkt.Trace = append(pkt.Trace, r.LocalID)
	pkt.RouteCursor++
	pkt.TTL--

	if int(pkt.RouteCursor) >= len(pkt.Route) {
		// We are the destination
		actions := []Action{{Type: ActionDeliverLocal, Packet: pkt}}
		if pkt.Type == PacketTypeData && !noAck(pkt.PayloadType) {
			actions = append(actions, r.buildAck(pkt))
		}
		return actions
	}

	nextHop := pkt.Route[pkt.RouteCursor]
	return []Action{{Type: ActionRelayNextHop, Packet: pkt, NextHop: &nextHop}}
}

// buildAck constructs an ACK packet routed back via the reversed trace.
func (r *Router) buildAck(data Packet) Action {
	ack := Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeAck,
		ID:          NewPacketID(),
		Source:      r.LocalID,
		Destination: data.Source,
		TTL:         uint8(len(data.Trace) + 1),
	}

	// ACK via reversed trace path
	if len(data.Trace) > 1 {
		// Build reverse route: exclude self (last element), reverse remaining
		// data.Trace at this point includes self as last element
		hops := data.Trace[:len(data.Trace)-1] // all hops before self
		route := make([]NodeID, len(hops))
		for i, hop := range hops {
			route[len(hops)-1-i] = hop
		}
		// Append the original source as the final hop. Source-route delivery happens by
		// route exhaustion, and the originator isn't in the trace, so without this the ACK
		// would be "delivered" at the last relay instead of reaching the source. Guard
		// against the source already being terminal.
		if len(route) == 0 || route[len(route)-1] != data.Source {
			route = append(route, data.Source)
		}
		ack.Mode = RoutingModeSourceRoute
		ack.Route = route
		ack.RouteCursor = 0
	} else {
		ack.Mode = RoutingModeFlood
	}

	nextHop := data.Source
	if len(data.Trace) > 1 {
		nextHop = data.Trace[len(data.Trace)-2]
	}
	// We originated this ACK — record it so a flood echo isn't re-flooded back.
	r.dedup.SeenOrAdd(ack.ID)
	return Action{Type: ActionSendAck, Packet: ack, NextHop: &nextHop}
}

// BuildAnnounce creates a periodic, signed announce packet for this node.
func (r *Router) BuildAnnounce(caps Capabilities, seq uint32) (Packet, error) {
	if r.Identity == nil {
		return Packet{}, fmt.Errorf("router has no signing identity")
	}
	neighbors := r.Neighbors.IDs()
	ts := time.Now().Unix()
	sig := r.Identity.SignAnnounce(uint32(ts), caps, seq, neighbors)
	ap := AnnouncePayload{
		NodeID:      r.LocalID,
		Caps:        caps,
		Neighbors:   neighbors,
		Seq:         seq,
		Timestamp:   ts,
		PublicKey:   r.Identity.Pub,
		Signature:   sig,
		Description: r.Description,
		Name:        r.Name,
		Platform:    r.Platform,
	}
	payload, err := ap.Encode()
	if err != nil {
		return Packet{}, err
	}
	return Packet{
		Version:     ProtocolVersion,
		Type:        PacketTypeAnnounce,
		ID:          NewPacketID(),
		Source:      r.LocalID,
		Mode:        RoutingModeFlood,
		TTL:         3,
		PayloadType: PayloadTypeTextTest,
		Payload:     payload,
	}, nil
}

// MarkOriginated records a packet ID this node is about to send as already seen,
// so that if a flood echoes the packet back to us we drop it as a duplicate instead
// of re-flooding our own packet. Call this for every packet this node originates.
func (r *Router) MarkOriginated(id PacketID) {
	r.dedup.SeenOrAdd(id)
}

// SelectRoute picks a source route to dst or signals direct/flood fallback.
// Returns (route, true) for a known multi-hop path, (nil, true) if direct neighbor, (nil, false) if unknown.
func (r *Router) SelectRoute(dst NodeID) ([]NodeID, bool) {
	// Direct neighbor?
	if _, ok := r.Neighbors.Get(dst); ok {
		return nil, true // direct
	}
	path := r.Topology.BFSPath(r.LocalID, dst)
	return path, len(path) > 0
}

// DescriptionFor resolves a node's diagnostic label: the direct neighbor's
// NODE_INFO description if known, else the description from its ANNOUNCE
// (topology). Returns "" if unknown.
func (r *Router) DescriptionFor(id NodeID) string {
	if n, ok := r.Neighbors.Get(id); ok && n.Description != "" {
		return n.Description
	}
	if tn, ok := r.Topology.GetNode(id); ok {
		return tn.Description
	}
	return ""
}

// NameFor resolves a node's primary display label: the direct neighbor's NODE_INFO
// name if known, else the name from its ANNOUNCE (topology), else the deterministic
// default derived from its public key if we know it, else "".
func (r *Router) NameFor(id NodeID) string {
	if n, ok := r.Neighbors.Get(id); ok && n.Name != "" {
		return n.Name
	}
	if tn, ok := r.Topology.GetNode(id); ok {
		if tn.Name != "" {
			return tn.Name
		}
		if name := DefaultNodeName(tn.PublicKey); name != "" {
			return name
		}
	}
	return ""
}

// PlatformFor resolves a node's OS/device string from its NODE_INFO (neighbor) or
// ANNOUNCE (topology). Returns "" if unknown.
func (r *Router) PlatformFor(id NodeID) string {
	if n, ok := r.Neighbors.Get(id); ok && n.Platform != "" {
		return n.Platform
	}
	if tn, ok := r.Topology.GetNode(id); ok {
		return tn.Platform
	}
	return ""
}

// PublicKeyFor returns a node's 32-byte Ed25519 public key as learned from its
// signed ANNOUNCE (topology), or nil if unknown. Used to encrypt a DM to a node
// we haven't received a message from yet.
func (r *Router) PublicKeyFor(id NodeID) []byte {
	if tn, ok := r.Topology.GetNode(id); ok && len(tn.PublicKey) == 32 {
		return tn.PublicKey
	}
	return nil
}

// FloodJitter returns a random relay delay between 10ms and 100ms to reduce collision probability.
func FloodJitter() time.Duration {
	return time.Duration(10+rand.Intn(90)) * time.Millisecond
}
