package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/pathrank"
	"github.com/spf13/cobra"
)

var pathCmd = &cobra.Command{
	Use:   "path <node-id>",
	Short: "Rank candidate routes to a node from announce & neighbor data",
	Long: `path is an experiment in offline route selection: rather than tracing a route
over the air, it builds a weighted graph from what the local node already knows —
the mesh topology learned via signed ANNOUNCE plus each node's advertised
per-link details (RSSI, PHY, age) — and ranks the candidate routes to a
destination by a transparent cost model.

Every route is shown with its total cost and a per-hop breakdown, so you can see
exactly why one route was preferred over another (shorter, stronger signal,
fresher links, fewer unknowns). The ranking algorithm lives in its own package
(pathrank) so it can later drive real message routing.

The node ID may be given in full or as any unambiguous short prefix.

  sp path <node-id>
  sp path <node-id> --max-hops 4 --routes 5

path requires a running daemon.`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("path requires the daemon; remove --no-daemon")
		}
		topo, err := api.NewClient(cfg.SockPath()).Topology()
		if err != nil {
			return fmt.Errorf("cannot reach daemon: %w (is 'sp daemon' running?)", err)
		}

		self, err := core.ParseNodeID(topo.Self)
		if err != nil {
			return fmt.Errorf("daemon reported an invalid self id %q: %w", topo.Self, err)
		}
		destStr, err := resolveNodeID(topo, args[0])
		if err != nil {
			return err
		}
		dest, err := core.ParseNodeID(destStr)
		if err != nil {
			return err
		}
		if dest == self {
			return fmt.Errorf("destination is this node")
		}

		maxHops, _ := cmd.Flags().GetInt("max-hops")
		maxRoutes, _ := cmd.Flags().GetInt("routes")

		names := nodeNames(topo)
		g := buildGraph(topo)
		routes := g.Routes(self, dest, pathrank.Options{MaxHops: maxHops, MaxRoutes: maxRoutes})

		out := cmd.OutOrStdout()
		if cfg.JSON {
			return json.NewEncoder(out).Encode(pathJSON(self, dest, routes, names))
		}
		return printPaths(out, self, dest, routes, names)
	},
}

// edgeView is one node's advertised (or locally observed) view of a link to a
// neighbor, plus the context needed to weight it: how old the announce carrying
// it is, and whether it is the local node's own live observation.
type edgeView struct {
	det          api.NeighborDetail
	announceAgeS int64 // seconds since the announcing node's announce arrived; <0 for self/live
	isSelf       bool
}

// buildGraph turns the topology into a directed, cost-weighted pathrank graph.
// Each advertised neighbor relation becomes an edge in both directions, tagged
// with a confidence Source: the local node's own links are SourceLocal; an edge
// both ends advertise is SourceReciprocal; one only the near end advertises is
// SourceForward; one only the far end advertises (borrowed) is SourceReverse;
// and a bare v1/v2 ID-only edge is SourceLegacy. Link age is made effective by
// adding the time since its carrying announce was received.
func buildGraph(topo *api.TopologyResult) *pathrank.Graph {
	// near[a][b] is how node a described (or observed) its link to neighbor b.
	near := make(map[core.NodeID]map[core.NodeID]edgeView)
	remember := func(from, to core.NodeID, v edgeView) {
		if near[from] == nil {
			near[from] = make(map[core.NodeID]edgeView)
		}
		near[from][to] = v
	}
	for _, n := range topo.Nodes {
		from, err := core.ParseNodeID(n.NodeID)
		if err != nil {
			continue
		}
		if len(n.Links) > 0 {
			for _, l := range n.Links {
				if to, err := core.ParseNodeID(l.NodeID); err == nil {
					remember(from, to, edgeView{det: l, announceAgeS: n.LastAnnounceS, isSelf: n.Self})
				}
			}
			continue
		}
		// No per-link details (v1/v2 announce): edges are known by ID only.
		for _, nb := range n.Neighbors {
			if to, err := core.ParseNodeID(nb); err == nil {
				remember(from, to, edgeView{det: api.NeighborDetail{NodeID: nb}, announceAgeS: n.LastAnnounceS, isSelf: n.Self})
			}
		}
	}

	g := pathrank.New(pathrank.DefaultWeights())
	added := make(map[[2]core.NodeID]bool)
	addEdge := func(from, to core.NodeID) {
		if from == to || added[[2]core.NodeID{from, to}] {
			return
		}
		fwd, okF := near[from][to]
		rev, okR := near[to][from]
		var base edgeView
		switch {
		case okF:
			base = fwd
		case okR:
			base = rev // borrow the far end's view of the reverse link
		default:
			return
		}
		added[[2]core.NodeID{from, to}] = true

		link := linkFromDetail(base.det, base.announceAgeS)
		switch {
		case base.isSelf:
			link.Source = pathrank.SourceLocal
		case !base.det.HasInfo:
			link.Source = pathrank.SourceLegacy
		case okF && okR:
			link.Source = pathrank.SourceReciprocal
		case okF:
			link.Source = pathrank.SourceForward
		default:
			link.Source = pathrank.SourceReverse
		}
		g.AddLink(from, to, link)
	}
	for a, m := range near {
		for b := range m {
			addEdge(a, b)
			addEdge(b, a)
		}
	}
	return g
}

