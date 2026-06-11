//go:build darwin

// bleedge-macos is an interactive BLEEdge node for macOS (1M PHY).
// CoreBluetooth does not support LE Coded PHY, so this node operates in
// 1m mode and is NOT valid for the Long Range demonstration.
// Use bleedge-listen on Linux for the full Coded PHY PoC.
package main

import (
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
	botChannels := "Public"
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
		case strings.HasPrefix(arg, "--channels="):
			botChannels = strings.TrimPrefix(arg, "--channels=")
		case arg == "--channels" && i+1 < len(os.Args[1:]):
			botChannels = os.Args[i+2]
		case arg == "--help" || arg == "-h":
			fmt.Fprintf(os.Stderr, `bleedge-macos — interactive BLEEdge node for macOS (1M PHY)

Usage:
  bleedge-macos [--seed-hex <hex>] [--description <str>] [--allow-peer <id,...>] [--verbose]
  bleedge-macos --bot <script.ts> [--bun <path>] [--channels <name,...>]   # run as a bot driven by a Bun script

Interactive commands (type and press Enter):
  <text>             broadcast a message on the public channel
  /dm <id> <text>    send an encrypted direct message to a node
  /peers             list connected peers
  /neighbors         list neighbor table
  /topology          list known topology
  /quit              exit

Bot mode (--bot): the node runs headless and forwards chat messages to a Bun
  JS/TS script over stdin/stdout JSON. The script can reply (encrypted DM),
  broadcast on a channel, and query mesh stats. By default the bot listens on
  the Public channel; pass --channels to join one or more (comma-separated,
  e.g. --channels "Public,rock climbers"). See bots/ for examples:
    bleedge-macos --bot bots/time-bot.ts
    bleedge-macos --bot bots/echo-bot.ts --channels "Public,dev"

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

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	// In bot mode, the bridge (set below) consumes incoming messages; otherwise we
	// print them interactively, decrypting encrypted DMs with our identity.
	var theBot *bot
	var node *blenode.Node
	uiEvents := make(chan uiEvent, 128)
	emitUI := func(line string) {
		select {
		case uiEvents <- uiEvent{line: line}:
		default:
		}
	}
	emitLog := func(line string) {
		select {
		case uiEvents <- uiEvent{line: line, kind: uiEventLog}:
		default:
		}
	}
	logFn := func(msg string) {
		if !verbose {
			return
		}
		line := fmt.Sprintf("%s  %s", time.Now().Format("15:04:05"), msg)
		if botScript != "" {
			fmt.Fprintln(os.Stderr, line)
			return
		}
		emitLog(line)
	}
	node = blenode.New(blenode.Config{
		Identity:    identity,
		Description: description,
		Caps:        core.Capabilities(uint8(core.CapSender) | uint8(core.CapReceiver) | uint8(core.CapRelay)),
		Allowlist:   allowlist,
		Verbose:     verbose,
		LogFn:       logFn,
		OnMessage: func(from core.NodeID, ptype core.PayloadType, payload []byte) {
			if theBot != nil {
				theBot.onIncoming(from, ptype, payload)
				return
			}
			ts := time.Now().Format("15:04:05")
			if ptype == core.PayloadTypeChannel {
				if in, ok := node.DecodeChannel(payload); ok {
					emitUI(fmt.Sprintf("[%s] #%s %s: %s", ts, in.Channel, in.Sender, in.Text))
				}
				return
			}
			emitUI(fmt.Sprintf("[%s] MSG from %s: %s", ts, from, renderIncoming(ptype, payload, identity)))
		},
		OnPeerConnect: func(id core.NodeID) {
			ts := time.Now().Format("15:04:05")
			emitUI(fmt.Sprintf("[%s] +++ connected: %s", ts, id))
		},
		OnPeerDisconnect: func(id core.NodeID) {
			ts := time.Now().Format("15:04:05")
			emitUI(fmt.Sprintf("[%s] --- disconnected: %s", ts, id))
		},
	})

	// Run node in background
	errCh := make(chan error, 1)
	go func() { errCh <- node.Run(ctx) }()

	// Bot mode: start the Bun bridge and run headless until interrupted.
	if botScript != "" {
		channels := parseChannelList(botChannels)
		b, err := startBot(ctx, node, bunPath, botScript, channels)
		if err != nil {
			fatalf("cannot start bot: %v", err)
		}
		theBot = b
		fmt.Fprintf(os.Stderr, "BLEEdge macOS bot  node=%s  script=%s  channels=%v\n",
			identity.NodeID(), botScript, channels)
		select {
		case err := <-errCh:
			if err != nil {
				fmt.Fprintf(os.Stderr, "node error: %v\n", err)
			}
		case <-ctx.Done():
		}
		return
	}

	// Plain lines are sent to the current channel; defaults to MeshCore's Public channel.
	current := node.JoinPublicChannel()
	if err := runTUI(ctx, cancel, errCh, uiEvents, node, identity, current); err != nil {
		fmt.Fprintf(os.Stderr, "tui error: %v\n", err)
		cancel()
	}
}

// parseChannelList splits a comma-separated channel list, trimming blanks and
// falling back to the Public channel when nothing usable is given.
func parseChannelList(s string) []string {
	var names []string
	for _, part := range strings.Split(s, ",") {
		if name := strings.TrimSpace(part); name != "" {
			names = append(names, name)
		}
	}
	if len(names) == 0 {
		names = []string{"Public"}
	}
	return names
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
	case core.PayloadTypeTraceResponse:
		result, err := core.DecodeTraceResult(payload)
		if err != nil {
			return fmt.Sprintf("[bad trace result: %v]", err)
		}
		return renderTraceResult(result)
	default:
		return string(payload)
	}
}

func parseTraceCommand(s string) (core.NodeID, []core.NodeID, error) {
	fields := strings.Fields(s)
	if len(fields) == 0 {
		return core.NodeID{}, nil, fmt.Errorf("missing destination")
	}
	dst, err := core.ParseNodeID(fields[0])
	if err != nil {
		return core.NodeID{}, nil, err
	}
	if len(fields) == 1 {
		return dst, nil, nil
	}
	if fields[1] != "via" {
		return core.NodeID{}, nil, fmt.Errorf("expected via")
	}
	route := make([]core.NodeID, 0, len(fields)-1)
	for _, f := range fields[2:] {
		hop, err := core.ParseNodeID(f)
		if err != nil {
			return core.NodeID{}, nil, err
		}
		route = append(route, hop)
	}
	if len(route) == 0 || route[len(route)-1] != dst {
		route = append(route, dst)
	}
	return dst, route, nil
}

func formatRoute(route []core.NodeID) string {
	parts := make([]string, len(route))
	for i, id := range route {
		parts[i] = id.String()
	}
	return strings.Join(parts, " -> ")
}

func renderTraceResult(r core.TraceResult) string {
	metric := r.Metric
	if metric == "" {
		metric = core.TraceMetricUnknown
	}
	return fmt.Sprintf("TRACE tag=%08x metric=%s forward=[%s] return=[%s]",
		r.Tag, metric, formatTraceSamples(r.ForwardNodes, r.ForwardSamples), formatTraceSamples(r.ReturnNodes, r.ReturnSamples))
}

func formatTraceSamples(nodes []core.NodeID, samples []int8) string {
	n := len(nodes)
	if len(samples) > n {
		n = len(samples)
	}
	parts := make([]string, 0, n)
	for i := 0; i < n; i++ {
		node := "?"
		if i < len(nodes) {
			node = idShort(nodes[i])
		}
		if i < len(samples) {
			parts = append(parts, fmt.Sprintf("%s:%d", node, samples[i]))
		} else {
			parts = append(parts, node+":?")
		}
	}
	return strings.Join(parts, " ")
}

func idShort(id core.NodeID) string {
	s := id.String()
	if len(s) > 8 {
		return s[:8]
	}
	return s
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
