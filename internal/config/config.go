// Package config resolves the on-disk locations and runtime options shared by
// every sp subcommand and the background daemon. Keeping path resolution in one
// place means the CLI and the daemon always agree on where the identity seed,
// PID file, control socket, and log live.
package config

import (
	"os"
	"path/filepath"
)

// Config holds the resolved runtime options for an sp invocation. It is built
// once from the global flags in PersistentPreRun and passed to the commands.
type Config struct {
	// Dir is the state directory (default ~/.sidepath) holding the identity
	// seed, PID file, control socket, and daemon log.
	Dir string
	// DBPath is the path to the local datastore (placeholder for now).
	DBPath string
	// JSON requests machine-readable output where a command supports it.
	JSON bool
	// Verbose enables extra diagnostic logging.
	Verbose bool
	// NoDaemon forces commands to act without contacting the local daemon.
	NoDaemon bool
}

// Default builds a Config from the resolved state directory, applying the
// SIDEPATH_HOME environment override when no explicit directory is given.
func Default(dir string) *Config {
	return &Config{Dir: resolveDir(dir)}
}

func resolveDir(dir string) string {
	if dir != "" {
		return dir
	}
	if env := os.Getenv("SIDEPATH_HOME"); env != "" {
		return env
	}
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.Getenv("HOME")
	}
	return filepath.Join(home, ".sidepath")
}

// SeedPath is the Ed25519 identity seed file. This matches the path used by the
// existing nodes so sp and the nodes share one identity.
func (c *Config) SeedPath() string { return filepath.Join(c.Dir, "seed") }

// PIDPath is the daemon PID file.
func (c *Config) PIDPath() string { return filepath.Join(c.Dir, "daemon.pid") }

// SockPath is the daemon's Unix control socket.
func (c *Config) SockPath() string { return filepath.Join(c.Dir, "daemon.sock") }

// LogPath is the daemon log file.
func (c *Config) LogPath() string { return filepath.Join(c.Dir, "daemon.log") }

// EnsureDir creates the state directory with private permissions if needed.
func (c *Config) EnsureDir() error {
	return os.MkdirAll(c.Dir, 0o700)
}
