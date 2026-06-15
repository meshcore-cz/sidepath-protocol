package cmd

import (
	"context"
	"encoding/hex"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/internal/modem"
	"github.com/spf13/cobra"
)

// modem flags (persistent on the modem command group).
var (
	flagModemPort string
	flagModemBaud int
)

var modemCmd = &cobra.Command{
	Use:   "modem",
	Short: "Control an ESP32-C6 Sidepath BLE modem",
	Long: `modem drives the ESP32-C6 Sidepath BLE modem (firmware/sidepath_modem_c6) over
its USB serial line protocol.

Two ways to reach the modem:

  • Direct — pass --port to talk to the serial device yourself:
      sp modem --port /dev/ttyACM0 info
  • Via the daemon — omit --port and the command runs on the modem the daemon
    owns (started with 'sp daemon run --modem <port>'):
      sp modem info

The modem is a dumb radio: it floods opaque 'ttl||content' packets over BLE
advertisements and reports received ones. It holds no routing state.`,
}

// modemExec runs a raw modem command line, either directly over the serial port
// (--port) or via the daemon's attached modem. It returns every reply line
// (including the terminal OK/ERR) so callers can print them verbatim.
func modemExec(line string) ([]string, error) {
	if flagModemPort != "" {
		c, err := modem.Open(flagModemPort, flagModemBaud)
		if err != nil {
			return nil, err
		}
		defer c.Close()
		return c.CommandRaw(line, 5*time.Second)
	}
	if cfg.NoDaemon {
		return nil, fmt.Errorf("modem needs --port or a running daemon (remove --no-daemon)")
	}
	lines, err := api.NewClient(cfg.SockPath()).Modem(line)
	if err != nil {
		return nil, fmt.Errorf("cannot reach daemon: %w (is 'sp daemon' running with --modem?)", err)
	}
	return lines, nil
}

// printLines echoes modem reply lines, indenting for readability.
func printLines(cmd *cobra.Command, lines []string) {
	out := cmd.OutOrStdout()
	for _, ln := range lines {
		fmt.Fprintln(out, ln)
	}
}

func newModemRun(use, short string, build func(args []string) (string, error)) *cobra.Command {
	return &cobra.Command{
		Use:   use,
		Short: short,
		RunE: func(cmd *cobra.Command, args []string) error {
			line, err := build(args)
			if err != nil {
				return err
			}
			lines, err := modemExec(line)
			printLines(cmd, lines)
			return err
		},
	}
}

func init() {
	pf := modemCmd.PersistentFlags()
	pf.StringVar(&flagModemPort, "port", "", "modem serial port (e.g. /dev/ttyACM0 or /dev/cu.usbmodem*); omit to use the daemon's modem")
	pf.IntVar(&flagModemBaud, "baud", modem.DefaultBaud, "serial baud (USB CDC ignores it)")

	modemCmd.AddCommand(
		newModemRun("ping", "Liveness check", func([]string) (string, error) { return "PING", nil }),
		newModemRun("info", "Show modem firmware/radio info", func([]string) (string, error) { return "INFO", nil }),
		newModemRun("stats", "Show relay/traffic counters", func([]string) (string, error) { return "STATS", nil }),
		newModemRun("set-phy <1M|CODED>", "Select the BLE PHY (Coded = Long Range)", func(a []string) (string, error) {
			if len(a) != 1 {
				return "", fmt.Errorf("usage: sp modem set-phy 1M|CODED")
			}
			return "SET_PHY " + strings.ToUpper(a[0]), nil
		}),
		newModemRun("set-tx-power <LOW|MEDIUM|HIGH>", "Set TX power tier", func(a []string) (string, error) {
			if len(a) != 1 {
				return "", fmt.Errorf("usage: sp modem set-tx-power LOW|MEDIUM|HIGH")
			}
			return "SET_TX_POWER " + strings.ToUpper(a[0]), nil
		}),
		newModemRun("scan <on|off>", "Start or stop scanning", func(a []string) (string, error) {
			return onOff(a, "START_SCAN", "STOP_SCAN")
		}),
		newModemRun("relay <on|off>", "Enable or disable connectionless relay", func(a []string) (string, error) {
			return onOff(a, "RELAY_ON", "RELAY_OFF")
		}),
		newModemRun("send <hex>", "Transmit one opaque packet (hex of ttl||content)", func(a []string) (string, error) {
			if len(a) != 1 {
				return "", fmt.Errorf("usage: sp modem send <hex ttl||content>")
			}
			h := strings.TrimPrefix(a[0], "0x")
			if _, err := hex.DecodeString(h); err != nil {
				return "", fmt.Errorf("invalid hex: %w", err)
			}
			return "SEND " + h, nil
		}),
		modemMonitorCmd,
	)
	modemMonitorCmd.Flags().Bool("scan", true, "start scanning before monitoring")
	rootCmd.AddCommand(modemCmd)
}

func onOff(args []string, on, off string) (string, error) {
	if len(args) != 1 {
		return "", fmt.Errorf("expected 'on' or 'off'")
	}
	switch strings.ToLower(args[0]) {
	case "on":
		return on, nil
	case "off":
		return off, nil
	default:
		return "", fmt.Errorf("expected 'on' or 'off', got %q", args[0])
	}
}

// modemMonitorCmd streams asynchronous RX/RELAY events. It requires a direct
// --port connection (the daemon owns the event stream when it holds the modem;
// use 'sp daemon logs -f' there).
var modemMonitorCmd = &cobra.Command{
	Use:   "monitor",
	Short: "Stream RX/RELAY events from the modem (requires --port)",
	Long: `monitor opens the modem directly and prints every asynchronous RX and RELAY
event until interrupted. It optionally starts scanning first (--scan).

When the daemon owns the modem, watch 'sp daemon logs -f' instead.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		if flagModemPort == "" {
			return fmt.Errorf("monitor requires --port (the daemon's modem streams to 'sp daemon logs -f')")
		}
		c, err := modem.Open(flagModemPort, flagModemBaud)
		if err != nil {
			return err
		}
		defer c.Close()

		if scan, _ := cmd.Flags().GetBool("scan"); scan {
			if err := c.StartScan(); err != nil {
				return fmt.Errorf("start scan: %w", err)
			}
		}

		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()

		out := cmd.OutOrStdout()
		fmt.Fprintln(out, "monitoring modem (Ctrl-C to stop)...")
		for {
			select {
			case <-ctx.Done():
				return nil
			case ev, ok := <-c.Events():
				if !ok {
					return fmt.Errorf("modem connection closed")
				}
				switch ev.Kind {
				case modem.EventRX:
					fmt.Fprintf(out, "RX   rssi=%d phy=%s packet=%s\n", ev.RSSI, ev.PHY, hex.EncodeToString(ev.Packet))
				case modem.EventRelay:
					fmt.Fprintf(out, "RLY  hash=%s ttl=%d\n", ev.Hash, ev.TTL)
				default:
					fmt.Fprintln(out, ev.Raw)
				}
			}
		}
	},
}
