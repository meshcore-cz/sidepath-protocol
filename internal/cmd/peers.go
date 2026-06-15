package cmd

import (
	"encoding/json"
	"fmt"
	"strconv"
	"text/tabwriter"

	"github.com/meshcore-cz/sidepath-protocol/internal/api"
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
It requires a running daemon.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("peers requires the daemon; remove --no-daemon")
		}
		peers, err := api.NewClient(cfg.SockPath()).Peers()
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
		tw := tabwriter.NewWriter(out, 0, 2, 2, ' ', 0)
		fmt.Fprintln(tw, "NODE ID\tNAME\tPLATFORM\tCONN\tRSSI\tPHY\tROUTE\tCAPS\tNBRS\tLAST ANNOUNCE")
		for _, p := range peers {
			fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
				p.NodeID, dash(p.Name), dash(p.Platform), connLabel(p), rssiLabel(p), phyLabel(p),
				routeLabel(p.Hops), capsString(p.Relay, p.Gateway), countLabel(p.Neighbors), lastSeenLabel(p.LastAnnounceS))
		}
		return tw.Flush()
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
