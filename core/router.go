package core

import (
	"fmt"
	"math/rand"
	"time"
)

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

type Action struct {
	Type        ActionType
	Reason      string
	Datagram    Datagram
	NextHop     *NodeID
	ExcludePeer *NodeID
}

type Router struct {
	LocalID     NodeID
	Identity    *Identity
	Name        string
	Platform    string
	Description string
	dedup       *DedupCache
	Neighbors   *NeighborTable
	Topology    *Topology
	Allowlist   map[NodeID]bool
}

func NewRouter(id NodeID) *Router {
	return &Router{
		LocalID:   id,
		dedup:     NewDedupCache(),
		Neighbors: NewNeighborTable(),
		Topology:  NewTopology(),
		Allowlist: make(map[NodeID]bool),
	}
}

func NewRouterForIdentity(id *Identity) *Router {
	r := NewRouter(id.NodeID())
	r.Identity = id
	return r
}

func (r *Router) HandleDatagram(dg Datagram, incomingPeer *NodeID) []Action {
	if dg.Version != DatagramVersion {
		return r.drop(dg, DropInvalidVersion)
	}
	if dg.TTL < 1 || dg.TTL > MaxTTL || len(dg.Path) > MaxRouteHops || len(dg.ID) != DatagramIDBytes {
		return r.drop(dg, DropMalformed)
	}
	if len(dg.Route) > MaxRouteHops {
		return r.drop(dg, DropBadRoute)
	}
	if incomingPeer != nil && len(r.Allowlist) > 0 && !r.Allowlist[*incomingPeer] {
		return r.drop(dg, DropPeerNotAllowed)
	}
	if r.dedup.SeenOrAdd(dg.ID) {
		return r.drop(dg, DropDuplicate)
	}
	for _, hop := range dg.Path {
		if hop == r.LocalID {
			return r.drop(dg, DropLoop)
		}
	}
	if ok := r.verifyControlIfAnnounce(dg); ok != nil && !*ok {
		return r.drop(dg, DropBadSignature)
	}
	if dg.IsSourceRouted() {
		return r.handleSourceRoute(dg)
	}
	return r.handleFlood(dg, incomingPeer)
}

func (r *Router) verifyControlIfAnnounce(dg Datagram) *bool {
	if dg.Protocol != ProtocolBLEEdgeControl {
		return nil
	}
	ctrl, err := DecodeControl(dg.Payload)
	if err != nil || ctrl.Kind != ControlAnnounce {
		return nil
	}
	body, err := DecodeAnnounceBody(ctrl.Body)
	if err != nil {
		v := false
		return &v
	}
	if len(body.PublicKey) != PublicKeyBytes || NodeIDFromPubKey(body.PublicKey) != dg.Source || !body.Valid() {
		v := false
		return &v
	}
	r.Topology.Update(TopoNode{
		ID:          dg.Source,
		Caps:        body.Caps,
		Neighbors:   body.Neighbors,
		Epoch:       body.Epoch,
		Seq:         body.Seq,
		Timestamp:   body.Timestamp,
		Description: body.Description,
		Name:        body.Name,
		Platform:    body.Platform,
		PublicKey:   body.PublicKey,
	})
	v := true
	return &v
}

func (r *Router) handleFlood(dg Datagram, incomingPeer *NodeID) []Action {
	dg.Path = append(append([]NodeID(nil), dg.Path...), r.LocalID)
	var actions []Action

	if dg.IsBroadcast() || dg.Destination == r.LocalID {
		actions = append(actions, Action{Type: ActionDeliverLocal, Datagram: dg})
		if !dg.IsBroadcast() && dg.AckRequested() {
			actions = append(actions, r.buildAck(dg))
		}
	}

	if dg.TTL > 1 && (dg.IsBroadcast() || dg.Destination != r.LocalID) {
		relay := dg
		relay.TTL--
		actions = append(actions, Action{Type: ActionRelayFlood, Datagram: relay, ExcludePeer: incomingPeer})
	}
	return actions
}

func (r *Router) handleSourceRoute(dg Datagram) []Action {
	cursor := int(dg.RouteCursor)
	if cursor >= len(dg.Route) || len(dg.Route) == 0 {
		return r.drop(dg, DropBadRoute)
	}
	if dg.Route[cursor] != r.LocalID {
		return r.drop(dg, DropNotNextHop)
	}
	if dg.Route[len(dg.Route)-1] != dg.Destination {
		return r.drop(dg, DropBadRoute)
	}
	if int(dg.TTL) != len(dg.Route)-cursor {
		return r.drop(dg, DropBadTTL)
	}
	dg.Path = append(append([]NodeID(nil), dg.Path...), r.LocalID)
	dg.TTL--
	dg.RouteCursor++
	if int(dg.RouteCursor) >= len(dg.Route) {
		actions := []Action{{Type: ActionDeliverLocal, Datagram: dg}}
		if dg.AckRequested() {
			actions = append(actions, r.buildAck(dg))
		}
		return actions
	}
	next := dg.Route[dg.RouteCursor]
	return []Action{{Type: ActionRelayNextHop, Datagram: dg, NextHop: &next}}
}

