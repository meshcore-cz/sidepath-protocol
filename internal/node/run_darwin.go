//go:build darwin

package node

import (
	"bufio"
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	mcbridge "github.com/meshcore-cz/sidepath-protocol/bridge/meshcore"
	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/internal/config"
	blenode "github.com/meshcore-cz/sidepath-protocol/macos"
)

// Start brings up the macOS node: BLE transport, the optional MeshCore bridge,
// and the optional Bun bot. It runs the node in the background and returns a
// Runtime handle plus a channel that delivers the node's terminal error. This
// is the headless port of cmd/sidepath-macos (no interactive TUI).
func Start(ctx context.Context, id *core.Identity, cfg *config.Config, nc config.NodeConfig, logf LogFunc) (Runtime, <-chan error, error) {
	nc = nc.Defaulted()

	epoch, err := core.LoadIncrementEpoch(filepath.Join(cfg.Dir, "epoch"))
	if err != nil {
		return nil, nil, fmt.Errorf("load epoch: %w", err)
	}

	var allowlist []core.NodeID
	for _, s := range nc.AllowPeers {
		nid, err := core.ParseNodeID(s)
		if err != nil {
			return nil, nil, fmt.Errorf("invalid allow_peer %q: %w", s, err)
		}
		allowlist = append(allowlist, nid)
	}

	// Advertising a bridged network sets the gateway capability and emits a v2 ANNOUNCE.
	var bridges []core.BridgeAd
	for _, s := range nc.Bridges {
		b, err := core.ParseBridgeSpec(s)
		if err != nil {
			return nil, nil, fmt.Errorf("invalid bridge %q: %w", s, err)
		}
		bridges = append(bridges, b)
	}
	caps := core.Capabilities(core.CapSender | core.CapReceiver | core.CapRelay)
	if len(bridges) > 0 {
		caps |= core.Capabilities(core.CapGateway)
	}

	bridgeLog := func(s string) { logf("%s", s) }

	// br and theBot are assigned below but referenced from OnMessage, which only
	// fires once the node is running by which point both are set (as in sidepath-macos).
	var br *mcbridge.Bridge
	var node *blenode.Node
	var theBot *bot
	rt := &darwinRuntime{
		pending:    make(map[uint32]chan traceOutcome),
		pendingAck: make(map[core.DatagramID]chan core.NodeID),
	}

	node = blenode.New(blenode.Config{
		Identity:      id,
		Name:          nc.Name,
		Description:   nc.Description,
		Caps:          caps,
		Bridges:       bridges,
		Allowlist:     allowlist,
		Verbose:       nc.Verbose,
		AnnounceEpoch: epoch,
		LogFn: func(msg string) {
			if nc.Verbose {
				logf("%s", msg)
			}
		},
		OnMessage: func(dg core.Datagram) {
			outLog := bridgeLog
			// Bridge native Sidepath channel messages outward onto MeshCore as a GRP_TXT, exactly
			// once per Sidepath datagram, then ACK_BRIDGED the original sender.
			if nc.MeshcoreBridge && dg.Protocol == core.ProtocolSidepathChat {
				idHex := hex.EncodeToString(dg.ID[:])
				cp := core.ChannelPayloadFromChat(dg.Payload)
				switch {
				case br == nil:
					outLog(fmt.Sprintf("meshcore bridge: out skip dg=%s reason=bridge-disabled", idHex))
				case len(cp) == 0:
					outLog(fmt.Sprintf("meshcore bridge: out skip dg=%s reason=not-a-channel-message", idHex))
				default:
					go func(src core.NodeID, id core.DatagramID, cp []byte, idHex string) {
						meshHash, bridged, err := br.BridgeChannelOut(ctx, idHex, cp)
						if err != nil {
							outLog(fmt.Sprintf("meshcore bridge: out FAILED dg=%s: %v", idHex, err))
							return
						}
						if !bridged {
							return
						}
						outLog(fmt.Sprintf("meshcore bridge: out dg=%s -> MeshCore GRP_TXT hash=%s", idHex, hex.EncodeToString(meshHash)))
						if err := node.SendBridgedAck(src, id, meshHash); err != nil {
							outLog(fmt.Sprintf("meshcore bridge: ack_bridged FAILED dg=%s: %v", idHex, err))
						} else {
							outLog(fmt.Sprintf("meshcore bridge: ack_bridged -> %s dg=%s", src, idHex))
						}
					}(dg.Source, dg.ID, cp, idHex)
				}
			}
			// A MESHCORE_PACKET from another Sidepath node is a raw MeshCore packet to place on the
			// radio verbatim, deduped by content.
			if nc.MeshcoreBridge && br != nil && dg.Protocol == core.ProtocolMeshCorePacket {
				go func(raw []byte, src core.NodeID) {
					injected, err := br.BridgeRawOut(ctx, raw)
					switch {
					case err != nil:
						outLog(fmt.Sprintf("meshcore bridge: raw out FAILED from=%s: %v", src, err))
					case injected:
						outLog(fmt.Sprintf("meshcore bridge: raw out from=%s -> MeshCore bytes=%d", src, len(raw)))
					}
				}(dg.Payload, dg.Source)
			}
			if theBot != nil {
				theBot.onIncoming(dg)
			}
		},
		OnPeerConnect:    func(id core.NodeID) { logf("+++ connected: %s", id) },
		OnPeerDisconnect: func(id core.NodeID) { logf("--- disconnected: %s", id) },
		OnTrace: func(resp core.TraceResponseBody, rtt time.Duration) {
			rt.deliverTrace(resp, rtt)
			if nc.Verbose {
				logf("trace result tag=%08x rtt=%s", resp.Tag, rtt.Round(time.Millisecond))
			}
		},
		OnAck: func(ackedID core.DatagramID, from core.NodeID) {
			rt.deliverAck(ackedID, from)
		},
	})
	rt.node = node

	errCh := make(chan error, 1)
	go func() { errCh <- node.Run(ctx) }()

	// MeshCore bridge: tap the meshcore-go backend and re-flood packets into the Sidepath mesh.
	if nc.MeshcoreBridge {
		// Tagging packets with a single configured network lets receivers attribute them; with zero
		// or several we emit the legacy raw payload and receivers fall back to signed-announce.
		var bridgeNetworkCode string
		if len(bridges) == 1 {
			bridgeNetworkCode = bridges[0].Code
		}
		br = mcbridge.New(mcbridge.Config{Socket: nc.MeshcoreSocket, Log: bridgeLog})
		go br.Run(ctx, func(pkt mcbridge.Packet) {
			framed := core.FrameMeshCorePacket(bridgeNetworkCode, pkt.Bytes)
			switch pkt.Mode {
			case mcbridge.ForwardFlood:
				tx, err := node.SendMeshCoreRawWithInfo(framed)
				if err != nil {
					bridgeLog(fmt.Sprintf("meshcore bridge: flood failed: %v packet=%s", err, pkt.Summary()))
					return
				}
				bridgeLog(fmt.Sprintf("meshcore bridge: flooded dg=%s bytes=%d fragments=%d %s",
					hex.EncodeToString(tx.DatagramID[:]), tx.DatagramBytes, tx.FragmentCount, pkt.Summary()))
			case mcbridge.ForwardDirect:
				targetHex := hex.EncodeToString(pkt.TargetHash)
				cands := node.MeshCoreCandidatesForHash(pkt.TargetHash)
				if len(cands) == 0 {
					bridgeLog(fmt.Sprintf("meshcore bridge: dest hash %s matches no Sidepath node — known=[%s]",
						targetHex, knownNodeHashes(node)))
					tx, err := node.SendMeshCoreRawWithInfo(framed)
					if err != nil {
						bridgeLog(fmt.Sprintf("meshcore bridge: direct->flood failed: %v packet=%s", err, pkt.Summary()))
						return
					}
					bridgeLog(fmt.Sprintf("meshcore bridge: direct->flood dest=%s dg=%s bytes=%d fragments=%d %s",
						targetHex, hex.EncodeToString(tx.DatagramID[:]), tx.DatagramBytes, tx.FragmentCount, pkt.Summary()))
					return
				}
				bridgeLog(fmt.Sprintf("meshcore bridge: dest hash %s -> %d candidate(s): %s  [%s]",
					targetHex, len(cands), describeCandidates(node, cands), pkt.Summary()))
				for _, dst := range cands {
					tx, err := node.SendMeshCoreRawToWithInfo(dst, framed)
					if err != nil {
						bridgeLog(fmt.Sprintf("meshcore bridge: direct failed dst=%s [%s] err=%v", dst, node.NameFor(dst), err))
						continue
					}
					bridgeLog(fmt.Sprintf("meshcore bridge: direct dst=%s [%s] via=%s dg=%s bytes=%d fragments=%d",
						dst, node.NameFor(dst), routeLabel(node, dst), hex.EncodeToString(tx.DatagramID[:]), tx.DatagramBytes, tx.FragmentCount))
				}
			default:
				bridgeLog("meshcore bridge: skip unknown forwarding mode " + pkt.Summary())
			}
		})
	}

	// Bot mode: start the Bun bridge headless. Without a bot we still join the configured
	// channels so the node participates in (and relays) them.
	if nc.Bot != "" {
		b, err := startBot(ctx, node, nc.Bun, nc.Bot, nc.Channels, func(s string) { logf("%s", s) })
		if err != nil {
			return nil, nil, fmt.Errorf("start bot: %w", err)
		}
		theBot = b
		logf("bot running script=%s channels=%v", nc.Bot, nc.Channels)
	} else {
		for _, name := range nc.Channels {
			if name == "Public" {
				node.JoinPublicChannel()
			} else {
				node.JoinNamedChannel(name)
			}
		}
	}

	logf("node started id=%s name=%s bridge=%v bot=%v channels=%v",
		id.NodeID(), node.Name(), nc.MeshcoreBridge, nc.Bot != "", nc.Channels)
	return rt, errCh, nil
}

