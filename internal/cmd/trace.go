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

var traceCmd = &cobra.Command{
	Use:   "trace <node-id> [via <hop>...]",
	Short: "Trace the route to a node",
	Long: `trace sends a Sidepath trace to a destination node and reports the round-trip
time and the forward path of relays it traversed. An explicit route can be
pinned with 'via':

  sp trace 1a2b3c4d5e6f7a8b9c0d
  sp trace <dest> via <relay1> <relay2> <dest>

Repeat like ping with -c/--count and -i/--interval (a count of 0 runs until
interrupted):

  sp trace -c 5 <dest>
  sp trace -c 0 -i 2s <dest>

trace requires a running daemon (the node performs the trace).`,
	Args: cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		if cfg.NoDaemon {
			return fmt.Errorf("trace requires the daemon; remove --no-daemon")
		}
		dest, route, err := parseTraceArgs(args)
		if err != nil {
			return err
		}
		count, _ := cmd.Flags().GetInt("count")
		interval, _ := cmd.Flags().GetDuration("interval")

		client := api.NewClient(cfg.SockPath())
		out := cmd.OutOrStdout()
		repeat := count != 1 // single-shot keeps the original output and error behavior

		// Verbose diagnostics go to stderr so they never pollute stdout/JSON.
		vlog := func(format string, a ...any) {
			if cfg.Verbose {
				fmt.Fprintf(cmd.ErrOrStderr(), nowTS()+"  "+format+"\n", a...)
			}
		}
		if len(route) > 0 {
			vlog("trace target=%s via=[%s] (%d-hop pinned route)", dest, strings.Join(route, " -> "), len(route))
		} else {
			vlog("trace target=%s route=auto (daemon selects from topology)", dest)
		}
		if repeat {
			vlog("repeat count=%d interval=%s (count 0 = until interrupted)", count, interval)
		}
		vlog("socket=%s timeout=20s per trace", cfg.SockPath())

		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()

		// Single trace: preserve the original behavior (error => non-zero exit),
		// but a cancellation (Ctrl-C) exits quietly.
		if !repeat {
			vlog("seq=1 sending trace request, awaiting reply...")
			res, err := client.Trace(ctx, dest, route)
			if err != nil {
				if ctx.Err() != nil {
					vlog("seq=1 interrupted")
					return nil
				}
				vlog("seq=1 failed: %v", err)
				return fmt.Errorf("trace failed: %w", err)
			}
			vlog("seq=1 reply tag=%08x rtt=%dms metric=%s hops=%d", res.Tag, res.RTTMs, res.Metric, len(res.Path))
			return printTrace(out, dest, 1, res, false, cfg.JSON)
		}

		var sent, recv int
		var rtts []int64
		infinite := count <= 0
	loop:
		for seq := 1; infinite || seq <= count; seq++ {
			if ctx.Err() != nil {
				break
			}
			vlog("seq=%d sending trace request, awaiting reply...", seq)
			res, err := client.Trace(ctx, dest, route)
			if ctx.Err() != nil {
				vlog("seq=%d interrupted", seq)
				break // interrupted mid-trace; don't count it
			}
			sent++
			if err != nil {
				vlog("seq=%d failed: %v", seq, err)
				if cfg.JSON {
					_ = json.NewEncoder(out).Encode(traceLine{Seq: seq, Error: err.Error()})
				} else {
					fmt.Fprintf(out, "seq=%d  FAILED: %v\n", seq, err)
				}
			} else {
				vlog("seq=%d reply tag=%08x rtt=%dms metric=%s hops=%d", seq, res.Tag, res.RTTMs, res.Metric, len(res.Path))
				recv++
				rtts = append(rtts, res.RTTMs)
				_ = printTrace(out, dest, seq, res, true, cfg.JSON)
			}

			if !infinite && seq >= count {
				break
			}
			select {
			case <-ctx.Done():
				break loop
			case <-time.After(interval):
			}
		}

		if !cfg.JSON {
			printTraceStats(out, dest, sent, recv, rtts)
		}
		return nil
	},
}

// traceLine is the JSON shape emitted per trace in repeat mode.
type traceLine struct {
	Seq    int      `json:"seq"`
	Tag    uint32   `json:"tag,omitempty"`
	RTTMs  int64    `json:"rtt_ms,omitempty"`
	Metric string   `json:"metric,omitempty"`
	Path   []string `json:"path,omitempty"`
	Error  string   `json:"error,omitempty"`
}

// printTrace renders one trace result. In repeat mode it is prefixed with the
// sequence number; single-shot keeps the original "trace <dest> ..." form.
func printTrace(out io.Writer, dest string, seq int, res *api.TraceResult, repeat, asJSON bool) error {
	if asJSON {
		return json.NewEncoder(out).Encode(traceLine{
			Seq: seq, Tag: res.Tag, RTTMs: res.RTTMs, Metric: res.Metric, Path: res.Path,
		})
	}
	path := "direct"
	if len(res.Path) > 0 {
		path = strings.Join(res.Path, " -> ")
	}
	if repeat {
		fmt.Fprintf(out, "seq=%d  rtt=%dms  metric=%s  path=[%s]\n", seq, res.RTTMs, res.Metric, path)
	} else {
		fmt.Fprintf(out, "trace %s  rtt=%dms  metric=%s  path=[%s]\n", dest, res.RTTMs, res.Metric, path)
	}
	return nil
}

// printTraceStats prints a ping-style summary.
func printTraceStats(out io.Writer, dest string, sent, recv int, rtts []int64) {
	loss := 100.0
	if sent > 0 {
		loss = float64(sent-recv) / float64(sent) * 100
	}
	fmt.Fprintf(out, "\n--- %s trace statistics ---\n", dest)
	fmt.Fprintf(out, "%d sent, %d received, %.0f%% loss\n", sent, recv, loss)
	if len(rtts) > 0 {
		min, max, sum := rtts[0], rtts[0], int64(0)
		for _, v := range rtts {
			if v < min {
				min = v
			}
			if v > max {
				max = v
			}
			sum += v
		}
		fmt.Fprintf(out, "rtt min/avg/max = %d/%d/%d ms\n", min, sum/int64(len(rtts)), max)
	}
}

// nowTS is a compact wall-clock timestamp for verbose trace logging.
func nowTS() string { return time.Now().Format("15:04:05.000") }

// parseTraceArgs splits "<dest> [via <hop>...]" into the destination and an
// optional explicit relay route.
func parseTraceArgs(args []string) (dest string, route []string, err error) {
	dest = args[0]
	if len(args) == 1 {
		return dest, nil, nil
	}
	if args[1] != "via" {
		return "", nil, fmt.Errorf("expected 'via' before the route, got %q", args[1])
	}
	route = args[2:]
	if len(route) == 0 {
		return "", nil, fmt.Errorf("'via' requires at least one hop")
	}
	return dest, route, nil
}

func init() {
	traceCmd.Flags().IntP("count", "c", 1, "number of traces to send (0 = until interrupted)")
	traceCmd.Flags().DurationP("interval", "i", time.Second, "wait between traces")
	rootCmd.AddCommand(traceCmd)
}
