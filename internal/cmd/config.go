package cmd

import (
	"fmt"
	"os"
	"os/exec"

	"github.com/meshcore-cz/sidepath-protocol/internal/config"
	"github.com/spf13/cobra"
)

// configTemplate is the starter config.toml written by 'sp config init'. It is a
// commented template (the TOML encoder cannot emit comments) covering every
// option with its default.
const configTemplate = `# Sidepath node configuration — used by 'sp daemon run'.
# CLI flags override any value set here.

# Node display name (empty = deterministic name derived from the public key).
name = ""
# Free-form bio shown to peers.
description = ""
# Verbose diagnostic logging.
verbose = false

# Bun bot: path to a script to run; empty disables the bot.
bot = ""
# Path to the Bun executable.
bun = "bun"

# Channels to join.
channels = ["Public"]

# MeshCore bridge: tap the meshcore-go backend and bridge packets into the mesh.
meshcore_bridge = false
# meshcore-go backend socket (empty = $MC_BACKEND_SOCKET or the platform default).
meshcore_socket = ""

# External networks this gateway advertises, e.g. ["CZ"] or ["CZ:869525000,250000,11,5"].
bridges = []

# NodeID allowlist (empty = allow all peers).
allow_peers = []

# ESP32-C6 BLE modem serial port; empty disables the daemon-owned modem.
# On macOS use /dev/cu.usbmodem*; Linux usually uses /dev/ttyACM*.
modem = ""
# Enable scan + connectionless relay when a modem is attached.
modem_relay = true
`

var configCmd = &cobra.Command{
	Use:   "config",
	Short: "Inspect and edit the node config (config.toml)",
	Long:  "config manages config.toml in the state directory, the options 'sp daemon run' uses when no flags override them.",
}

var configPathCmd = &cobra.Command{
	Use:   "path",
	Short: "Print the config file path",
	RunE: func(cmd *cobra.Command, args []string) error {
		fmt.Fprintln(cmd.OutOrStdout(), cfg.ConfigPath())
		return nil
	},
}

var configShowCmd = &cobra.Command{
	Use:   "show",
	Short: "Print the effective config as TOML",
	Long:  "show prints the effective config: the values loaded from config.toml with conventional defaults applied.",
	RunE: func(cmd *cobra.Command, args []string) error {
		nc, err := config.LoadNodeConfig(cfg.ConfigPath())
		if err != nil {
			return fmt.Errorf("read %s: %w", cfg.ConfigPath(), err)
		}
		data, err := nc.Defaulted().Encode()
		if err != nil {
			return err
		}
		_, err = cmd.OutOrStdout().Write(data)
		return err
	},
}

var configInitCmd = &cobra.Command{
	Use:   "init",
	Short: "Write a starter config.toml",
	RunE: func(cmd *cobra.Command, args []string) error {
		path := cfg.ConfigPath()
		force, _ := cmd.Flags().GetBool("force")
		if _, err := os.Stat(path); err == nil && !force {
			return fmt.Errorf("%s already exists (use --force to overwrite)", path)
		}
		if err := cfg.EnsureDir(); err != nil {
			return err
		}
		if err := os.WriteFile(path, []byte(configTemplate), 0o600); err != nil {
			return err
		}
		fmt.Fprintf(cmd.OutOrStdout(), "Wrote %s\n", path)
		return nil
	},
}

var configEditCmd = &cobra.Command{
	Use:   "edit",
	Short: "Open the config in $EDITOR",
	RunE: func(cmd *cobra.Command, args []string) error {
		path := cfg.ConfigPath()
		if _, err := os.Stat(path); os.IsNotExist(err) {
			if err := cfg.EnsureDir(); err != nil {
				return err
			}
			if err := os.WriteFile(path, []byte(configTemplate), 0o600); err != nil {
				return err
			}
		}
		editor := firstNonEmpty(os.Getenv("VISUAL"), os.Getenv("EDITOR"), "vi")
		ed := exec.Command(editor, path)
		ed.Stdin, ed.Stdout, ed.Stderr = os.Stdin, cmd.OutOrStdout(), cmd.ErrOrStderr()
		return ed.Run()
	},
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

func init() {
	configInitCmd.Flags().Bool("force", false, "overwrite an existing config.toml")
	configCmd.AddCommand(configPathCmd, configShowCmd, configInitCmd, configEditCmd)
	rootCmd.AddCommand(configCmd)
}