// traceOutcome is a trace response delivered to a waiting Trace call.
type traceOutcome struct {
	resp core.TraceResponseBody
	rtt  time.Duration
}

// darwinRuntime exposes the live node to the daemon control API and tracks
// in-flight traces, keyed by tag, so async OnTrace responses reach the caller.
type darwinRuntime struct {
	node       *blenode.Node
	mu         sync.Mutex
	pending    map[uint32]chan traceOutcome         // in-flight traces, keyed by tag
	pendingAck map[core.DatagramID]chan core.NodeID // in-flight acks, keyed by sent datagram ID
}

func (r *darwinRuntime) NodeID() string { return r.node.NodeID().String() }

// Peers returns every node this node knows about: all topology entries (learned
// via signed ANNOUNCE) plus any directly-linked peer, each annotated with the
// live link state, the current route, and the latest announce data. Connected
// peers sort first.
func (r *darwinRuntime) Peers() []api.Peer {
	dir := make(map[core.NodeID]string)
	for _, p := range r.node.PeerLinks() {
		dir[p.ID] = p.Direction
	}
	nbr := make(map[core.NodeID]core.Neighbor)
	for _, nb := range r.node.Neighbors() {
		nbr[nb.ID] = nb
	}

	self := r.node.NodeID()
	seen := make(map[core.NodeID]bool)
	var out []api.Peer

	add := func(id core.NodeID, tn *core.TopoNode) {
		if id == self || seen[id] {
			return
		}
		seen[id] = true
		p := api.Peer{NodeID: id.String(), Hops: -1, LastAnnounceS: -1, LastRxS: -1}
		if d, ok := dir[id]; ok {
			p.Connected = true
			p.Direction = d
			if nb, ok := nbr[id]; ok {
				p.TxPHY = nb.TxPHY.String()
				p.RxPHY = nb.RxPHY.String()
				p.Quality = nb.QualityQ8
				p.RTTms = nb.RTTms
				p.RSSIEWMA = nb.RSSIEWMA
			}
			p.RxPackets, p.TxPackets, p.LastRxS = packetStats(r.node, id)
		}
		// RSSI (dBm) is only known when a scan sampled it; 0 means unknown.
		p.RSSI = r.node.RSSIFor(id)
		if route, ok := r.node.RouteTo(id); ok {
			// RouteTo includes the destination as the final element, so the number
			// of relay hops between us is len(route)-1 (0 = direct neighbor).
			p.Hops = len(route) - 1
		}
		if tn != nil {
			p.Name = tn.Name
			p.Platform = tn.Platform
			p.Description = tn.Description
			p.Relay = tn.Caps.IsRelay()
			p.Gateway = tn.Caps.IsGateway()
			p.Neighbors = len(tn.Neighbors)
			p.AnnounceEpoch = tn.Epoch
			p.AnnounceSeq = tn.Seq
			if !tn.LastSeen.IsZero() {
				p.LastAnnounceS = int64(time.Since(tn.LastSeen).Seconds())
			}
		}
		out = append(out, p)
	}

	for _, tn := range r.node.Topology() {
		tn := tn
		add(tn.ID, &tn)
	}
	// Connected peers whose ANNOUNCE we haven't processed yet still appear.
	for _, p := range r.node.PeerLinks() {
		add(p.ID, nil)
	}

	sort.Slice(out, func(i, j int) bool {
		if out[i].Connected != out[j].Connected {
			return out[i].Connected // connected first
		}
		return out[i].NodeID < out[j].NodeID
	})
	return out
}

