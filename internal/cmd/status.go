package cmd

import (
	"encoding/hex"
	"encoding/json"
	"fmt"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/spf13/cobra"
)

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show node and daemon status",
	Long: `status reports whether the local daemon is running and, if so, its PID, uptime,
NodeID and peer count. When the daemon is stopped (or --no-daemon is set) it
falls back to the local identity so the NodeID is always available.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		out := cmd.OutOrStdout()

		if !cfg.NoDaemon {
			if s, err := api.NewClient(cfg.SockPath()).Status(); err == nil {
				if cfg.JSON {
					return json.NewEncoder(out).Encode(map[string]any{"running": true, "status": s})
				}
				fmt.Fprintln(out, "daemon:  running")
				fmt.Fprintf(out, "  PID:     %d\n", s.PID)
				fmt.Fprintf(out, "  NodeID:  %s\n", s.NodeID)
				fmt.Fprintf(out, "  Uptime:  %s\n", s.Uptime())
				fmt.Fprintf(out, "  Peers:   %d\n", s.PeerCount)
				fmt.Fprintf(out, "  Version: %s\n", s.Version)
				return nil
			}
		}

		// Daemon not reachable: report offline status from the local identity.
		nodeID, pubkey := "(uninitialized)", ""
		if id, err := core.LoadOrCreateIdentity(cfg.SeedPath()); err == nil {
			nodeID = id.NodeID().String()
			pubkey = hex.EncodeToString(id.Pub)
		}
		if cfg.JSON {
			return json.NewEncoder(out).Encode(map[string]any{
				"running": false, "node_id": nodeID, "pubkey": pubkey,
			})
		}
		fmt.Fprintln(out, "daemon:  stopped")
		fmt.Fprintf(out, "  NodeID:  %s\n", nodeID)
		fmt.Fprintln(out, "  (run 'sp daemon start' to bring the node online)")
		return nil
	},
}

func init() {
	rootCmd.AddCommand(statusCmd)
}
