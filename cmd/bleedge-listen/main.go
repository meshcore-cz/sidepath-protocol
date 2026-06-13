// bleedge-listen is the Linux CLI for BLEEdge using BlueZ D-Bus.
// It requires BlueZ 5.56+, kernel 5.4+, and CAP_NET_ADMIN + CAP_NET_RAW (or sudo).
package main

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/burningtree/bleedge/core"
	"github.com/burningtree/bleedge/linux"
)

type multiString []string

func (m *multiString) String() string     { return strings.Join(*m, ",") }
func (m *multiString) Set(v string) error { *m = append(*m, v); return nil }

func main() {
	var (
		adapterFlag = flag.String("adapter", "hci0", "Bluetooth adapter name (e.g. hci0)")
		seedFlag    = flag.String("seed-hex", "", "Ed25519 identity seed (32 bytes / 64 hex chars); loaded from ~/.bleedge/seed if empty. NodeID = pubkey[:8]")
		descFlag    = flag.String("description", "", "Free-form node bio shown to peers (default: empty)")
		nameFlag    = flag.String("name", "", "Node display name (default: deterministic from public key, e.g. barrel-two-return)")
		phyFlag     = flag.String("phy", "1m", "PHY mode: 1m (default) | coded-only | coded-preferred")
		jsonFlag    = flag.Bool("json", false, "Output log lines as JSON")
		verboseFlag = flag.Bool("verbose", false, "Verbose logging")
	)
	var allowPeers multiString
	flag.Var(&allowPeers, "allow-peer", "Allowed peer NodeID (hex); repeatable; empty = allow all")
	flag.Parse()

	// Parse PHY mode
	var phyMode core.PHYMode
	switch *phyFlag {
	case "coded-only":
		phyMode = core.PHYModeCodedOnly
	case "coded-preferred":
		phyMode = core.PHYModeCodedPreferred
	case "1m", "1m-debug": // "1m-debug" accepted for backward compatibility
		phyMode = core.PHYMode1M
	default:
		fmt.Fprintf(os.Stderr, "unknown phy mode: %s\n", *phyFlag)
		os.Exit(1)
	}

	// Parse identity seed if supplied (else NewNode loads/generates ~/.bleedge/seed)
	var identity *core.Identity
	if *seedFlag != "" {
		b, err := hex.DecodeString(*seedFlag)
		if err != nil || len(b) != core.SeedSize {
			fmt.Fprintf(os.Stderr, "invalid seed-hex: must be %d hex bytes\n", core.SeedSize)
			os.Exit(1)
		}
		var seed [core.SeedSize]byte
		copy(seed[:], b)
		identity = core.IdentityFromSeed(seed)
	}

	// Parse allowlist
	var allowlist []core.NodeID
	for _, s := range allowPeers {
		id, err := core.ParseNodeID(s)
		if err != nil {
			fmt.Fprintf(os.Stderr, "invalid allow-peer %q: %v\n", s, err)
			os.Exit(1)
		}
		allowlist = append(allowlist, id)
	}

	cfg := linux.NodeConfig{
		AdapterName: *adapterFlag,
		Identity:    identity,
		Description: *descFlag,
		Name:        *nameFlag,
		PHYMode:     phyMode,
		Allowlist:   allowlist,
		Verbose:     *verboseFlag,
		JSONLog:     *jsonFlag,
	}

	if *jsonFlag {
		log.SetFlags(0)
		log.SetOutput(&jsonLogger{})
	}

	node, err := linux.NewNode(cfg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "init node: %v\n", err)
		os.Exit(1)
	}

	node.SetDeliveryHandler(func(dg core.Datagram) {
		if *jsonFlag {
			printJSON(map[string]interface{}{
				"event":    "deliver",
				"id":       dg.ID,
				"source":   dg.Source.String(),
				"protocol": dg.Protocol,
				"payload":  string(dg.Payload),
				"path":     traceStrings(dg.Path),
				"ts":       time.Now().Unix(),
			})
		} else {
			log.Printf("deliver protocol=0x%04x payload=%q path=%v",
				dg.Protocol, string(dg.Payload), traceStrings(dg.Path))
		}
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := node.Start(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "start: %v\n", err)
		os.Exit(1)
	}

	// Print capabilities
	printCapabilities(node.NodeID(), phyMode, cfg)

	// Wait for interrupt
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig

	log.Printf("shutting down...")
	node.Stop(ctx)
}

func printCapabilities(nodeID core.NodeID, phyMode core.PHYMode, cfg linux.NodeConfig) {
	log.Printf("node-id=%s phy-mode=%s", nodeID, phyMode)
	caps := core.Capabilities(core.LinuxCapabilities)
	log.Printf("capabilities: receiver=%v gateway=%v coded-phy=%v",
		caps.Has(core.CapReceiver), caps.Has(core.CapGateway), caps.Has(core.CapCodedPHY))
	if len(cfg.Allowlist) > 0 {
		var ids []string
		for _, id := range cfg.Allowlist {
			ids = append(ids, id.String())
		}
		log.Printf("allowlist: %s", strings.Join(ids, ", "))
	} else {
		log.Printf("allowlist: (all peers allowed)")
	}
}

func traceStrings(trace []core.NodeID) []string {
	out := make([]string, len(trace))
	for i, id := range trace {
		out[i] = id.String()
	}
	return out
}

func printJSON(v interface{}) {
	b, _ := json.Marshal(v)
	fmt.Println(string(b))
}

// jsonLogger wraps log output into JSON lines.
type jsonLogger struct{}

func (j *jsonLogger) Write(p []byte) (n int, err error) {
	msg := strings.TrimRight(string(p), "\n")
	b, _ := json.Marshal(map[string]interface{}{
		"ts":  time.Now().Unix(),
		"msg": msg,
	})
	fmt.Println(string(b))
	return len(p), nil
}
