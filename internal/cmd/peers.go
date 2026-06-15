package cmd

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"text/tabwriter"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/pathrank"
	"github.com/spf13/cobra"
)

var peersCmd = &cobra.Command{
	Use:     "peers",
	Aliases: []string{"nodes"},
	Short:   "List known nodes (connected ones marked)",
	Long: `peers lists every node the local daemon knows about — those learned via signed
ANNOUNCE plus any directly-linked peer — with each node's latest announce data
(name, platform, capabilities), this node's route to it, and whether a BLE link
is currently up. Connected nodes are marked in the CONN column and sorted first.
For each live link, SIGNAL shows RSSI and PHY, QUAL/RTT the observed delivery
reliability and smoothed round-trip latency (once direct traffic has sampled
them), RX/TX the Sidepath packets received from and sent to the peer over the
life of the link, and LAST RX how long ago the last one arrived.

The ROUTE column shows the node's selected route ('direct' or 'Nh'); for a node
with no selected route it falls back to pathrank, reporting how many candidate
routes it found through the known topology and the best one's hop count and cost
('Np Hh cC') instead of 'no-route'. It requires a running daemon.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("peers requires the daemon; remove --no-daemon")
		}
		client := api.NewClient(cfg.SockPath())
		peers, err := client.Peers()
		if err != nil {
			return fmt.Errorf("cannot reach daemon: %w (is 'sp daemon' running?)", err)
		}

		out := cmd.OutOrStdout()
		if cfg.JSON {
			return json.NewEncoder(out).Encode(peers)
		}
		if len(peers) == 0 {
			fmt.Fprintln(out, "no nodes known yet")
			return nil
		}

		// For peers the daemon has no selected route to, fall back to pathrank: it
		// can still find candidate routes through the topology. The whole graph is
		// walked once (RoutesAll) to rank routes to every node, instead of a
		// separate enumeration per peer.
		var routesByDest map[core.NodeID][]pathrank.Route
		if topo, terr := client.Topology(); terr == nil {
			if self, perr := core.ParseNodeID(topo.Self); perr == nil {
				routesByDest = buildGraph(topo).RoutesAll(self, pathrank.Options{})
			}
		}

		usedPathrank := false
		tw := tabwriter.NewWriter(out, 0, 2, 2, ' ', 0)
		fmt.Fprintln(tw, "NODE ID\tNAME\tPLATFORM\tCONN\tSIGNAL\tQUAL/RTT\tRX\tTX\tLAST RX\tROUTE\tCAPS\tNBRS\tLAST ANNOUNCE")
		for _, p := range peers {
			route, viaPathrank := peerRoute(p, routesByDest)
			usedPathrank = usedPathrank || viaPathrank
			fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
				p.NodeID, dash(p.Name), dash(p.Platform), connLabel(p), signalLabel(p), qualRttLabel(p),
				pktCount(p, p.RxPackets), pktCount(p, p.TxPackets), lastRxLabel(p),
				route, capsString(p.Relay, p.Gateway), countLabel(p.Neighbors), lastSeenLabel(p.LastAnnounceS))
		}
		if err := tw.Flush(); err != nil {
			return err
		}
		if usedPathrank {
			fmt.Fprintln(out, "\nROUTE: 'direct'/'Nh' = node's selected route; 'Np Hh cC' = pathrank found N candidate(s), best is H hop(s) at cost C")
		}
		return nil
	},
}

// connLabel marks connected nodes with their link direction, or "-" when the
// node is known only from the topology (no live link).
func connLabel(p api.Peer) string {
	if p.Connected {
		if p.Direction != "" {
			return p.Direction
		}
		return "yes"
	}
	return "-"
}

// rssiLabel shows the signal strength when a real sample exists. RSSI is in dBm
// and always negative in practice, so 0 (or positive) means "no sample".
func rssiLabel(p api.Peer) string {
	if p.RSSI < 0 {
		return strconv.Itoa(p.RSSI)
	}
	return "-"
}

// phyLabel renders the link PHY, collapsing symmetric tx/rx to one value.
func phyLabel(p api.Peer) string {
	switch {
	case p.TxPHY == "" && p.RxPHY == "":
		return "-"
	case p.TxPHY == p.RxPHY:
		return p.TxPHY
	default:
		return p.TxPHY + "/" + p.RxPHY
	}
}

// signalLabel combines RSSI and PHY into one column ("-81 1M"), since both only
// apply to a live link; "-" when nothing is sampled.
func signalLabel(p api.Peer) string {
	rssi, phy := rssiLabel(p), phyLabel(p)
	switch {
	case rssi == "-" && phy == "-":
		return "-"
	case phy == "-":
		return rssi
	case rssi == "-":
		return phy
	default:
		return rssi + " " + phy
	}
}

// qualRttLabel surfaces the live link quality this node has learned for a
// connected peer: the delivery-reliability score (as a percentage) and smoothed
// round-trip latency. Either is shown only once sampled (0 = unknown), so a
// freshly-connected peer with no direct traffic reads "-".
func qualRttLabel(p api.Peer) string {
	if !p.Connected {
		return "-"
	}
	var parts []string
	if p.Quality > 0 {
		parts = append(parts, strconv.Itoa((int(p.Quality)*100+127)/255)+"%")
	}
	if p.RTTms > 0 {
		parts = append(parts, strconv.Itoa(int(p.RTTms))+"ms")
	}
	if len(parts) == 0 {
		return "-"
	}
	return strings.Join(parts, " ")
}

func routeLabel(hops int) string {
	switch {
	case hops < 0:
		return "no-route"
	case hops == 0:
		return "direct"
	default:
		return strconv.Itoa(hops) + "h"
	}
}

// peerRoute renders the ROUTE column. A node with a daemon-selected route shows
// it as before (direct/Nh). For a node with no selected route, it looks up the
// pathrank candidates precomputed for every destination and, when any exist,
// reports their count and the cheapest route's hop count and cost instead of
// "no-route". The bool says whether the pathrank form was used (for the legend).
func peerRoute(p api.Peer, routesByDest map[core.NodeID][]pathrank.Route) (string, bool) {
	if p.Hops >= 0 {
		return routeLabel(p.Hops), false
	}
	dst, err := core.ParseNodeID(p.NodeID)
	if err != nil {
		return "no-route", false
	}
	routes := routesByDest[dst]
	if len(routes) == 0 {
		return "no-route", false
	}
	best := routes[0]
	return fmt.Sprintf("%dp %dh c%.1f", len(routes), len(best.Hops), best.Total), true
}

// capsString renders the relay/gateway capability flags compactly.
func capsString(relay, gateway bool) string {
	switch {
	case relay && gateway:
		return "relay,gw"
	case relay:
		return "relay"
	case gateway:
		return "gw"
	default:
		return "-"
	}
}

// pktCount shows a lifetime Sidepath-packet count for a connected peer; "-" for
// a node with no live link (these counters are link-only).
func pktCount(p api.Peer, n uint64) string {
	if !p.Connected {
		return "-"
	}
	return strconv.FormatUint(n, 10)
}

// lastRxLabel shows how long ago the last packet was received from a connected
// peer; "-" when not connected or nothing has been received yet.
func lastRxLabel(p api.Peer) string {
	if !p.Connected || p.LastRxS < 0 {
		return "-"
	}
	return lastSeenLabel(p.LastRxS)
}

func lastSeenLabel(s int64) string {
	switch {
	case s < 0:
		return "never"
	case s < 60:
		return strconv.FormatInt(s, 10) + "s"
	case s < 3600:
		return strconv.FormatInt(s/60, 10) + "m"
	default:
		return strconv.FormatInt(s/3600, 10) + "h"
	}
}

func dash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

func countLabel(n int) string {
	if n == 0 {
		return "-"
	}
	return strconv.Itoa(n)
}

func init() {
	rootCmd.AddCommand(peersCmd)
}
