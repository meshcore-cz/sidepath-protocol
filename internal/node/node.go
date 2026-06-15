// Package node runs a live Sidepath node inside the sp daemon: BLE transport,
// optional Bun bot, and the optional MeshCore bridge.
//
// The runtime is platform-specific (CoreBluetooth on macOS, BlueZ on Linux),
// so Start is implemented per-GOOS behind build tags. macOS is the reference
// implementation; other platforms return ErrUnsupported until ported. The rest
// of sp (commands, daemon, control API) stays platform-agnostic and links the
// stub on unsupported platforms.
package node

import "github.com/meshcore-cz/sidepath-protocol/internal/api"

// LogFunc is the diagnostic sink the node writes to (the daemon log).
type LogFunc func(format string, args ...any)

// Runtime is the live handle to a running node, queried by the daemon to serve
// the control API (status, peers).
type Runtime interface {
	// NodeID is this node's routing ID as hex.
	NodeID() string
	// Peers is a snapshot of the currently linked peers.
	Peers() []api.Peer
}