// Topology returns the mesh graph as this node sees it: every node learned via
// ANNOUNCE with its advertised neighbors, plus the local node itself with its
// live direct links. Node labels are resolved to friendly names; nodes are
// sorted with the local node first, then connected nodes, then by ID.
func (r *darwinRuntime) Topology() api.TopologyResult {
	self := r.node.NodeID()
	connected := make(map[core.NodeID]bool)
	for _, p := range r.node.PeerLinks() {
		connected[p.ID] = true
	}

	nodes := make([]api.TopologyNode, 0)

	// The local node never processes its own ANNOUNCE, so it is absent from the
	// topology; add it explicitly with its current direct neighbors, carrying the
	// live link metrics (RSSI/PHY/direction/age) so route ranking can weigh them.
	selfNbrs := make([]string, 0)
	selfLinks := make([]api.NeighborDetail, 0)
	for _, nb := range r.node.Neighbors() {
		selfNbrs = append(selfNbrs, nb.ID.String())
		var age uint32
		if d := time.Since(nb.LastSeen); d > 0 {
			age = uint32(d / time.Second)
		}
		selfLinks = append(selfLinks, api.NeighborDetail{
			NodeID:    nb.ID.String(),
			Name:      r.node.NameFor(nb.ID),
			HasInfo:   true,
			RSSI:      nb.RSSI,
			TxPHY:     nb.TxPHY.String(),
			RxPHY:     nb.RxPHY.String(),
			Direction: directionString(nb.Direction),
			AgeS:      age,
			Transport: core.TransportBLE.String(),
			RSSIEWMA:  nb.RSSIEWMA,
			QualityQ8: nb.QualityQ8,
			LatencyMs: nb.RTTms,
		})
	}
	sort.Strings(selfNbrs)
	nodes = append(nodes, api.TopologyNode{
		NodeID:        self.String(),
		Name:          r.node.Name(),
		Self:          true,
		Neighbors:     selfNbrs,
		Links:         selfLinks,
		LastAnnounceS: -1,
	})

	for _, tn := range r.node.Topology() {
		if tn.ID == self {
			continue
		}
		nbrs := make([]string, 0, len(tn.Neighbors))
		for _, nid := range tn.Neighbors {
			nbrs = append(nbrs, nid.String())
		}
		sort.Strings(nbrs)
		last := int64(-1)
		if !tn.LastSeen.IsZero() {
			last = int64(time.Since(tn.LastSeen).Seconds())
		}
		nodes = append(nodes, api.TopologyNode{
			NodeID:        tn.ID.String(),
			Name:          r.node.NameFor(tn.ID),
			Connected:     connected[tn.ID],
			Neighbors:     nbrs,
			Links:         r.neighborDetails(tn),
			LastAnnounceS: last,
		})
	}

	// Connected peers whose ANNOUNCE we haven't processed yet still belong in the
	// graph (with an empty neighbor list until their announce arrives).
	seen := make(map[string]bool, len(nodes))
	for _, n := range nodes {
		seen[n.NodeID] = true
	}
	for _, p := range r.node.PeerLinks() {
		if p.ID == self || seen[p.ID.String()] {
			continue
		}
		nodes = append(nodes, api.TopologyNode{
			NodeID:        p.ID.String(),
			Name:          r.node.NameFor(p.ID),
			Connected:     true,
			Neighbors:     []string{},
			LastAnnounceS: -1,
		})
	}

	sort.Slice(nodes, func(i, j int) bool {
		if nodes[i].Self != nodes[j].Self {
			return nodes[i].Self
		}
		if nodes[i].Connected != nodes[j].Connected {
			return nodes[i].Connected
		}
		return nodes[i].NodeID < nodes[j].NodeID
	})

	return api.TopologyResult{Self: self.String(), Nodes: nodes}
}

