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

	mcbridge "github.com/burningtree/bleedge/bridge/meshcore"
	"github.com/burningtree/bleedge/core"
	blenode "github.com/burningtree/bleedge/macos"
)

// stdoutIsTTY reports whether stdout is an interactive terminal (so we can run the
// full-screen TUI). When it isn't — piped, nohup, systemd — bot mode stays headless.
func stdoutIsTTY() bool {
	fi, err := os.Stdout.Stat()
	return err == nil && (fi.Mode()&os.ModeCharDevice) != 0
}

// lineWriter forwards a subprocess's output to a per-line sink (the TUI log), so Bun's
// stderr doesn't corrupt the alternate-screen TUI when the bot runs in the foreground.
type lineWriter struct{ emit func(string) }

func (w lineWriter) Write(p []byte) (int, error) {
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		if line != "" {
			w.emit("[bun] " + line)
		}
	}
	return len(p), nil
}

func main() {
	// Parse a minimal set of flags manually so we don't depend on flag pkg order
	seedHex := ""
	allowPeers := ""
	description := ""
	nodeName := ""
	botScript := ""
	bunPath := "bun"
	botChannels := "Public"
	meshcoreBridge := false
	meshcoreSocket := ""
	verbose := false
	for i, arg := range os.Args[1:] {
		switch {
		case arg == "--verbose" || arg == "-v":
			verbose = true
		case arg == "--meshcore-bridge":
			meshcoreBridge = true
		case strings.HasPrefix(arg, "--meshcore-socket="):
			meshcoreSocket = strings.TrimPrefix(arg, "--meshcore-socket=")
		case arg == "--meshcore-socket" && i+1 < len(os.Args[1:]):
			meshcoreSocket = os.Args[i+2]
		case strings.HasPrefix(arg, "--seed-hex="):
			seedHex = strings.TrimPrefix(arg, "--seed-hex=")
		case arg == "--seed-hex" && i+1 < len(os.Args[1:]):
			seedHex = os.Args[i+2]
		case strings.HasPrefix(arg, "--description="):
			description = strings.TrimPrefix(arg, "--description=")
		case arg == "--description" && i+1 < len(os.Args[1:]):
			description = os.Args[i+2]
		case strings.HasPrefix(arg, "--name="):
			nodeName = strings.TrimPrefix(arg, "--name=")
		case arg == "--name" && i+1 < len(os.Args[1:]):
			nodeName = os.Args[i+2]
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
  bleedge-macos --meshcore-bridge [--meshcore-socket <path>]               # bridge MeshCore packets into the BLEEdge mesh

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

Bridge mode (--meshcore-bridge): taps a running meshcore-go backend daemon over
  its Unix-socket IPC (watch_rf), and re-floods every MeshCore packet it hears
  into the BLEEdge mesh as an opaque v3 MESHCORE_PACKET datagram. Phase 1 is
  one-way (MeshCore -> BLEEdge). Requires the meshcore-go backend running with
  RF logging enabled. Socket defaults to MC_BACKEND_SOCKET or
  <cache>/mc/backend.sock; override with --meshcore-socket.

NOTE: macOS CoreBluetooth does NOT support LE Coded PHY.
      Use bleedge-listen on Linux for the Long Range PoC.
`)
			os.Exit(0)
		}
	}

	// Load or generate the Ed25519 identity (NodeID = pubkey[:10])
	var identity *core.Identity
	var announceEpoch uint64 = 1
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
		announceEpoch, err = blenode.LoadIncrementEpoch()
		if err != nil {
			fatalf("cannot load epoch: %v", err)
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

	// In bot mode, the bridge (set below) consumes incoming messages and, when a TTY is
	// present, the interactive TUI still runs in front (bot in the background). Without a
	// TTY (piped/daemon), bot mode stays fully headless.
	headless := botScript != "" && !stdoutIsTTY()
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
	emitBridge := func(line string) {
		select {
		case uiEvents <- uiEvent{line: line, kind: uiEventBridge}:
		default:
		}
	}
	logFn := func(msg string) {
		if !verbose {
			return
		}
		line := fmt.Sprintf("%s  %s", time.Now().Format("15:04:05"), msg)
		if headless {
			fmt.Fprintln(os.Stderr, line)
			return
		}
		emitLog(line)
	}
	// Hoisted so OnMessage (defined below) can bridge outbound channel messages once the bridge is
	// started further down; bridgeLog is the shared status sink for both directions.
	bridgeLog := func(s string) {
		if headless {
			fmt.Fprintln(os.Stderr, s)
			return
		}
		emitBridge(fmt.Sprintf("%s  %s", time.Now().Format("15:04:05"), s))
	}
	var br *mcbridge.Bridge
	node = blenode.New(blenode.Config{
		Identity:      identity,
		Name:          nodeName,
		Description:   description,
		Caps:          core.Capabilities(core.CapSender | core.CapReceiver | core.CapRelay),
		Allowlist:     allowlist,
		Verbose:       verbose,
		AnnounceEpoch: announceEpoch,
		LogFn:         logFn,
		OnMessage: func(dg core.Datagram) {
			// Bridge native BLEEdge channel messages outward onto the real MeshCore network as a
			// GRP_TXT — exactly once per BLEEdge datagram — then ACK_BRIDGED the original sender.
			// Diagnostics go to BOTH the bridge pane and the main log so they're easy to see.
			outLog := func(s string) {
				bridgeLog(s)
				emitLog(fmt.Sprintf("%s  %s", time.Now().Format("15:04:05"), s))
			}
			if meshcoreBridge && dg.Protocol == core.ProtocolBLEEdgeChat {
				idHex := hex.EncodeToString(dg.ID[:])
				cp := core.ChannelPayloadFromChat(dg.Payload)
				switch {
				case br == nil:
					outLog(fmt.Sprintf("meshcore bridge: out skip dg=%s reason=bridge-disabled", idHex))
				case len(cp) == 0:
					outLog(fmt.Sprintf("meshcore bridge: out skip dg=%s reason=not-a-channel-message", idHex))
				default:
					go func(src core.NodeID, id core.DatagramID, cp []byte, idHex string) {
						meshHash, bridged, err := br.BridgeChannelOut(ctx, idHex, cp)
						if err != nil {
							outLog(fmt.Sprintf("meshcore bridge: out FAILED dg=%s: %v", idHex, err))
							return
						}
						if !bridged {
							return // already emitted onto MeshCore
						}
						outLog(fmt.Sprintf("meshcore bridge: out dg=%s -> MeshCore GRP_TXT hash=%s", idHex, hex.EncodeToString(meshHash)))
						if err := node.SendBridgedAck(src, id, meshHash); err != nil {
							outLog(fmt.Sprintf("meshcore bridge: ack_bridged FAILED dg=%s: %v", idHex, err))
						} else {
							outLog(fmt.Sprintf("meshcore bridge: ack_bridged -> %s dg=%s", src, idHex))
						}
					}(dg.Source, dg.ID, cp, idHex)
				}
			}
			// The bot consumes every message; the TUI (when present) also shows it.
			if theBot != nil {
				theBot.onIncoming(dg)
			}
			if headless {
				return
			}
			ts := time.Now().Format("15:04:05")
			if dg.Protocol == core.ProtocolBLEEdgeChat {
				if in, ok := node.DecodeChannel(dg.Payload); ok {
					emitUI(fmt.Sprintf("[%s] #%s %s: %s", ts, in.Channel, in.Sender, in.Text))
					return
				}
			}
			emitUI(fmt.Sprintf("[%s] MSG from %s: %s", ts, dg.Source, renderIncoming(dg, identity)))
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

	// MeshCore bridge: tap the meshcore-go backend and re-flood packets. The outbound direction
	// (BLEEdge channel -> MeshCore GRP_TXT) is driven from OnMessage above via this same bridge.
	if meshcoreBridge {
		br = mcbridge.New(mcbridge.Config{Socket: meshcoreSocket, Log: bridgeLog})
		go br.Run(ctx, func(pkt mcbridge.Packet) {
			switch pkt.Mode {
			case mcbridge.ForwardFlood:
				tx, err := node.SendMeshCoreRawWithInfo(pkt.Bytes)
				if err != nil {
					bridgeLog(fmt.Sprintf("meshcore bridge: flood failed: %v packet=%s", err, pkt.Summary()))
					return
				}
				bridgeLog(fmt.Sprintf("meshcore bridge: flooded dg=%s bytes=%d fragments=%d %s",
					hex.EncodeToString(tx.DatagramID[:]), tx.DatagramBytes, tx.FragmentCount, pkt.Summary()))
			case mcbridge.ForwardDirect:
				dst, ok := node.MeshCoreNeighborForHash(pkt.TargetHash)
				if !ok {
					bridgeLog("meshcore bridge: skip direct " + pkt.Summary() + " reason=no reachable BLEEdge neighbor for MeshCore hash")
					return
				}
				tx, err := node.SendMeshCoreRawToWithInfo(dst, pkt.Bytes)
				if err != nil {
					bridgeLog(fmt.Sprintf("meshcore bridge: direct failed dst=%s err=%v packet=%s", dst, err, pkt.Summary()))
					return
				}
				bridgeLog(fmt.Sprintf("meshcore bridge: direct dst=%s dg=%s bytes=%d fragments=%d %s",
					dst, hex.EncodeToString(tx.DatagramID[:]), tx.DatagramBytes, tx.FragmentCount, pkt.Summary()))
			default:
				bridgeLog("meshcore bridge: skip unknown forwarding mode " + pkt.Summary())
			}
		})
	}

	// Bot mode: start the Bun bridge. Headless (no TTY) runs until interrupted; with a TTY
	// the bot runs in the background and we fall through to the interactive TUI in front.
	if botScript != "" {
		channels := parseChannelList(botChannels)
		// Interactive: bot diagnostics go to the TUI log. Headless: straight to stderr.
		botLog := emitLog
		if headless {
			botLog = func(s string) { fmt.Fprintln(os.Stderr, s) }
		}
		b, err := startBot(ctx, node, bunPath, botScript, channels, botLog)
		if err != nil {
			fatalf("cannot start bot: %v", err)
		}
		theBot = b
		if headless {
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
		emitUI(fmt.Sprintf("bot running in background: %s  channels=%v", botScript, channels))
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
func renderIncoming(dg core.Datagram, id *core.Identity) string {
	if dg.Protocol == core.ProtocolBLEEdgeChat {
		ctx := core.ChatContext{DatagramID: dg.ID, Source: dg.Source, Destination: dg.Destination}
		switch env, err := core.DecodeChatEnvelope(dg.Payload); {
		case err != nil:
			return "[bad chat payload]"
		case env.Kind == core.ChatDirectText:
			if msg, ok := core.OpenDirectText(id, dg.Payload, ctx); ok {
				return msg.Text
			}
			return "[encrypted - not for me]"
		case env.Kind == core.ChatPublicText:
			ctx.Destination = core.BroadcastNodeID
			if msg, ok := core.OpenPublicText(dg.Payload, ctx); ok {
				return msg.Text
			}
			return "[bad public chat]"
		case env.Kind == core.ChatTyping:
			return "[typing]"
		}
	}
	return string(dg.Payload)
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
		metric = core.TraceMetricNameUnknown
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

// descLabel renders a node's free-form bio as "(desc)", or "" when empty.
func descLabel(desc string) string {
	if desc == "" {
		return ""
	}
	return "(" + desc + ")"
}

// nameLabel renders a node's primary name, or "?" when unknown.
func nameLabel(name string) string {
	if name == "" {
		return "?"
	}
	return name
}

// platLabel renders a node's platform as " [platform]", or "" when unknown.
func platLabel(platform string) string {
	if platform == "" {
		return ""
	}
	return " [" + platform + "]"
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
