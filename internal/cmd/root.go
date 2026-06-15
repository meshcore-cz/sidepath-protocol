// Package cmd defines the sp command tree.
//
// sp is one binary for everything: the foreground CLI, the background daemon
// (`sp daemon run`), and — as they land — service management, Sidepath
// operations, a MeshCore namespace, and transport/packet debug tools. Each
// command lives in its own file; this file holds the root command, the global
// flags, and the shared config the subcommands read.
package cmd

import (
	"github.com/meshcore-cz/sidepath-protocol/internal/config"
	"github.com/spf13/cobra"
)

// cfg is the resolved runtime config, built from the global flags in
// PersistentPreRun before any subcommand runs.
var cfg *config.Config

// global flag values
var (
	flagConfig   string
	flagDB       string
	flagJSON     bool
	flagVerbose  bool
	flagNoDaemon bool
)

var rootCmd = &cobra.Command{
	Use:   "sp",
	Short: "Universal CLI for Sidepath Protocol",
	Long: `sp is the universal CLI for Sidepath Protocol, MeshCore bridges, and offline mesh transports.

It is a single binary for everything: foreground commands, the background daemon
(sp daemon run), and Sidepath/MeshCore operations. Foreground commands such as
'sp status' and 'sp peers' talk to a local daemon over a Unix control socket.`,
	SilenceUsage:  true,
	SilenceErrors: true,
	PersistentPreRun: func(cmd *cobra.Command, args []string) {
		cfg = config.Default(flagConfig)
		cfg.DBPath = flagDB
		cfg.JSON = flagJSON
		cfg.Verbose = flagVerbose
		cfg.NoDaemon = flagNoDaemon
	},
}

// Execute runs the root command. main() calls this.
func Execute() error {
	return rootCmd.Execute()
}

func init() {
	pf := rootCmd.PersistentFlags()
	pf.StringVar(&flagConfig, "config", "", "state directory (default ~/.sidepath, or $SIDEPATH_HOME)")
	pf.StringVar(&flagDB, "db", "", "path to the local datastore")
	pf.BoolVar(&flagJSON, "json", false, "machine-readable JSON output where supported")
	pf.BoolVarP(&flagVerbose, "verbose", "v", false, "verbose diagnostic logging")
	pf.BoolVar(&flagNoDaemon, "no-daemon", false, "do not contact the local daemon")
}