// Peer returns the full detail for a single node by NodeID (hex): the same
// summary fields the peer list shows, plus the public key, bridged networks, and
// the node's own neighbor list with the per-link details (RSSI, PHY, direction,
// age) it advertised in its latest v3 ANNOUNCE.
func (r *darwinRuntime) Peer(idHex string) (*api.PeerDetail, error) {
	id, err := core.ParseNodeID(idHex)
	if err != nil {
		return nil, fmt.Errorf("invalid node id %q: %w", idHex, err)
	}

	dir := ""
	for _, p := range r.node.PeerLinks() {
		if p.ID == id {
			dir = p.Direction
			break
		}
	}
	var nb *core.Neighbor
	for _, n := range r.node.Neighbors() {
		if n.ID == id {
			nn := n
			nb = &nn
			break
		}
	}
	var tn *core.TopoNode
	for _, t := range r.node.Topology() {
		if t.ID == id {
			tt := t
			tn = &tt
			break
		}
	}
	if tn == nil && dir == "" {
		return nil, fmt.Errorf("unknown node %s (no announce seen and not connected)", idHex)
	}

	p := api.Peer{NodeID: id.String(), Hops: -1, LastAnnounceS: -1, LastRxS: -1}
	if dir != "" {
		p.Connected = true
		p.Direction = dir
		if nb != nil {
			p.TxPHY = nb.TxPHY.String()
			p.RxPHY = nb.RxPHY.String()
			p.Quality = nb.QualityQ8
			p.RTTms = nb.RTTms
			p.RSSIEWMA = nb.RSSIEWMA
		}
		p.RxPackets, p.TxPackets, p.LastRxS = packetStats(r.node, id)
	}
	p.RSSI = r.node.RSSIFor(id)
	if route, ok := r.node.RouteTo(id); ok {
		p.Hops = len(route) - 1
	}

	detail := &api.PeerDetail{}
	if tn != nil {
		p.Name = tn.Name
		p.Platform = tn.Platform
		p.Description = tn.Description
		p.Relay = tn.Caps.IsRelay()
		p.Gateway = tn.Caps.IsGateway()
		p.Neighbors = len(tn.Neighbors)
		p.AnnounceEpoch = tn.Epoch
		p.AnnounceSeq = tn.Seq
		if !tn.LastSeen.IsZero() {
			p.LastAnnounceS = int64(time.Since(tn.LastSeen).Seconds())
		}
		detail.Pubkey = hex.EncodeToString(tn.PublicKey)
		for _, b := range tn.Bridges {
			detail.Bridges = append(detail.Bridges, b.Code)
		}
		detail.NeighborList = r.neighborDetails(*tn)
	}
	detail.NeighborOfList = r.reverseNeighborDetails(id)
	detail.Peer = p
	return detail, nil
}