func (r *Router) buildAck(delivered Datagram) Action {
	route := append([]NodeID(nil), delivered.Path[:len(delivered.Path)-1]...)
	for i, j := 0, len(route)-1; i < j; i, j = i+1, j-1 {
		route[i], route[j] = route[j], route[i]
	}
	route = append(route, delivered.Source)
	payload, _ := AckBody{AckedID: delivered.ID}.ToControl()
	ack := Datagram{
		Version:     DatagramVersion,
		ID:          NewDatagramID(),
		Source:      r.LocalID,
		Destination: delivered.Source,
		TTL:         uint8(len(route)),
		Route:       route,
		Protocol:    ProtocolBLEEdgeControl,
		Payload:     payload,
	}
	r.MarkOriginated(ack.ID)
	next := route[0]
	return Action{Type: ActionSendAck, Datagram: ack, NextHop: &next}
}

// BuildBridged constructs an ACK_BRIDGED control datagram addressed to dst (the original sender of
// a bridged channel message). Delivered by source route when one is known, else flooded.
func (r *Router) BuildBridged(dst NodeID, bridgedID DatagramID, meshHash []byte) (Datagram, bool) {
	payload, err := BridgedBody{BridgedID: bridgedID, BridgeID: r.LocalID, MeshHash: meshHash}.ToControl()
	if err != nil {
		return Datagram{}, false
	}
	return r.NewUnicast(dst, ProtocolBLEEdgeControl, payload, 0, DefaultFloodTTL, false)
}

func (r *Router) BuildAnnounce(caps Capabilities, epoch uint64, seq uint32) (Datagram, error) {
	if r.Identity == nil {
		return Datagram{}, fmt.Errorf("router has no signing identity")
	}
	body := NewAnnounceBody(r.Identity, epoch, seq, time.Now().Unix(), caps, r.Neighbors.IDs(), r.Name, r.Description, r.Platform)
	payload, err := body.ToControl()
	if err != nil {
		return Datagram{}, err
	}
	dg := Datagram{
		Version:     DatagramVersion,
		ID:          NewDatagramID(),
		Source:      r.LocalID,
		Destination: BroadcastNodeID,
		TTL:         AnnounceTTL,
		Protocol:    ProtocolBLEEdgeControl,
		Payload:     payload,
	}
	r.MarkOriginated(dg.ID)
	return dg, nil
}

func (r *Router) NewBroadcast(protocol PayloadProtocol, payload []byte, ttl uint8) Datagram {
	if ttl == 0 || ttl > MaxTTL {
		ttl = DefaultFloodTTL
	}
	dg := Datagram{Version: DatagramVersion, ID: NewDatagramID(), Source: r.LocalID, Destination: BroadcastNodeID, TTL: ttl, Protocol: protocol, Payload: payload}
	r.MarkOriginated(dg.ID)
	return dg
}

func (r *Router) NewUnicast(dst NodeID, protocol PayloadProtocol, payload []byte, flags uint16, floodTTL uint8, requireRoute bool) (Datagram, bool) {
	route := r.SelectRoute(dst)
	var dg Datagram
	if len(route) > 0 {
		dg = Datagram{Version: DatagramVersion, ID: NewDatagramID(), Source: r.LocalID, Destination: dst, TTL: uint8(len(route)), Route: route, Protocol: protocol, Flags: flags, Payload: payload}
	} else {
		if requireRoute {
			return Datagram{}, false
		}
		if floodTTL == 0 || floodTTL > MaxTTL {
			floodTTL = DefaultFloodTTL
		}
		dg = Datagram{Version: DatagramVersion, ID: NewDatagramID(), Source: r.LocalID, Destination: dst, TTL: floodTTL, Protocol: protocol, Flags: flags, Payload: payload}
	}
	r.MarkOriginated(dg.ID)
	return dg, true
}

func (r *Router) SelectRoute(dst NodeID) []NodeID {
	if _, ok := r.Neighbors.Get(dst); ok {
		return []NodeID{dst}
	}
	// Seed the search with our direct neighbors: we're never in our own topology, so a plain BFS
	// from LocalID would dead-end and never find a multi-hop source route.
	all := r.Neighbors.All()
	localNeighbors := make([]NodeID, 0, len(all))
	for _, nb := range all {
		localNeighbors = append(localNeighbors, nb.ID)
	}
	return r.Topology.BFSPathFromSource(r.LocalID, dst, localNeighbors)
}

func (r *Router) MarkOriginated(id DatagramID) {
	r.dedup.SeenOrAdd(id)
}

func (r *Router) FloodJitter() time.Duration {
	return time.Duration(10+rand.Intn(91)) * time.Millisecond
}

func (r *Router) drop(dg Datagram, reason DropReason) []Action {
	return []Action{{Type: ActionDrop, Reason: string(reason), Datagram: dg}}
}

func (r *Router) PublicKeyFor(id NodeID) []byte {
	if n, ok := r.Neighbors.Get(id); ok && len(n.PublicKey) == PublicKeyBytes {
		return append([]byte(nil), n.PublicKey...)
	}
	if n, ok := r.Topology.GetNode(id); ok && len(n.PublicKey) == PublicKeyBytes {
		return append([]byte(nil), n.PublicKey...)
	}
	return nil
}

func (r *Router) NameFor(id NodeID) string {
	if n, ok := r.Topology.GetNode(id); ok {
		if n.Name != "" {
			return n.Name
		}
		return DefaultNodeName(n.PublicKey)
	}
	if n, ok := r.Neighbors.Get(id); ok && len(n.PublicKey) == PublicKeyBytes {
		return DefaultNodeName(n.PublicKey)
	}
	return ""
}

func (r *Router) DescriptionFor(id NodeID) string {
	if n, ok := r.Topology.GetNode(id); ok {
		return n.Description
	}
	return ""
}

func (r *Router) PlatformFor(id NodeID) string {
	if n, ok := r.Topology.GetNode(id); ok {
		return n.Platform
	}
	return ""
}
