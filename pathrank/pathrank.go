// Package pathrank is an experimental, self-contained route ranker for the
// Sidepath mesh graph. Given a weighted view of the topology — who can hear
// whom, how well, how recently, and how much we trust that information — it
// enumerates the candidate source routes between two nodes and ranks them by a
// transparent cost model, so a caller can see not just the winning route but
// exactly why it won.
//
// It deliberately knows nothing about the control API, the daemon, or BLE: it
// operates on plain core.NodeIDs and per-link metrics. Keeping the ranking
// logic isolated here makes it easy to tune and unit-test, and lets the message
// router reuse the same scoring later instead of growing its own copy.
//
// Design: announce v3 link details (§8.8) are routing *hints* that seed the
// graph; a real router should prefer its own live observations (see Source).
// Reliability is modeled multiplicatively — each hop has a success probability
// p, and the route's reliability cost is -W·Σ ln(p). Because that sum grows fast
// as any p→0, a single bad hop dominates and extra hops accumulate, so a short
// all-stable route beats a longer route with one weak link.
package pathrank

import (
	"math"
	"sort"

	"github.com/meshcore-cz/sidepath-protocol/core"
)

// LinkSource is how we came to know a link, i.e. how much we trust its metrics.
// Lower-confidence sources carry a larger additive penalty.
type LinkSource uint8

const (
	SourceUnknown    LinkSource = iota
	SourceLocal                 // local direct observation (most trusted)
	SourceReciprocal            // both ends advertise the link, and agree it exists
	SourceForward               // only the near end advertised this link
	SourceReverse               // only the far end advertised it; we borrowed its view
	SourceLegacy                // ID-only edge from a v1/v2 announce (no metrics)
)

func (s LinkSource) String() string {
	switch s {
	case SourceLocal:
		return "local"
	case SourceReciprocal:
		return "reciprocal"
	case SourceForward:
		return "forward"
	case SourceReverse:
		return "reverse"
	case SourceLegacy:
		return "legacy"
	default:
		return "unknown"
	}
}

// Link is the quality of one directed hop, as the route ranker sees it. Fields
// mirror what a node advertises about a neighbor in a v3 ANNOUNCE (§8.8) or what
// a node observes on its own live link; 0 means "unknown" for each metric.
type Link struct {
	Transport core.Transport
	TxPHY     core.PHY
	RxPHY     core.PHY
	RSSI      int    // last-sample RSSI in dBm (negative in practice; 0 = unknown)
	RSSIEWMA  int    // smoothed RSSI in dBm; preferred over RSSI when present
	QualityQ8 uint8  // normalized recent reliability 0..255; preferred over RSSI when present
	AgeS      uint32 // effective link age in seconds (caller folds in announce-receive delay)
	RTTms     uint16 // representative round-trip latency in ms; 0 = unknown
	QueueQ8   uint8  // congestion/backpressure 0..255; 0 = none/unknown
	Source    LinkSource
	HasInfo   bool // false = no per-link metrics at all (a bare ID-only edge)
}

// Weights tunes the cost model. Penalties are additive and in the same abstract
// unit; reliability is folded in via ReliabilityWeight (see package doc).
type Weights struct {
	HopBase float64 // fixed cost per hop — the floor that favors shorter routes

	// Reliability: a hop contributes -ReliabilityWeight·ln(p). MinProb floors p so
	// the log stays finite; UnknownProb is assumed when neither quality nor RSSI is
	// known.
	ReliabilityWeight float64
	MinProb           float64
	UnknownProb       float64

	// Freshness (additive): free up to FreshGraceS, then FreshScale per second to
	// FreshCap. Hard expiry (dropping the edge entirely) is the caller's job.
	FreshGraceS uint32
	FreshScale  float64
	FreshCap    float64

	LatencyScale float64 // additive, per ms of RTT
	LatencyCap   float64
	QueueMax     float64 // additive cost at QueueQ8 == 255

	TransportPenalty  map[core.Transport]float64 // additive, by transport
	ConfidencePenalty map[LinkSource]float64     // additive, by source
	UnknownPenalty    float64                    // additive, when HasInfo is false
}

// DefaultWeights is a reasonable starting point. ReliabilityWeight dominates the
// hop floor, so a strong route wins on quality, but every hop still costs HopBase
// so all-equal links prefer the shorter path.
func DefaultWeights() Weights {
	return Weights{
		HopBase:           10,
		ReliabilityWeight: 8,
		MinProb:           0.02,
		UnknownProb:       0.5,
		FreshGraceS:       15,
		FreshScale:        0.15,
		FreshCap:          12,
		LatencyScale:      0.02,
		LatencyCap:        10,
		QueueMax:          8,
		TransportPenalty: map[core.Transport]float64{
			core.TransportBLE:      0,
			core.TransportUSB:      0,
			core.TransportTCP:      1,
			core.TransportMeshCore: 6,
			core.TransportUnknown:  2,
		},
		ConfidencePenalty: map[LinkSource]float64{
			SourceLocal:      0,
			SourceReciprocal: 0.5,
			SourceForward:    2,
			SourceReverse:    4,
			SourceLegacy:     6,
			SourceUnknown:    6,
		},
		UnknownPenalty: 4,
	}
}