// reverseNeighborDetails is the inverse of neighborDetails: every node that lists
// [id] in its own advertised neighbor set, with the per-link details that node
// reported about the link. Sources are the local node's neighbor table (shown as
// "(this node)") and every topology node's latest announce (v3 per-link info when
// present, IDs only otherwise). Lets `sp peer` show a link from both ends.
func (r *darwinRuntime) reverseNeighborDetails(id core.NodeID) []api.NeighborDetail {
	self := r.node.NodeID()
	names := make(map[core.NodeID]string)
	for _, t := range r.node.Topology() {
		if t.Name != "" {
			names[t.ID] = t.Name
		}
	}

	var out []api.NeighborDetail
	// The local node advertises its own direct neighbors; surface our link to id.
	for _, nb := range r.node.Neighbors() {
		if nb.ID != id {
			continue
		}
		var age uint32
		if d := time.Since(nb.LastSeen); d > 0 {
			age = uint32(d / time.Second)
		}
		out = append(out, api.NeighborDetail{
			NodeID:    self.String(),
			Name:      "(this node)",
			HasInfo:   true,
			RSSI:      nb.RSSI,
			TxPHY:     nb.TxPHY.String(),
			RxPHY:     nb.RxPHY.String(),
			Direction: directionString(nb.Direction),
			AgeS:      age,
		})
		break
	}

	// Every remote node whose latest announce lists id as a neighbor.
	for _, tn := range r.node.Topology() {
		if tn.ID == self || tn.ID == id {
			continue
		}
		if len(tn.NeighborInfo) > 0 {
			for _, ni := range tn.NeighborInfo {
				if ni.ID != id {
					continue
				}
				// The reverse entry is keyed/displayed by the advertising node, but
				// carries that node's link metrics to id.
				d := neighborInfoDetail(ni, names[tn.ID])
				d.NodeID = tn.ID.String()
				out = append(out, d)
				break
			}
			continue
		}
		for _, nid := range tn.Neighbors {
			if nid == id {
				out = append(out, api.NeighborDetail{NodeID: tn.ID.String(), Name: names[tn.ID]})
				break
			}
		}
	}
	return out
}

// neighborDetails renders a peer's advertised neighbor list. v3 announces carry
// per-link details (HasInfo true); v1/v2 announces yield IDs only. Neighbor names
// are resolved from our own topology when that node is also known.
func (r *darwinRuntime) neighborDetails(tn core.TopoNode) []api.NeighborDetail {
	names := make(map[core.NodeID]string)
	for _, t := range r.node.Topology() {
		if t.Name != "" {
			names[t.ID] = t.Name
		}
	}
	self := r.node.NodeID()
	nameOf := func(id core.NodeID) string {
		if id == self {
			return "(this node)"
		}
		return names[id]
	}

	if len(tn.NeighborInfo) > 0 {
		out := make([]api.NeighborDetail, 0, len(tn.NeighborInfo))
		for _, ni := range tn.NeighborInfo {
			out = append(out, neighborInfoDetail(ni, nameOf(ni.ID)))
		}
		return out
	}

	out := make([]api.NeighborDetail, 0, len(tn.Neighbors))
	for _, nid := range tn.Neighbors {
		out = append(out, api.NeighborDetail{NodeID: nid.String(), Name: nameOf(nid)})
	}
	return out
}

// packetStats reads a connected peer's lifetime RX/TX Sidepath-packet counts and
// converts the last-receive timestamp to seconds-ago (-1 when nothing received).
func packetStats(node *blenode.Node, id core.NodeID) (rx, tx uint64, lastRxS int64) {
	rx, tx, lastRX := node.PacketStats(id)
	lastRxS = int64(-1)
	if !lastRX.IsZero() {
		lastRxS = int64(time.Since(lastRX).Seconds())
	}
	return rx, tx, lastRxS
}

// neighborInfoDetail converts a signed v3 NeighborInfo entry into the API shape,
// carrying both the original per-link fields and the extended quality hints.
func neighborInfoDetail(ni core.NeighborInfo, name string) api.NeighborDetail {
	d := api.NeighborDetail{
		NodeID:    ni.ID.String(),
		Name:      name,
		HasInfo:   true,
		RSSI:      int(ni.RSSI),
		TxPHY:     ni.TxPHY.String(),
		RxPHY:     ni.RxPHY.String(),
		Direction: directionString(ni.Dir),
		AgeS:      ni.AgeS,
		RSSIEWMA:  int(ni.RSSIEWMA),
		QualityQ8: ni.QualityQ8,
		LatencyMs: ni.LatencyMs,
		QueueQ8:   ni.QueueQ8,
	}
	if ni.Transport != core.TransportUnknown {
		d.Transport = ni.Transport.String()
	}
	return d
}

