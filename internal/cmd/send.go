package cmd

import (
	"context"
	"encoding/json"
	"fmt"
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

		if !wantAck {
			if err := client.SendDirect(dest, text); err != nil {
				return fmt.Errorf("send failed: %w", err)
			}
			if cfg.JSON {
				return json.NewEncoder(out).Encode(map[string]any{"sent": true, "dest": dest, "text": text})
			}
			fmt.Fprintf(out, "sent to %s\n", dest)
			return nil
		}

		// Wait for the ACK.
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		res, err := client.SendDirectAck(ctx, dest, text, ackWait)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("send failed: %w", err)
		}
		if cfg.JSON {
			return json.NewEncoder(out).Encode(map[string]any{"sent": true, "dest": dest, "text": text, "ack": res})
		}
		if res.Acked {
			fmt.Fprintf(out, "sent to %s — ACK from %s in %dms\n", dest, res.From, res.RTTMs)
		} else {
			fmt.Fprintf(out, "sent to %s — no ACK within %s\n", dest, ackWait)
		}
		return nil
	},
}

func init() {
	sendCmd.Flags().StringP("channel", "c", "", "broadcast on this channel instead of a direct message")
	sendCmd.Flags().BoolP("ack", "a", false, "wait for a delivery ACK (direct messages only)")
	sendCmd.Flags().Duration("wait", 0, "wait up to this long for an ACK (implies --ack)")
	rootCmd.AddCommand(sendCmd)
}