// linkFromDetail converts an API neighbor detail into a pathrank link, folding
// the time since the carrying announce arrived into an effective link age. The
// Source field is set by the caller.
func linkFromDetail(d api.NeighborDetail, announceAgeS int64) pathrank.Link {
	age := int64(d.AgeS)
	if announceAgeS > 0 { // remote announce: the edge is at least this much older now
		age += announceAgeS
	}
	return pathrank.Link{
		Transport: parseTransport(d.Transport),
		TxPHY:     parsePHY(d.TxPHY),
		RxPHY:     parsePHY(d.RxPHY),
		RSSI:      d.RSSI,
		RSSIEWMA:  d.RSSIEWMA,
		QualityQ8: d.QualityQ8,
		AgeS:      uint32(age),
		RTTms:     d.LatencyMs,
		QueueQ8:   d.QueueQ8,
		HasInfo:   d.HasInfo,
	}
}

func parsePHY(s string) core.PHY {
	switch s {
	case "1M":
		return core.PHY1M
	case "2M":
		return core.PHY2M
	case "LE Coded":
		return core.PHYCoded
	default:
		return core.PHYUnknown
	}
}

func parseTransport(s string) core.Transport {
	switch s {
	case "BLE":
		return core.TransportBLE
	case "MeshCore":
		return core.TransportMeshCore
	case "TCP":
		return core.TransportTCP
	case "USB":
		return core.TransportUSB
	default:
		return core.TransportUnknown
	}
}

// nodeNames maps each known NodeID to its display name (empty if none).
func nodeNames(topo *api.TopologyResult) map[core.NodeID]string {
	names := make(map[core.NodeID]string, len(topo.Nodes))
	for _, n := range topo.Nodes {
		if id, err := core.ParseNodeID(n.NodeID); err == nil {
			names[id] = n.Name
		}
	}
	return names
}

// nodeLabeler returns a function that renders a NodeID as "shortid name" (or
// just the short id when no name is known), used for compact route printing.
func nodeLabeler(names map[core.NodeID]string) func(core.NodeID) string {
	return func(id core.NodeID) string {
		if name := names[id]; name != "" {
			return shortID(id.String()) + " " + name
		}
		return shortID(id.String())
	}
}

// printPaths renders the ranked routes with a per-hop cost breakdown.
func printPaths(out io.Writer, self, dest core.NodeID, routes []pathrank.Route, names map[core.NodeID]string) error {
	label := nodeLabeler(names)

	fmt.Fprintf(out, "routes %s → %s — %d candidate(s)\n\n", label(self), label(dest), len(routes))
	if len(routes) == 0 {
		fmt.Fprintln(out, "no route found in the known topology")
		return nil
	}

	for i, r := range routes {
		nodeSeq := r.Path()
		parts := make([]string, len(nodeSeq))
		for j, id := range nodeSeq {
			parts[j] = label(id)
		}
		fmt.Fprintf(out, "#%d  cost %.1f  %s: %s\n", i+1, r.Total, relayHopLabel(r), strings.Join(parts, " → "))
		for _, h := range r.Hops {
			fmt.Fprintf(out, "      %s → %s   %s   cost %.1f  [%s]\n",
				label(h.From), label(h.To), linkMetrics(h.Link, h.Cost), h.Cost.Total, costBreakdown(h.Cost))
		}
		fmt.Fprintln(out)
	}

	fmt.Fprintln(out, "lower cost is better — each hop costs a base + penalties for weak signal, stale or unknown links")
	return nil
}

// printPathOverview renders a compact, one-line-per-route pathrank summary (no
// per-hop cost breakdown) — used as a section of `sp peer`. The full breakdown
// is available via `sp path`.
func printPathOverview(out io.Writer, self, dest core.NodeID, routes []pathrank.Route, names map[core.NodeID]string) {
	label := nodeLabeler(names)
	fmt.Fprintf(out, "\nPATHRANK (%s → %s) — %d candidate route(s)\n", label(self), label(dest), len(routes))
	if len(routes) == 0 {
		fmt.Fprintln(out, "  none in the known topology")
		return
	}
	for i, r := range routes {
		seq := r.Path()
		parts := make([]string, len(seq))
		for j, id := range seq {
			parts[j] = label(id)
		}
		fmt.Fprintf(out, "  #%d  cost %.1f  %s  %s\n", i+1, r.Total, relayHopLabel(r), strings.Join(parts, " → "))
	}
	fmt.Fprintf(out, "  (run 'sp path %s' for the per-hop cost breakdown)\n", shortID(dest.String()))
}

