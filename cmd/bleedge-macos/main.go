//go:build darwin

// bleedge-macos is an interactive BLEEdge node for macOS (1M PHY).
// CoreBluetooth does not support LE Coded PHY, so this node operates in
// 1m mode and is NOT valid for the Long Range demonstration.
// Use bleedge-listen on Linux for the full Coded PHY PoC.
package main

import (
	"bufio"
	"context"
	"encoding/hex"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/bleedge/bleedge/core"
	blenode "github.com/bleedge/bleedge/macos"
)

func main() {
	// Parse a minimal set of flags manually so we don't depend on flag pkg order
	seedHex := ""
	allowPeers := ""
	description := ""
	botScript := ""
	bunPath := "bun"
	verbose := false
	for i, arg := range os.Args[1:] {
		switch {
		case arg == "--verbose" || arg == "-v":
			verbose = true
		case strings.HasPrefix(arg, "--seed-hex="):
			seedHex = strings.TrimPrefix(arg, "--seed-hex=")
		case arg == "--seed-hex" && i+1 < len(os.Args[1:]):
			seedHex = os.Args[i+2]
		case strings.HasPrefix(arg, "--description="):
			description = strings.TrimPrefix(arg, "--description=")
		case arg == "--description" && i+1 < len(os.Args[1:]):
			description = os.Args[i+2]
		case strings.HasPrefix(arg, "--allow-peer="):
			allowPeers = strings.TrimPrefix(arg, "--allow-peer=")
		case arg == "--allow-peer" && i+1 < len(os.Args[1:]):
			allowPeers = os.Args[i+2]
		case strings.HasPrefix(arg, "--bot="):
			botScript = strings.TrimPrefix(arg, "--bot=")
		case arg == "--bot" && i+1 < len(os.Args[1:]):
			botScript = os.Args[i+2]
		case strings.HasPrefix(arg, "--bun="):
			bunPath = strings.TrimPrefix(arg, "--bun=")
		case arg == "--bun" && i+1 < len(os.Args[1:]):
			bunPath = os.Args[i+2]
		case arg == "--help" || arg == "-h":
			fmt.Fprintf(os.Stderr, `bleedge-macos — interactive BLEEdge node for macOS (1M PHY)

Usage:
  bleedge-macos [--seed-hex <hex>] [--description <str>] [--allow-peer <id,...>] [--verbose]
  bleedge-macos --bot <script.ts> [--bun <path>]   # run as a bot driven by a Bun script

Interactive commands (type and press Enter):
  <text>             broadcast a message on the public channel
  /dm <id> <text>    send an encrypted direct message to a node
  /peers             list connected peers
  /neighbors         list neighbor table
  /topology          list known topology
  /quit              exit

Bot mode (--bot): the node runs headless and forwards chat messages to a Bun
  JS/TS script over stdin/stdout JSON. The script can reply (encrypted DM),
  broadcast on the channel, and query mesh stats. See bots/ for examples:
    bleedge-macos --bot bots/time-bot.ts

NOTE: macOS CoreBluetooth does NOT support LE Coded PHY.
      Use bleedge-listen on Linux for the Long Range PoC.
`)
			os.Exit(0)
		}
	}

	// Load or generate the Ed25519 identity (NodeID = pubkey[:8])
	var identity *core.Identity
	if seedHex != "" {
		b, err := hex.DecodeString(seedHex)
		if err != nil || len(b) != core.SeedSize {
			fatalf("invalid --seed-hex: must be %d hex bytes", core.SeedSize)
		}
		var seed [core.SeedSize]byte
		copy(seed[:], b)
		identity = core.IdentityFromSeed(seed)
	} else {
		var err error
		identity, err = blenode.LoadOrCreateIdentity()
		if err != nil {
			fatalf("cannot load identity: %v", err)
		}
	}

	// Parse allowlist
	var allowlist []core.NodeID
	if allowPeers != "" {
		for _, s := range strings.Split(allowPeers, ",") {
			s = strings.TrimSpace(s)
			if s == "" {
				continue
			}
			id, err := core.ParseNodeID(s)
			if err != nil {
				fatalf("invalid --allow-peer %q: %v", s, err)
			}
			allowlist = append(allowlist, id)
		}
	}

	// logFn writes to stderr so it doesn't pollute the interactive stdout
	logFn := func(msg string) {
		if verbose {
			fmt.Fprintf(os.Stderr, "%s  %s\n", time.Now().Format("15:04:05"), msg)
		}
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	// In bot mode, the bridge (set below) consumes incoming messages; otherwise we
	// print them interactively, decrypting encrypted DMs with our identity.
	var theBot *bot
	node := blenode.New(blenode.Config{
		Identity:    identity,
		Description: description,
		Caps:        core.Capabilities(uint8(core.CapSender) | uint8(core.CapReceiver) | uint8(core.CapRelay)),
		Allowlist: allowlist,
		Verbose:   verbose,
		LogFn:     logFn,
		OnMessage: func(from core.NodeID, ptype core.PayloadType, payload []byte) {
			if theBot != nil {
				theBot.onIncoming(from, ptype, payload)
				return
			}
			ts := time.Now().Format("15:04:05")
			fmt.Printf("\n[%s] MSG from %s: %s\n> ", ts, from, renderIncoming(ptype, payload, identity))
		},
		OnPeerConnect: func(id core.NodeID) {
			ts := time.Now().Format("15:04:05")
			fmt.Printf("\n[%s] +++ connected: %s\n> ", ts, id)
		},
		OnPeerDisconnect: func(id core.NodeID) {
			ts := time.Now().Format("15:04:05")
			fmt.Printf("\n[%s] --- disconnected: %s\n> ", ts, id)
		},
	})

	// Run node in background
	errCh := make(chan error, 1)
	go func() { errCh <- node.Run(ctx) }()

	// Bot mode: start the Bun bridge and run headless until interrupted.
	if botScript != "" {
		b, err := startBot(ctx, node, bunPath, botScript)
		if err != nil {
			fatalf("cannot start bot: %v", err)
		}
		theBot = b
		fmt.Fprintf(os.Stderr, "BLEEdge macOS bot  node=%s  script=%s\n", identity.NodeID(), botScript)
		select {
		case err := <-errCh:
			if err != nil {
				fmt.Fprintf(os.Stderr, "node error: %v\n", err)
			}
		case <-ctx.Done():
		}
		return
	}

	// Banner
	fmt.Printf("BLEEdge macOS  node=%s  phy=1m\n", identity.NodeID())
	fmt.Println("Advertising and scanning... type a message and press Enter to broadcast.")
	fmt.Println("Commands: /dm <id> <text>  /peers  /neighbors  /topology  /quit")
	fmt.Println()

	// Interactive loop
	scanner := bufio.NewScanner(os.Stdin)
	for {
		fmt.Print("> ")

		// Check if node exited
		select {
		case err := <-errCh:
			if err != nil {
				fmt.Fprintf(os.Stderr, "node error: %v\n", err)
			}
			return
		default:
		}

		if !scanner.Scan() {
			cancel()
			break
		}

		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		switch line {
		case "/quit", "/q", "/exit":
			cancel()
			<-errCh
			return

		case "/peers", "/p":
			peers := node.ConnectedPeers()
			if len(peers) == 0 {
				fmt.Println("  (no peers connected)")
			} else {
				for _, p := range peers {
					fmt.Printf("  peer: %s  %s\n", p, descLabel(node.DescriptionFor(p)))
				}
			}

		case "/neighbors", "/n":
			nbs := node.Neighbors()
			if len(nbs) == 0 {
				fmt.Println("  (no neighbors)")
			} else {
				for _, nb := range nbs {
					fmt.Printf("  neighbor: %s  %s  rssi=%d  tx=%s  rx=%s\n",
						nb.ID, descLabel(node.DescriptionFor(nb.ID)), nb.RSSI, nb.TxPHY, nb.RxPHY)
				}
			}

		case "/topology", "/t":
			nodes := node.Topology()
			if len(nodes) == 0 {
				fmt.Println("  (no topology data)")
			} else {
				for _, tn := range nodes {
					nbs := make([]string, len(tn.Neighbors))
					for i, id := range tn.Neighbors {
						s := id.String()
						if len(s) > 8 {
							s = s[:8] + "…"
						}
						nbs[i] = s
					}
					fmt.Printf("  node: %s  %s  caps=%s  last-announce=%s  neighbors=[%s]\n",
						tn.ID, descLabel(tn.Description), tn.Caps, relativeTime(tn.LastSeen), strings.Join(nbs, " "))
				}
			}

		default:
			if strings.HasPrefix(line, "/dm ") {
				rest := strings.TrimSpace(strings.TrimPrefix(line, "/dm "))
				parts := strings.SplitN(rest, " ", 2)
				if len(parts) < 2 {
					fmt.Println("  usage: /dm <nodeid> <message>")
					continue
				}
				id, err := core.ParseNodeID(parts[0])
				if err != nil {
					fmt.Printf("  bad node id: %v\n", err)
					continue
				}
				if err := node.SendChat(id, parts[1]); err != nil {
					fmt.Printf("  dm error: %v\n", err)
				} else {
					fmt.Printf("  dm sent to %s\n", id)
				}
				continue
			}
			if strings.HasPrefix(line, "/") {
				fmt.Println("  unknown command. try /dm /peers /neighbors /topology /quit")
				continue
			}
			if err := node.SendChannel(line); err != nil {
				fmt.Printf("  send error: %v\n", err)
			} else {
				fmt.Printf("  sent to channel: %s\n", line)
			}
		}
	}

	<-ctx.Done()
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "bleedge-macos: "+format+"\n", args...)
	os.Exit(1)
}

// renderIncoming turns a received payload into displayable text, decrypting
// encrypted DMs with our identity.
func renderIncoming(ptype core.PayloadType, payload []byte, id *core.Identity) string {
	switch ptype {
	case core.PayloadTypeChatEncrypted:
		if text, ok := core.OpenChat(payload, id); ok {
			return text
		}
		return "[encrypted — not for me]"
	default:
		return string(payload)
	}
}

// descLabel renders a node description as "[desc]", or "[?]" when unknown.
func descLabel(desc string) string {
	if desc == "" {
		return "[?]"
	}
	return "[" + desc + "]"
}

// relativeTime formats a timestamp as a short "Ns ago" string.
func relativeTime(t time.Time) string {
	if t.IsZero() {
		return "never"
	}
	d := time.Since(t)
	switch {
	case d < time.Second:
		return "just now"
	case d < time.Minute:
		return fmt.Sprintf("%ds ago", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%dm%ds ago", int(d.Minutes()), int(d.Seconds())%60)
	default:
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	}
}
