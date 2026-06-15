// Command sp is the universal CLI for Sidepath Protocol, MeshCore bridges, and
// offline mesh transports. One binary does everything: foreground commands, the
// background daemon (sp daemon run), and Sidepath/MeshCore operations.
package main

import (
	"fmt"
	"os"

	"github.com/meshcore-cz/sidepath-protocol/internal/cmd"
)

func main() {
	if err := cmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "sp:", err)
		os.Exit(1)
	}
}