func relayHopCount(r pathrank.Route) int {
	if len(r.Hops) == 0 {
		return 0
	}
	return len(r.Hops) - 1
}

func relayHopLabel(r pathrank.Route) string {
	return fmt.Sprintf("%d hop(s)", relayHopCount(r))
}

// linkMetrics renders the raw link facts a hop's cost was derived from: the
// signal it was judged on, age, transport, confidence source, and the resulting
// success probability.
func linkMetrics(l pathrank.Link, c pathrank.Cost) string {
	var sig string
	switch {
	case l.QualityQ8 > 0:
		sig = fmt.Sprintf("q=%d/255", l.QualityQ8)
	case l.RSSIEWMA != 0:
		sig = fmt.Sprintf("rssi~%d", l.RSSIEWMA)
	case l.RSSI != 0:
		sig = fmt.Sprintf("rssi=%d", l.RSSI)
	default:
		sig = "rssi=?"
	}
	parts := []string{sig, "age=" + lastSeenLabel(int64(l.AgeS))}
	if l.Transport != core.TransportUnknown {
		parts = append(parts, l.Transport.String())
	}
	if l.RTTms > 0 {
		parts = append(parts, fmt.Sprintf("rtt=%dms", l.RTTms))
	}
	parts = append(parts, l.Source.String(), fmt.Sprintf("p=%.2f", c.Prob))
	return strings.Join(parts, " ")
}

// costBreakdown lists the non-zero components that make up a hop's cost, so the
// ranking is fully observable.
func costBreakdown(c pathrank.Cost) string {
	var parts []string
	add := func(name string, v float64) {
		if v != 0 {
			parts = append(parts, fmt.Sprintf("%s %.1f", name, v))
		}
	}
	add("hop", c.Hop)
	add("reliability", c.Reliability)
	add("freshness", c.Freshness)
	add("latency", c.Latency)
	add("congestion", c.Congestion)
	add("transport", c.Transport)
	add("confidence", c.Confidence)
	add("unknown", c.Unknown)
	return strings.Join(parts, " + ")
}

// --- JSON shapes ----------------------------------------------------------

type pathResult struct {
	Self   string      `json:"self"`
	Dest   string      `json:"dest"`
	Routes []pathRoute `json:"routes"`
}

type pathRoute struct {
	Rank        int          `json:"rank"`
	Total       float64      `json:"total_cost"`
	Reliability float64      `json:"reliability"` // end-to-end success probability
	Path        []string     `json:"path"`        // relay hops then dest (hex), as a source route
	Hops        []pathHopOut `json:"hops"`
}

type pathHopOut struct {
	From      string        `json:"from"`
	To        string        `json:"to"`
	Name      string        `json:"name,omitempty"`
	Transport string        `json:"transport,omitempty"`
	RSSI      int           `json:"rssi,omitempty"`
	RSSIEWMA  int           `json:"rssi_ewma,omitempty"`
	QualityQ8 uint8         `json:"quality_q8,omitempty"`
	RTTms     uint16        `json:"rtt_ms,omitempty"`
	AgeS      uint32        `json:"age_s,omitempty"`
	Source    string        `json:"source"`
	HasInfo   bool          `json:"has_info"`
	Cost      pathrank.Cost `json:"cost"`
}

func pathJSON(self, dest core.NodeID, routes []pathrank.Route, names map[core.NodeID]string) pathResult {
	res := pathResult{Self: self.String(), Dest: dest.String()}
	for i, r := range routes {
		pr := pathRoute{Rank: i + 1, Total: r.Total, Reliability: r.Reliability()}
		for _, id := range r.Path() {
			pr.Path = append(pr.Path, id.String())
		}
		for _, h := range r.Hops {
			out := pathHopOut{
				From:      h.From.String(),
				To:        h.To.String(),
				Name:      names[h.To],
				RSSI:      h.Link.RSSI,
				RSSIEWMA:  h.Link.RSSIEWMA,
				QualityQ8: h.Link.QualityQ8,
				RTTms:     h.Link.RTTms,
				AgeS:      h.Link.AgeS,
				Source:    h.Link.Source.String(),
				HasInfo:   h.Link.HasInfo,
				Cost:      h.Cost,
			}
			if h.Link.Transport != core.TransportUnknown {
				out.Transport = h.Link.Transport.String()
			}
			pr.Hops = append(pr.Hops, out)
		}
		res.Routes = append(res.Routes, pr)
	}
	return res
}

func init() {
	pathCmd.Flags().Int("max-hops", 6, "maximum number of hops to consider")
	pathCmd.Flags().Int("routes", 8, "maximum number of ranked routes to show")
	rootCmd.AddCommand(pathCmd)
}