// Cost is the per-hop cost breakdown, kept component-by-component so a route can
// be explained: every term shows which factor contributed how much. Prob is the
// success probability the reliability term was derived from.
type Cost struct {
	Hop         float64 `json:"hop"`
	Reliability float64 `json:"reliability"`
	Freshness   float64 `json:"freshness"`
	Latency     float64 `json:"latency"`
	Congestion  float64 `json:"congestion"`
	Transport   float64 `json:"transport"`
	Confidence  float64 `json:"confidence"`
	Unknown     float64 `json:"unknown"`
	Total       float64 `json:"total"`
	Prob        float64 `json:"prob"`
}

// linkProb estimates a hop's success probability from the best signal available:
// a normalized quality score if present, else a PHY-aware RSSI mapping, else the
// configured unknown default.
func (w Weights) linkProb(l Link) float64 {
	var p float64
	switch {
	case l.QualityQ8 > 0:
		p = float64(l.QualityQ8) / 255
	case l.RSSIEWMA != 0 || l.RSSI != 0:
		rssi := l.RSSIEWMA
		if rssi == 0 {
			rssi = l.RSSI
		}
		phy := l.RxPHY
		if phy == core.PHYUnknown {
			phy = l.TxPHY
		}
		p = rssiProb(rssi, phy)
	default:
		p = w.UnknownProb
	}
	if p < w.MinProb {
		p = w.MinProb
	}
	if p > 0.999 {
		p = 0.999
	}
	return p
}

// rssiProb maps an RSSI (dBm) to a success probability, PHY-aware: 2M needs a
// stronger signal for the same reliability, LE Coded tolerates a much weaker one.
func rssiProb(rssi int, phy core.PHY) float64 {
	good, bad := -60.0, -95.0 // 1M / unknown defaults
	switch phy {
	case core.PHY2M:
		good, bad = -55.0, -88.0
	case core.PHYCoded:
		good, bad = -70.0, -106.0
	}
	r := float64(rssi)
	switch {
	case r >= good:
		return 0.95
	case r <= bad:
		return 0.2
	default:
		return 0.2 + (r-bad)/(good-bad)*(0.95-0.2)
	}
}

func (w Weights) freshness(age uint32) float64 {
	if age <= w.FreshGraceS {
		return 0
	}
	f := float64(age-w.FreshGraceS) * w.FreshScale
	if f > w.FreshCap {
		return w.FreshCap
	}
	return f
}

func (w Weights) latency(rtt uint16) float64 {
	if rtt == 0 {
		return 0
	}
	l := float64(rtt) * w.LatencyScale
	if l > w.LatencyCap {
		return w.LatencyCap
	}
	return l
}

// edgeCost scores a single hop from its link metrics.
func (w Weights) edgeCost(l Link) Cost {
	p := w.linkProb(l)
	c := Cost{
		Hop:         w.HopBase,
		Reliability: -w.ReliabilityWeight * math.Log(p),
		Freshness:   w.freshness(l.AgeS),
		Latency:     w.latency(l.RTTms),
		Congestion:  float64(l.QueueQ8) / 255 * w.QueueMax,
		Transport:   w.TransportPenalty[l.Transport],
		Confidence:  w.ConfidencePenalty[l.Source],
		Prob:        p,
	}
	if !l.HasInfo {
		c.Unknown = w.UnknownPenalty
	}
	c.Total = c.Hop + c.Reliability + c.Freshness + c.Latency + c.Congestion + c.Transport + c.Confidence + c.Unknown
	return c
}

// Hop is one edge of a ranked route, carrying the link it traverses and that
// link's cost breakdown.
type Hop struct {
	From core.NodeID
	To   core.NodeID
	Link Link
	Cost Cost
}

// Route is one candidate path from a source to a destination, ordered from the
// first hop to the last, with the summed cost the ranker assigned it.
type Route struct {
	Hops  []Hop
	Total float64
}

// Path returns the node IDs the route traverses after the source — every relay
// in order followed by the destination — the shape a source route takes.
func (r Route) Path() []core.NodeID {
	out := make([]core.NodeID, len(r.Hops))
	for i, h := range r.Hops {
		out[i] = h.To
	}
	return out
}

