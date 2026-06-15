//go:build !darwin

package node

import (
	"context"
	"fmt"
	"runtime"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/config"
)

// Start is not yet implemented off macOS. macOS is the reference platform; the
// Linux (BlueZ) runtime is the next port. It returns a clear error so the
// daemon fails fast with an actionable message rather than silently idling.
func Start(ctx context.Context, id *core.Identity, cfg *config.Config, nc config.NodeConfig, logf LogFunc) (Runtime, <-chan error, error) {
	return nil, nil, fmt.Errorf("the node runtime is not yet supported on %s (only darwin); build/run sp on macOS", runtime.GOOS)
}