// directionString maps a wire connection direction to the same labels the peer
// list uses for live links.
func directionString(d core.ConnDirection) string {
	switch d {
	case core.DirectionOutgoing:
		return "outbound"
	case core.DirectionIncoming:
		return "inbound"
	case core.DirectionBoth:
		return "in+out"
	default:
		return ""
	}
}

// SendDirect sends an encrypted direct message to dest. The recipient's public
// key is resolved from the topology (learned via signed ANNOUNCE). When wantAck
// is set it waits for the ACK until ctx is cancelled, reporting the round-trip
// time; a timeout returns Acked=false (the message was still sent).
func (r *darwinRuntime) SendDirect(ctx context.Context, dest, text string, route []string, wantAck bool) (api.SendResult, error) {
	id, err := core.ParseNodeID(dest)
	if err != nil {
		return api.SendResult{}, fmt.Errorf("invalid destination %q: %w", dest, err)
	}
	hops := make([]core.NodeID, 0, len(route))
	for _, h := range route {
		hop, err := core.ParseNodeID(h)
		if err != nil {
			return api.SendResult{}, fmt.Errorf("invalid path hop %q: %w", h, err)
		}
		hops = append(hops, hop)
	}

	dgID, used, flooded, err := r.node.SendChatRouted(id, text, hops)
	if err != nil {
		return api.SendResult{}, err
	}
	res := api.SendResult{Dest: id.String(), DatagramID: hex.EncodeToString(dgID[:]), Flooded: flooded}
	for _, h := range used {
		res.Route = append(res.Route, h.String())
	}
	if !wantAck {
		return res, nil
	}

	// A direct send (destination is a one-hop neighbor) yields clean link feedback:
	// the ACK outcome and round-trip time describe that single link. Multi-hop ACKs
	// are end-to-end and would mis-attribute, so we only feed the link stats here.
	direct := !flooded && len(used) <= 1

	start := time.Now()
	ch := make(chan core.NodeID, 1)
	r.mu.Lock()
	r.pendingAck[dgID] = ch
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.pendingAck, dgID)
		r.mu.Unlock()
	}()

	select {
	case <-ctx.Done():
		if direct {
			r.node.RecordLinkDelivery(id, false)
		}
		return res, nil
	case from := <-ch:
		res.Acked = true
		res.From = from.String()
		res.RTTMs = time.Since(start).Milliseconds()
		if direct {
			r.node.RecordLinkDelivery(id, true)
			if res.RTTMs > 0 && res.RTTMs <= 65535 {
				r.node.RecordLinkRTT(id, uint16(res.RTTMs))
			}
		}
		return res, nil
	}
}

// deliverAck routes an ACK to the matching in-flight SendDirect call.
func (r *darwinRuntime) deliverAck(ackedID core.DatagramID, from core.NodeID) {
	r.mu.Lock()
	ch := r.pendingAck[ackedID]
	r.mu.Unlock()
	if ch != nil {
		select {
		case ch <- from:
		default:
		}
	}
}

// SendChannel broadcasts a message on the named channel, joining it first
// (idempotent). An empty name or "Public" targets the MeshCore Public channel.
func (r *darwinRuntime) SendChannel(channel, text string) error {
	var ch *blenode.Channel
	if channel == "" || strings.EqualFold(channel, "Public") {
		ch = r.node.JoinPublicChannel()
	} else {
		ch = r.node.JoinNamedChannel(channel)
	}
	return r.node.SendToChannel(ch.Secret, text)
}

// deliverTrace routes a trace response to the matching in-flight Trace call.
func (r *darwinRuntime) deliverTrace(resp core.TraceResponseBody, rtt time.Duration) {
	r.mu.Lock()
	ch := r.pending[resp.Tag]
	r.mu.Unlock()
	if ch != nil {
		select {
		case ch <- traceOutcome{resp: resp, rtt: rtt}:
		default:
		}
	}
}

