package cmd

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/spf13/cobra"
)

var initCmd = &cobra.Command{
	Use:   "init",
	Short: "Create or load this node's identity",
	Long: `init loads the node's Ed25519 identity from the state directory, creating and
persisting a fresh one on first run. The 10-byte NodeID is derived from the
public key and is how the node is addressed on the mesh.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		if err := cfg.EnsureDir(); err != nil {
			return err
		}
		_, existed := statErr(cfg.SeedPath())
		id, err := core.LoadOrCreateIdentity(cfg.SeedPath())
		if err != nil {
			return err
		}
		created := !existed

		if cfg.JSON {
			return json.NewEncoder(cmd.OutOrStdout()).Encode(map[string]any{
				"created": created,
				"node_id": id.NodeID().String(),
				"pubkey":  hex.EncodeToString(id.Pub),
				"seed":    cfg.SeedPath(),
				"dir":     cfg.Dir,
			})
		}

		out := cmd.OutOrStdout()
		if created {
			fmt.Fprintln(out, "Created new identity.")
		} else {
			fmt.Fprintln(out, "Loaded existing identity.")
		}
		fmt.Fprintf(out, "  NodeID: %s\n", id.NodeID())
		fmt.Fprintf(out, "  Pubkey: %s\n", hex.EncodeToString(id.Pub))
		fmt.Fprintf(out, "  Seed:   %s\n", cfg.SeedPath())
		return nil
	},
}

// statErr reports whether a path exists; the error is returned for callers that
// want to distinguish "missing" from "exists".
func statErr(path string) (error, bool) {
	_, err := os.Stat(path)
	return err, err == nil
}

func init() {
	rootCmd.AddCommand(initCmd)
}
