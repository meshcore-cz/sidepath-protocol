package config

import (
	"bytes"
	"os"
	"path/filepath"

	"github.com/BurntSushi/toml"
)

// NodeConfig holds the runtime options for the node the daemon runs. It can be
// stored as config.toml in the state directory and/or supplied via CLI flags;
// flags take precedence over the file.
type NodeConfig struct {
	Name           string   `toml:"name"`
	Description    string   `toml:"description"`
	Verbose        bool     `toml:"verbose"`
	Bot            string   `toml:"bot"`             // path to a Bun bot script; empty disables the bot
	Bun            string   `toml:"bun"`             // Bun executable (default "bun")
	Channels       []string `toml:"channels"`        // channels to join (default ["Public"])
	MeshcoreBridge bool     `toml:"meshcore_bridge"` // tap the meshcore-go backend and bridge packets
	MeshcoreSocket string   `toml:"meshcore_socket"` // meshcore-go backend socket (empty = default)
	Bridges        []string `toml:"bridges"`         // external networks this gateway advertises, e.g. ["CZ"]
	AllowPeers     []string `toml:"allow_peers"`     // NodeID allowlist (empty = allow all)
	Modem          string   `toml:"modem"`           // serial port for an attached ESP32-C6 BLE modem
	ModemRelay     bool     `toml:"modem_relay"`     // enable scan + connectionless relay on the attached modem
}

// ConfigPath is the node config file in the state directory.
func (c *Config) ConfigPath() string { return filepath.Join(c.Dir, "config.toml") }

// LoadNodeConfig reads config.toml from path. A missing file is not an error:
// callers get the conventional defaults so CLI flags can be layered on top.
func LoadNodeConfig(path string) (NodeConfig, error) {
	nc := defaultNodeConfig()
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nc, nil
	}
	if err != nil {
		return nc, err
	}
	if err := toml.Unmarshal(data, &nc); err != nil {
		return nc, err
	}
	return nc, nil
}

// Encode renders the config as TOML.
func (nc NodeConfig) Encode() ([]byte, error) {
	var buf bytes.Buffer
	if err := toml.NewEncoder(&buf).Encode(nc); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// Save writes the config as TOML to path (0600), creating the directory.
func (nc NodeConfig) Save(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := nc.Encode()
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

// Defaulted returns a copy with the conventional defaults applied.
func (nc NodeConfig) Defaulted() NodeConfig {
	defaults := defaultNodeConfig()
	if nc.Bun == "" {
		nc.Bun = defaults.Bun
	}
	if len(nc.Channels) == 0 {
		nc.Channels = defaults.Channels
	}
	return nc
}

func defaultNodeConfig() NodeConfig {
	return NodeConfig{
		Bun:        "bun",
		Channels:   []string{"Public"},
		ModemRelay: true,
	}
}