// Trace sends a trace to dest (optionally pinned to an explicit relay route) and
// waits for the response or ctx cancellation.
func (r *darwinRuntime) Trace(ctx context.Context, dest string, route []string) (api.TraceResult, error) {
	dst, err := core.ParseNodeID(dest)
	if err != nil {
		return api.TraceResult{}, fmt.Errorf("invalid destination %q: %w", dest, err)
	}
	var hops []core.NodeID
	for _, h := range route {
		id, err := core.ParseNodeID(h)
		if err != nil {
			return api.TraceResult{}, fmt.Errorf("invalid route hop %q: %w", h, err)
		}
		hops = append(hops, id)
	}

	tag, err := r.node.SendTrace(dst, hops)
	if err != nil {
		return api.TraceResult{}, err
	}
	ch := make(chan traceOutcome, 1)
	r.mu.Lock()
	r.pending[tag] = ch
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.pending, tag)
		r.mu.Unlock()
	}()

	select {
	case <-ctx.Done():
		return api.TraceResult{}, fmt.Errorf("trace to %s timed out", dst)
	case out := <-ch:
		path := make([]string, len(out.resp.ForwardPath))
		for i, id := range out.resp.ForwardPath {
			path[i] = id.String()
		}
		// A trace with no forward relays went straight to a direct neighbor, so its
		// round-trip time is a clean single-link sample for the live stats.
		if len(out.resp.ForwardPath) == 0 {
			if ms := out.rtt.Milliseconds(); ms > 0 && ms <= 65535 {
				r.node.RecordLinkRTT(dst, uint16(ms))
			}
		}
		return api.TraceResult{
			Tag:    out.resp.Tag,
			Metric: traceMetricName(out.resp.Metric),
			RTTMs:  out.rtt.Milliseconds(),
			Path:   path,
		}, nil
	}
}

func traceMetricName(m core.TraceMetric) string {
	switch m {
	case core.TraceMetricRSSIDBM:
		return core.TraceMetricNameRSSI
	case core.TraceMetricSNRQ4:
		return core.TraceMetricNameSNR
	default:
		return core.TraceMetricNameUnknown
	}
}

// --- bot (ported headless from cmd/sidepath-macos) -------------------------

// lineWriter forwards a subprocess's output to a per-line sink (Bun's stderr).
type lineWriter struct{ emit func(string) }

func (w lineWriter) Write(p []byte) (int, error) {
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		if line != "" {
			w.emit("[bun] " + line)
		}
	}
	return len(p), nil
}

// bot bridges the node to a user script run by the Bun JS/TS runtime over
// newline-delimited JSON on the script's stdin/stdout. See bots/ for examples.
type bot struct {
	node      *blenode.Node
	mu        sync.Mutex
	stdin     io.Writer
	logf      func(string)
	pubByNode map[core.NodeID][]byte
	channels  []*blenode.Channel
}

func startBot(ctx context.Context, node *blenode.Node, bunPath, script string, channelNames []string, logf func(string)) (*bot, error) {
	cmd := exec.CommandContext(ctx, bunPath, "run", script)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = lineWriter{emit: logf}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start bun (%s): %w", bunPath, err)
	}

	b := &bot{node: node, stdin: stdin, logf: logf, pubByNode: make(map[core.NodeID][]byte)}
	for _, name := range channelNames {
		if name == "Public" {
			b.channels = append(b.channels, node.JoinPublicChannel())
		} else {
			b.channels = append(b.channels, node.JoinNamedChannel(name))
		}
	}

	go b.readLoop(stdout)
	go func() {
		_ = cmd.Wait()
		logf("bot: bun process exited")
	}()

	chNames := make([]string, len(b.channels))
	for i, ch := range b.channels {
		chNames[i] = ch.Name
	}
	b.send(map[string]any{
		"type": "ready",
		"self": map[string]any{
			"node":     node.NodeID().String(),
			"name":     node.Name(),
			"channels": chNames,
		},
	})
	return b, nil
}

func (b *bot) defaultChannel() *blenode.Channel {
	if len(b.channels) == 0 {
		return nil
	}
	return b.channels[0]
}

func (b *bot) channelByName(name string) *blenode.Channel {
	if name != "" {
		for _, ch := range b.channels {
			if strings.EqualFold(ch.Name, name) {
				return ch
			}
		}
	}
	return b.defaultChannel()
}

func (b *bot) send(v any) {
	data, err := json.Marshal(v)
	if err != nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	fmt.Fprintf(b.stdin, "%s\n", data)
}

func (b *bot) onIncoming(dg core.Datagram) {
	from := dg.Source
	msg := map[string]any{
		"type": "message",
		"from": from.String(),
		"name": b.node.NameFor(from),
		"ts":   time.Now().UnixMilli(),
	}
	if dg.Protocol != core.ProtocolSidepathChat {
		return
	}
	if in, ok := b.node.DecodeChannel(dg.Payload); ok {
		msg["text"] = in.Text
		msg["channel"] = true
		msg["channelName"] = in.Channel
		msg["sender"] = in.Sender
		b.send(msg)
		return
	}
	env, err := core.DecodeChatEnvelope(dg.Payload)
	if err != nil {
		return
	}
	cctx := core.ChatContext{DatagramID: dg.ID, Source: dg.Source, Destination: dg.Destination}
	switch env.Kind {
	case core.ChatDirectText:
		t, ok := core.OpenDirectText(b.node.Identity(), dg.Payload, cctx)
		if !ok {
			return
		}
		msg["text"] = t.Text
		msg["channel"] = false
		if len(t.SenderPublicKey) == 32 {
			b.mu.Lock()
			b.pubByNode[from] = t.SenderPublicKey
			b.mu.Unlock()
		}
	case core.ChatPublicText:
		cctx.Destination = core.BroadcastNodeID
		t, ok := core.OpenPublicText(dg.Payload, cctx)
		if !ok {
			return
		}
		msg["text"] = t.Text
		msg["channel"] = true
	default:
		return
	}
	b.send(msg)
}

