package cmd

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/spf13/cobra"
)

// defaultAckWait is how long --ack waits for an ACK when --wait isn't given.
const defaultAckWait = 10 * time.Second

var sendCmd = &cobra.Command{
	Use:   "send <node-id> <text> | --channel <name> <text>",
	Short: "Send a direct or channel message",
	Long: `send delivers a chat message through the running node.

Direct (encrypted DM to a node — its key must be known from a signed ANNOUNCE):
  sp send 1a2b3c4d5e6f7a8b9c0d "hello there"

Channel broadcast (-c/--channel; "Public" or any named channel):
  sp send -c Public "hi everyone"
  sp send --channel dev "build is green"

Wait for delivery acknowledgement (direct messages only):
  sp send --ack 1a2b3c... "did you get this?"
  sp send --wait 5s 1a2b3c... "..."

Pin an explicit source route instead of letting the node pick one, and print the
routing details (-v shows them for any send):
  sp send --path 1111116e82f1a143...,111111fe43731387... 1a2b3c... "via these relays"
  sp send -v 1a2b3c... "how did this go out?"

The message is the remaining arguments joined with spaces, so quoting is optional.
send requires a running daemon.`,
	Args: cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("send requires the daemon; remove --no-daemon")
		}
		channel, _ := cmd.Flags().GetString("channel")
		ack, _ := cmd.Flags().GetBool("ack")
		wait, _ := cmd.Flags().GetDuration("wait")
		path, _ := cmd.Flags().GetStringSlice("path")
		verbose, _ := cmd.Flags().GetBool("verbose")
		wantAck := ack || wait > 0
		ackWait := wait
		if ackWait <= 0 {
			ackWait = defaultAckWait
		}

		client := api.NewClient(cfg.SockPath())
		out := cmd.OutOrStdout()

		// Channel broadcast: no per-recipient ACK to wait for.
		if channel != "" {
			if wantAck {
				return fmt.Errorf("--ack/--wait is only supported for direct messages")
			}
			if len(path) > 0 {
				return fmt.Errorf("--path is only supported for direct messages")
			}
			text := strings.Join(args, " ")
			if strings.TrimSpace(text) == "" {
				return fmt.Errorf("a message is required")
			}
			if err := client.SendChannel(channel, text); err != nil {
				return fmt.Errorf("send failed: %w", err)
			}
			if cfg.JSON {
				return json.NewEncoder(out).Encode(map[string]any{"sent": true, "channel": channel, "text": text})
			}
			fmt.Fprintf(out, "sent to #%s\n", channel)
			return nil
		}

		// Direct message.
		if len(args) < 2 {
			return fmt.Errorf("usage: sp send <node-id> <text>  (or --channel <name> <text>)")
		}
		dest := args[0]
		text := strings.Join(args[1:], " ")
		// Show the routing detail block whenever the user asked for it or pinned a path.
		detail := verbose || len(path) > 0

		if !wantAck {
			res, err := client.SendDirect(dest, text, path)
			if err != nil {
				return fmt.Errorf("send failed: %w", err)
			}
			if cfg.JSON {
				return json.NewEncoder(out).Encode(map[string]any{"sent": true, "dest": dest, "text": text, "send": res})
			}
			printSent(out, dest, res, detail)
			return nil
		}

		// Wait for the ACK.
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		res, err := client.SendDirectAck(ctx, dest, text, path, ackWait)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("send failed: %w", err)
		}
		if cfg.JSON {
			return json.NewEncoder(out).Encode(map[string]any{"sent": true, "dest": dest, "text": text, "ack": res})
		}
		printSent(out, dest, res, detail)
		if res.Acked {
			fmt.Fprintf(out, "ACK from %s in %dms\n", res.From, res.RTTMs)
		} else {
			fmt.Fprintf(out, "no ACK within %s\n", ackWait)
		}
		return nil
	},
}

// printSent reports a direct send. With detail it adds the datagram id and the
// route the daemon actually used (explicit path, auto-selected source route, or flood).
func printSent(out io.Writer, dest string, res *api.SendResult, detail bool) {
	fmt.Fprintf(out, "sent to %s\n", dest)
	if !detail || res == nil {
		return
	}
	if res.DatagramID != "" {
		fmt.Fprintf(out, "  datagram: %s\n", res.DatagramID)
	}
	fmt.Fprintf(out, "  route:    %s\n", routeSummary(res))
}

// routeSummary renders how a direct send was routed: a flood fallback, a direct
// hop, or the ordered source route (each hop abbreviated, ending at the destination).
func routeSummary(res *api.SendResult) string {
	if res == nil {
		return "unknown"
	}
	if res.Flooded {
		return "flood (no route known)"
	}
	if len(res.Route) <= 1 {
		return "direct"
	}
	hops := make([]string, len(res.Route))
	for i, h := range res.Route {
		hops[i] = shortID(h)
	}
	return strings.Join(hops, " -> ")
}

func init() {
	sendCmd.Flags().StringP("channel", "c", "", "broadcast on this channel instead of a direct message")
	sendCmd.Flags().BoolP("ack", "a", false, "wait for a delivery ACK (direct messages only)")
	sendCmd.Flags().Duration("wait", 0, "wait up to this long for an ACK (implies --ack)")
	sendCmd.Flags().StringSlice("path", nil, "explicit source route: comma-separated relay NodeIDs to reach the destination (direct messages only)")
	sendCmd.Flags().BoolP("verbose", "v", false, "print routing details (datagram id and the route used)")
	rootCmd.AddCommand(sendCmd)
}
