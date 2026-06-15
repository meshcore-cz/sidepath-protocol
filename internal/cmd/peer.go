package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"text/tabwriter"

	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/spf13/cobra"
)

var peerCmd = &cobra.Command{
	Use:   "peer <node-id>",
	Short: "Show detailed info about one node and the neighbors it advertises",
	Long: `peer shows everything the local daemon knows about a single node: its latest
announce data (name, platform, description, capabilities, public key, bridged
networks), this node's live link and route to it, and the node's own neighbor
list. When that node advertised a v3 announce, each neighbor is shown with the
per-link details it reported — RSSI, PHY, direction, and age. It requires a
running daemon.`,
	Args: cobra.ExactArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("peer requires the daemon; remove --no-daemon")
		}
		detail, err := api.NewClient(cfg.SockPath()).Peer(args[0])
		if err != nil {
			return fmt.Errorf("cannot reach daemon: %w (is 'sp daemon' running?)", err)
		}

		out := cmd.OutOrStdout()
		if cfg.JSON {
			return json.NewEncoder(out).Encode(detail)
		}

		p := detail.Peer
		kv := tabwriter.NewWriter(out, 0, 2, 2, ' ', 0)
		fmt.Fprintf(kv, "NODE ID\t%s\n", p.NodeID)
		fmt.Fprintf(kv, "NAME\t%s\n", dash(p.Name))
		fmt.Fprintf(kv, "PLATFORM\t%s\n", dash(p.Platform))
		if detail.Pubkey != "" {
			fmt.Fprintf(kv, "PUBKEY\t%s\n", detail.Pubkey)
		}
		fmt.Fprintf(kv, "CONN\t%s\n", connLabel(p))
		fmt.Fprintf(kv, "RSSI\t%s\n", rssiLabel(p))
		fmt.Fprintf(kv, "PHY\t%s\n", phyLabel(p))
		fmt.Fprintf(kv, "ROUTE\t%s\n", routeLabel(p.Hops))
		fmt.Fprintf(kv, "CAPS\t%s\n", capsString(p.Relay, p.Gateway))
		if len(detail.Bridges) > 0 {
			fmt.Fprintf(kv, "BRIDGES\t%s\n", strings.Join(detail.Bridges, ", "))
		}
		fmt.Fprintf(kv, "ANNOUNCE\tepoch=%d seq=%d (%s)\n", p.AnnounceEpoch, p.AnnounceSeq, lastSeenLabel(p.LastAnnounceS))
		if p.Description != "" {
			fmt.Fprintf(kv, "DESCRIPTION\t%s\n", p.Description)
		}
		if err := kv.Flush(); err != nil {
			return err
		}

		// Forward links: the neighbors this node advertises.
		fmt.Fprintf(out, "\nNEIGHBORS (%d)\n", len(detail.NeighborList))
		if err := printNeighborTable(out, detail.NeighborList); err != nil {
			return err
		}

		// Backward links: the nodes that advertise this node as one of their neighbors.
		// DIR/RSSI/PHY here are from the advertising node's side, so they can differ from above.
		fmt.Fprintf(out, "\nNEIGHBOR OF (%d)\n", len(detail.NeighborOfList))
		return printNeighborTable(out, detail.NeighborOfList)
	},
}

// printNeighborTable renders a per-link neighbor table (forward or backward), or a
// placeholder when the list is empty.
func printNeighborTable(out io.Writer, list []api.NeighborDetail) error {
	if len(list) == 0 {
		_, err := fmt.Fprintln(out, "  none")
		return err
	}
	tw := tabwriter.NewWriter(out, 0, 2, 2, ' ', 0)
	fmt.Fprintln(tw, "NODE ID\tNAME\tRSSI\tPHY\tDIR\tLAST RECV")
	for _, n := range list {
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\n",
			n.NodeID, dash(n.Name), nbrRSSI(n), nbrPHY(n), nbrDir(n), nbrAge(n))
	}
	return tw.Flush()
}

// nbrRSSI shows the advertised RSSI when the neighbor entry carried v3 details
// and held a real sample (0 means "no sample").
func nbrRSSI(n api.NeighborDetail) string {
	if !n.HasInfo || n.RSSI >= 0 {
		return "-"
	}
	return fmt.Sprintf("%d", n.RSSI)
}

func nbrPHY(n api.NeighborDetail) string {
	if !n.HasInfo {
		return "-"
	}
	switch {
	case n.TxPHY == "" && n.RxPHY == "":
		return "-"
	case n.TxPHY == n.RxPHY:
		return n.TxPHY
	default:
		return n.TxPHY + "/" + n.RxPHY
	}
}

func nbrDir(n api.NeighborDetail) string {
	if !n.HasInfo || n.Direction == "" {
		return "-"
	}
	return n.Direction
}

func nbrAge(n api.NeighborDetail) string {
	if !n.HasInfo {
		return "-"
	}
	return lastSeenLabel(int64(n.AgeS))
}

func init() {
	rootCmd.AddCommand(peerCmd)
}
