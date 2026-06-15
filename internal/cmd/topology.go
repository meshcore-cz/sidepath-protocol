package cmd

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/spf13/cobra"
)

var topologyCmd = &cobra.Command{
	Use:     "topology",
	Aliases: []string{"topo", "graph"},
	Short:   "Show the mesh graph (which nodes are connected to whom)",
	Long: `topology shows the mesh as the local node currently sees it: every node it knows
about (itself plus all nodes learned via signed ANNOUNCE) and, for each, the
neighbors that node advertises. The local node (●) and any node with a live link
(◉) are marked; everything else (○) is reachable only over the mesh. It requires
a running daemon.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("topology requires the daemon; remove --no-daemon")
		}
		topo, err := api.NewClient(cfg.SockPath()).Topology()
		if err != nil {
			return fmt.Errorf("cannot reach daemon: %w (is 'sp daemon' running?)", err)
		}

		out := cmd.OutOrStdout()
		if cfg.JSON {
			return json.NewEncoder(out).Encode(topo)
		}

		// Resolve a label (short id + name) for any node id we can see, falling
		// back to the short id alone for neighbors we have no entry for.
		names := make(map[string]string, len(topo.Nodes))
		for _, n := range topo.Nodes {
			names[n.NodeID] = n.Name
		}
		label := func(id string) string {
			if name := names[id]; name != "" {
				return shortID(id) + " " + name
			}
			return shortID(id)
		}

		fmt.Fprintf(out, "mesh topology — %d node(s) (self: %s)\n\n", len(topo.Nodes), label(topo.Self))

		var edges int
		for _, n := range topo.Nodes {
			fmt.Fprintf(out, "%s %s%s\n", topoMarker(n), label(n.NodeID), topoTag(n))
			if len(n.Neighbors) == 0 {
				fmt.Fprintln(out, "    → (none)")
				continue
			}
			edges += len(n.Neighbors)
			labels := make([]string, 0, len(n.Neighbors))
			for _, nb := range n.Neighbors {
				labels = append(labels, label(nb))
			}
			sort.Strings(labels)
			fmt.Fprintf(out, "    → %s\n", strings.Join(labels, ", "))
		}

		fmt.Fprintf(out, "\nlegend: ● this node  ◉ live link  ○ via mesh   (%d directed link(s))\n", edges)
		return nil
	},
}

// topoMarker picks the glyph for a node by its role in the graph.
func topoMarker(n api.TopologyNode) string {
	switch {
	case n.Self:
		return "●"
	case n.Connected:
		return "◉"
	default:
		return "○"
	}
}

// topoTag annotates a node line with its role and announce age.
func topoTag(n api.TopologyNode) string {
	var parts []string
	if n.Self {
		parts = append(parts, "this node")
	} else if n.Connected {
		parts = append(parts, "connected")
	}
	if n.LastAnnounceS >= 0 {
		parts = append(parts, "announce "+lastSeenLabel(n.LastAnnounceS))
	}
	if len(parts) == 0 {
		return ""
	}
	return "  (" + strings.Join(parts, ", ") + ")"
}

// shortID abbreviates a hex NodeID to its first 10 characters for compact graphs.
func shortID(id string) string {
	if len(id) > 10 {
		return id[:10]
	}
	return id
}

func init() {
	rootCmd.AddCommand(topologyCmd)
}