// Reliability is the route's end-to-end success probability, the product of its
// hops' per-link probabilities.
func (r Route) Reliability() float64 {
	p := 1.0
	for _, h := range r.Hops {
		p *= h.Cost.Prob
	}
	return p
}

// Graph is a directed, cost-weighted view of the mesh. Build it with AddLink,
// then ask it for ranked Routes. Edge costs are computed eagerly from the
// graph's weights as links are added.
type Graph struct {
	w   Weights
	adj map[core.NodeID][]Hop
}

// New returns an empty graph that scores edges with the given weights.
func New(w Weights) *Graph {
	return &Graph{w: w, adj: make(map[core.NodeID][]Hop)}
}

// Weights reports the cost model this graph scores with.
func (g *Graph) Weights() Weights { return g.w }

// AddLink adds (or replaces) the directed hop from→to with the given metrics.
func (g *Graph) AddLink(from, to core.NodeID, l Link) {
	hop := Hop{From: from, To: to, Link: l, Cost: g.w.edgeCost(l)}
	for i, e := range g.adj[from] {
		if e.To == to {
			g.adj[from][i] = hop
			return
		}
	}
	g.adj[from] = append(g.adj[from], hop)
}

// Options bounds the search.
type Options struct {
	MaxHops   int // cap on hops in a route (relays + final hop); 0 = default
	MaxRoutes int // cap on returned routes; 0 = default
}

// Routes enumerates every simple (loop-free) path from→to within the hop limit
// and returns them ranked cheapest-first, keeping the best MaxRoutes. The caller
// gets the full per-hop cost breakdown so it can show why the top route beat the
// alternatives. The mesh graphs this runs on are small, so exhaustive
// enumeration is both affordable and maximally transparent.
func (g *Graph) Routes(from, to core.NodeID, opt Options) []Route {
	if opt.MaxHops <= 0 {
		opt.MaxHops = 6
	}
	if opt.MaxRoutes <= 0 {
		opt.MaxRoutes = 8
	}

	var routes []Route
	visited := map[core.NodeID]bool{from: true}
	var cur []Hop

	var dfs func(node core.NodeID, total float64)
	dfs = func(node core.NodeID, total float64) {
		if node == to {
			if len(cur) > 0 {
				routes = append(routes, Route{Hops: append([]Hop(nil), cur...), Total: total})
			}
			return
		}
		if len(cur) >= opt.MaxHops {
			return
		}
		for _, e := range g.adj[node] {
			if visited[e.To] {
				continue
			}
			visited[e.To] = true
			cur = append(cur, e)
			dfs(e.To, total+e.Cost.Total)
			cur = cur[:len(cur)-1]
			visited[e.To] = false
		}
	}
	dfs(from, 0)

	sortRoutes(routes)
	if len(routes) > opt.MaxRoutes {
		routes = routes[:opt.MaxRoutes]
	}
	return routes
}

// RoutesAll enumerates loop-free paths from `from` in a single traversal and
// returns, for every reachable node, its candidate routes ranked cheapest-first
// (capped to MaxRoutes each). It is the batch form of Routes: one walk answers
// "best route to every node" instead of re-walking the graph once per
// destination — the right call when ranking routes to many peers at once.
func (g *Graph) RoutesAll(from core.NodeID, opt Options) map[core.NodeID][]Route {
	if opt.MaxHops <= 0 {
		opt.MaxHops = 6
	}
	if opt.MaxRoutes <= 0 {
		opt.MaxRoutes = 8
	}

	out := make(map[core.NodeID][]Route)
	visited := map[core.NodeID]bool{from: true}
	var cur []Hop

	var dfs func(node core.NodeID, total float64)
	dfs = func(node core.NodeID, total float64) {
		if len(cur) > 0 { // the current path is a candidate route to `node`
			out[node] = append(out[node], Route{Hops: append([]Hop(nil), cur...), Total: total})
		}
		if len(cur) >= opt.MaxHops {
			return
		}
		for _, e := range g.adj[node] {
			if visited[e.To] {
				continue
			}
			visited[e.To] = true
			cur = append(cur, e)
			dfs(e.To, total+e.Cost.Total)
			cur = cur[:len(cur)-1]
			visited[e.To] = false
		}
	}
	dfs(from, 0)

	for k, rs := range out {
		sortRoutes(rs)
		out[k] = rs[:min(len(rs), opt.MaxRoutes)]
	}
	return out
}

// sortRoutes ranks routes cheapest-first in place, ties broken by fewer hops.
func sortRoutes(routes []Route) {
	sort.SliceStable(routes, func(i, j int) bool {
		if routes[i].Total != routes[j].Total {
			return routes[i].Total < routes[j].Total
		}
		return len(routes[i].Hops) < len(routes[j].Hops)
	})
}