type botCommand struct {
	Type    string `json:"type"`
	To      string `json:"to"`
	Text    string `json:"text"`
	What    string `json:"what"`
	Channel string `json:"channel"`
}

func (b *bot) readLoop(r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var c botCommand
		if err := json.Unmarshal(line, &c); err != nil {
			b.logf(fmt.Sprintf("bot: bad command json: %v", err))
			continue
		}
		switch c.Type {
		case "reply":
			id, err := core.ParseNodeID(c.To)
			if err != nil {
				b.logf(fmt.Sprintf("bot reply: bad 'to' %q: %v", c.To, err))
				continue
			}
			b.mu.Lock()
			pub := b.pubByNode[id]
			b.mu.Unlock()
			if len(pub) != 32 {
				pub = b.node.PublicKeyFor(id)
			}
			if len(pub) != 32 {
				b.logf(fmt.Sprintf("bot reply: no public key known for %s", c.To))
				continue
			}
			if err := b.node.SendChatTo(id, pub, c.Text); err != nil {
				b.logf(fmt.Sprintf("bot reply send: %v", err))
			}
		case "broadcast":
			ch := b.channelByName(c.Channel)
			if ch == nil {
				b.logf("bot broadcast: bot has no channels to broadcast on")
				continue
			}
			if err := b.node.SendToChannel(ch.Secret, c.Text); err != nil {
				b.logf(fmt.Sprintf("bot broadcast: %v", err))
			}
		case "typing":
			id, err := core.ParseNodeID(c.To)
			if err != nil {
				b.logf(fmt.Sprintf("bot typing: bad 'to' %q: %v", c.To, err))
				continue
			}
			if err := b.node.SendTyping(id); err != nil {
				b.logf(fmt.Sprintf("bot typing send: %v", err))
			}
		case "query":
			if c.What == "stats" {
				b.send(map[string]any{
					"type":      "stats",
					"peers":     len(b.node.ConnectedPeers()),
					"neighbors": len(b.node.Neighbors()),
					"topology":  len(b.node.Topology()),
					"node":      b.node.NodeID().String(),
					"name":      b.node.Name(),
				})
			}
		case "log":
			b.logf(fmt.Sprintf("bot: %s", c.Text))
		default:
			b.logf(fmt.Sprintf("bot: unknown command %q", c.Type))
		}
	}
}

// --- bridge logging helpers (ported from cmd/sidepath-macos) ---------------

func formatRoute(route []core.NodeID) string {
	parts := make([]string, len(route))
	for i, id := range route {
		parts[i] = id.String()
	}
	return strings.Join(parts, " -> ")
}

func idShort(id core.NodeID) string {
	s := id.String()
	if len(s) > 8 {
		return s[:8]
	}
	return s
}

func nameLabel(name string) string {
	if name == "" {
		return "?"
	}
	return name
}

func routeLabel(node *blenode.Node, dst core.NodeID) string {
	route, ok := node.RouteTo(dst)
	switch {
	case !ok:
		return "no-route"
	case len(route) == 0:
		return "direct"
	default:
		return fmt.Sprintf("%d hops: %s", len(route), formatRoute(route))
	}
}

func describeCandidates(node *blenode.Node, cands []core.NodeID) string {
	parts := make([]string, len(cands))
	for i, id := range cands {
		parts[i] = fmt.Sprintf("%s(%s, %s)", idShort(id), nameLabel(node.NameFor(id)), routeLabel(node, id))
	}
	return strings.Join(parts, ", ")
}

func knownNodeHashes(node *blenode.Node) string {
	seen := make(map[core.NodeID]bool)
	type entry struct {
		hash byte
		name string
	}
	var entries []entry
	add := func(id core.NodeID) {
		if seen[id] {
			return
		}
		pub := node.PublicKeyFor(id)
		if len(pub) == 0 {
			return
		}
		seen[id] = true
		entries = append(entries, entry{hash: pub[0], name: nameLabel(node.NameFor(id))})
	}
	for _, nb := range node.Neighbors() {
		add(nb.ID)
	}
	for _, tn := range node.Topology() {
		add(tn.ID)
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].hash < entries[j].hash })
	parts := make([]string, len(entries))
	for i, e := range entries {
		parts[i] = fmt.Sprintf("%02x:%s", e.hash, e.name)
	}
	return strings.Join(parts, " ")
}
