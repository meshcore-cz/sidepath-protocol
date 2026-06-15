package cmd

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/internal/config"
	"github.com/meshcore-cz/sidepath-protocol/internal/daemon"
	"github.com/meshcore-cz/sidepath-protocol/internal/modem"
	"github.com/meshcore-cz/sidepath-protocol/internal/node"
	"github.com/spf13/cobra"
)

var daemonCmd = &cobra.Command{
	Use:   "daemon",
	Short: "Run and control the background node daemon",
	Long: `The daemon is the long-lived sp process. It owns the live link table and
listens on a Unix control socket that foreground commands (sp status, sp peers)
talk to. There is no separate daemon binary — 'sp daemon run' is the daemon.`,
}

var daemonRunCmd = &cobra.Command{
	Use:   "run",
	Short: "Run the daemon in the foreground",
	Long:  "run starts the daemon in the foreground, logging to stderr and the daemon log. It blocks until interrupted (Ctrl-C / SIGTERM).",
	RunE: func(cmd *cobra.Command, args []string) error {
		if err := cfg.EnsureDir(); err != nil {
			return err
		}
		id, err := core.LoadOrCreateIdentity(cfg.SeedPath())
		if err != nil {
			return fmt.Errorf("load identity: %w", err)
		}

		logFile, err := os.OpenFile(cfg.LogPath(), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
		if err != nil {
			return fmt.Errorf("open log: %w", err)
		}
		defer logFile.Close()

		// Always log to the file; also mirror to stderr in the foreground.
		var sink io.Writer = logFile
		if foreground, _ := cmd.Flags().GetBool("foreground"); foreground || cfg.Verbose {
			sink = io.MultiWriter(logFile, cmd.ErrOrStderr())
		}
		logf := func(format string, a ...any) {
			fmt.Fprintf(sink, "%s  %s\n", time.Now().Format("2006-01-02 15:04:05"), fmt.Sprintf(format, a...))
		}

		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()

		// Bring up the live node (BLE + optional bot/MeshCore bridge). Its
		// options come from node.json layered with the flags below.
		nodeCfg := resolveNodeConfig(cmd)
		rt, nodeErrCh, err := node.Start(ctx, id, cfg, nodeCfg, logf)
		if err != nil {
			return fmt.Errorf("start node: %w", err)
		}

		d := daemon.New(cfg, id, logf)
		d.SetPeerSource(rt)

		// Optionally attach an ESP32-C6 BLE modem on a serial port. When
		// attached it can act as a connectionless relay (scan + reflood) and is
		// controllable via 'sp modem'.
		if port := nodeCfg.Modem; port != "" {
			mc, err := modem.Open(port, 0)
			if err != nil {
				return fmt.Errorf("open modem %s: %w", port, err)
			}
			defer mc.Close()
			d.SetModem(mc)
			logf("modem attached on %s", port)
			if nodeCfg.ModemRelay {
				if err := mc.RelayOn(); err != nil {
					logf("modem relay enable failed: %v", err)
				}
				if err := mc.StartScan(); err != nil {
					logf("modem scan start failed: %v", err)
				}
				logf("modem relay+scan enabled")
			}
		}

		// If the node exits on its own, take the daemon down with it.
		go func() {
			if e := <-nodeErrCh; e != nil {
				logf("node exited: %v", e)
			}
			stop()
		}()

		return d.Run(ctx)
	},
}

// addNodeFlags registers the node runtime flags shared by 'daemon run' (which
// consumes them) and 'daemon start' (which forwards them to the child).
func addNodeFlags(cmd *cobra.Command) {
	f := cmd.Flags()
	f.String("name", "", "node display name")
	f.String("description", "", "free-form node bio shown to peers")
	f.String("bot", "", "Bun bot script to run (e.g. bots/echo-bot.ts)")
	f.String("bun", "bun", "path to the Bun executable")
	f.String("channels", "", "comma-separated channels to join (default Public)")
	f.Bool("meshcore-bridge", false, "tap the meshcore-go backend and bridge packets into the mesh")
	f.String("meshcore-socket", "", "meshcore-go backend socket (default MC_BACKEND_SOCKET or <cache>/mc/backend.sock)")
	f.StringArray("bridge", nil, "external network this gateway advertises; repeatable (e.g. CZ or CZ:869525000,250000,11,5)")
	f.StringArray("allow-peer", nil, "allowed peer NodeID (hex); repeatable; empty = allow all")
}

// resolveNodeConfig layers the CLI flags on top of config.toml: the file provides
// defaults, an explicitly-set flag overrides.
func resolveNodeConfig(cmd *cobra.Command) config.NodeConfig {
	nc, err := config.LoadNodeConfig(cfg.ConfigPath())
	if err != nil {
		fmt.Fprintf(cmd.ErrOrStderr(), "sp: ignoring bad %s: %v\n", cfg.ConfigPath(), err)
	}
	f := cmd.Flags()
	if f.Changed("name") {
		nc.Name, _ = f.GetString("name")
	}
	if f.Changed("description") {
		nc.Description, _ = f.GetString("description")
	}
	if f.Changed("bot") {
		nc.Bot, _ = f.GetString("bot")
	}
	if f.Changed("bun") {
		nc.Bun, _ = f.GetString("bun")
	}
	if f.Changed("channels") {
		s, _ := f.GetString("channels")
		nc.Channels = splitChannels(s)
	}
	if f.Changed("meshcore-bridge") {
		nc.MeshcoreBridge, _ = f.GetBool("meshcore-bridge")
	}
	if f.Changed("meshcore-socket") {
		nc.MeshcoreSocket, _ = f.GetString("meshcore-socket")
	}
	if f.Changed("bridge") {
		nc.Bridges, _ = f.GetStringArray("bridge")
	}
	if f.Changed("allow-peer") {
		nc.AllowPeers, _ = f.GetStringArray("allow-peer")
	}
	if f.Changed("modem") {
		nc.Modem, _ = f.GetString("modem")
	}
	if f.Changed("modem-relay") {
		nc.ModemRelay, _ = f.GetBool("modem-relay")
	}
	if cfg.Verbose {
		nc.Verbose = true
	}
	return nc
}

// forwardNodeFlags rebuilds the explicitly-set node flags so 'daemon start' can
// pass them to the detached 'daemon run' child.
func forwardNodeFlags(cmd *cobra.Command) []string {
	var out []string
	f := cmd.Flags()
	str := func(name string) {
		if f.Changed(name) {
			v, _ := f.GetString(name)
			out = append(out, "--"+name, v)
		}
	}
	arr := func(name string) {
		if f.Changed(name) {
			vs, _ := f.GetStringArray(name)
			for _, v := range vs {
				out = append(out, "--"+name, v)
			}
		}
	}
	str("name")
	str("description")
	str("bot")
	str("bun")
	str("channels")
	str("meshcore-socket")
	if f.Changed("meshcore-bridge") {
		if v, _ := f.GetBool("meshcore-bridge"); v {
			out = append(out, "--meshcore-bridge")
		}
	}
	arr("bridge")
	arr("allow-peer")
	str("modem")
	if f.Changed("modem-relay") {
		if v, _ := f.GetBool("modem-relay"); !v {
			out = append(out, "--modem-relay=false")
		}
	}
	return out
}

func splitChannels(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if name := strings.TrimSpace(part); name != "" {
			out = append(out, name)
		}
	}
	return out
}

var daemonStartCmd = &cobra.Command{
	Use:   "start",
	Short: "Start the daemon in the background",
	Long:  "start launches 'sp daemon run' as a detached background process and waits until its control socket is live.",
	RunE: func(cmd *cobra.Command, args []string) error {
		return startDaemon(cmd)
	},
}

var daemonStopCmd = &cobra.Command{
	Use:   "stop",
	Short: "Stop the background daemon",
	RunE: func(cmd *cobra.Command, args []string) error {
		running, err := stopDaemon(cmd)
		if err != nil {
			return err
		}
		if !running {
			return fmt.Errorf("daemon not running")
		}
		return nil
	},
}

var daemonRestartCmd = &cobra.Command{
	Use:   "restart",
	Short: "Restart the background daemon",
	Long:  "restart stops the running daemon (if any) and starts a fresh one. Use it after upgrading the sp binary so the new code is loaded.",
	RunE: func(cmd *cobra.Command, args []string) error {
		if _, err := stopDaemon(cmd); err != nil {
			return err
		}
		return startDaemon(cmd)
	},
}

// startDaemon launches a detached 'daemon run' child and waits for its control
// socket to come live. The node flags set on cmd are forwarded to the child.
func startDaemon(cmd *cobra.Command) error {
	if err := cfg.EnsureDir(); err != nil {
		return err
	}
	if api.NewClient(cfg.SockPath()).Ping() {
		fmt.Fprintln(cmd.OutOrStdout(), "daemon already running")
		return nil
	}

	self, err := os.Executable()
	if err != nil {
		return err
	}
	logFile, err := os.OpenFile(cfg.LogPath(), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return fmt.Errorf("open log: %w", err)
	}
	defer logFile.Close()

	// Re-exec ourselves as the daemon, preserving the resolved state dir so the
	// child writes its socket/PID where this command expects them, and
	// forwarding the node flags the user passed.
	runArgs := []string{"--config", cfg.Dir}
	if cfg.Verbose {
		runArgs = append(runArgs, "--verbose")
	}
	runArgs = append(runArgs, "daemon", "run")
	runArgs = append(runArgs, forwardNodeFlags(cmd)...)
	child := exec.Command(self, runArgs...)
	child.Stdout = logFile
	child.Stderr = logFile
	child.SysProcAttr = &syscall.SysProcAttr{Setsid: true} // detach from this terminal
	if err := child.Start(); err != nil {
		return fmt.Errorf("start daemon: %w", err)
	}
	pid := child.Process.Pid // capture before Release invalidates it
	_ = child.Process.Release()

	if err := waitForSocket(cfg.SockPath(), 5*time.Second); err != nil {
		return fmt.Errorf("daemon did not come up: %w (see %s)", err, cfg.LogPath())
	}
	fmt.Fprintf(cmd.OutOrStdout(), "daemon started (pid %d)\n", pid)
	return nil
}

// stopDaemon signals the running daemon and waits for it to exit, reporting
// whether one was actually running. Liveness is judged by the control socket so
// a stale PID file does not block a restart.
func stopDaemon(cmd *cobra.Command) (bool, error) {
	if !api.NewClient(cfg.SockPath()).Ping() {
		return false, nil
	}
	pid, err := readPID(cfg.PIDPath())
	if err != nil {
		return false, fmt.Errorf("daemon is responding but its PID file is unreadable: %w", err)
	}
	proc, err := os.FindProcess(pid)
	if err != nil {
		return false, err
	}
	if err := proc.Signal(syscall.SIGTERM); err != nil {
		return false, fmt.Errorf("signal pid %d: %w", pid, err)
	}
	// Wait for the socket to stop answering as confirmation of a clean exit.
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if !api.NewClient(cfg.SockPath()).Ping() {
			fmt.Fprintf(cmd.OutOrStdout(), "daemon stopped (pid %d)\n", pid)
			return true, nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	fmt.Fprintf(cmd.OutOrStdout(), "sent SIGTERM to pid %d (still shutting down)\n", pid)
	return true, nil
}

var daemonStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Report whether the daemon is running",
	RunE: func(cmd *cobra.Command, args []string) error {
		out := cmd.OutOrStdout()
		if s, err := api.NewClient(cfg.SockPath()).Status(); err == nil {
			fmt.Fprintf(out, "running  pid=%d node=%s uptime=%s peers=%d\n", s.PID, s.NodeID, s.Uptime(), s.PeerCount)
			return nil
		}
		fmt.Fprintln(out, "stopped")
		return nil
	},
}

var daemonLogsCmd = &cobra.Command{
	Use:   "logs",
	Short: "Show the daemon log",
	Long:  "logs prints the daemon log file. Use --follow to stream new lines as they are written.",
	RunE: func(cmd *cobra.Command, args []string) error {
		follow, _ := cmd.Flags().GetBool("follow")
		f, err := os.Open(cfg.LogPath())
		if err != nil {
			return fmt.Errorf("no log at %s (%v)", cfg.LogPath(), err)
		}
		defer f.Close()
		out := cmd.OutOrStdout()
		if _, err := io.Copy(out, f); err != nil {
			return err
		}
		if !follow {
			return nil
		}
		// Tail: poll for appended bytes until interrupted.
		ctx, stop := signal.NotifyContext(cmd.Context(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		for {
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(300 * time.Millisecond):
				if _, err := io.Copy(out, f); err != nil {
					return err
				}
			}
		}
	},
}

// waitForSocket blocks until a daemon answers on sock or the timeout elapses.
func waitForSocket(sock string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if api.NewClient(sock).Ping() {
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return fmt.Errorf("timed out after %s", timeout)
}

// readPID reads and parses the daemon PID file.
func readPID(path string) (int, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(b)))
}

func init() {
	daemonRunCmd.Flags().Bool("foreground", false, "mirror logs to stderr (implied by --verbose)")
	daemonLogsCmd.Flags().BoolP("follow", "f", false, "stream new log lines until interrupted")
	for _, c := range []*cobra.Command{daemonRunCmd, daemonStartCmd, daemonRestartCmd} {
		addNodeFlags(c)
		c.Flags().String("modem", "", "attach a Sidepath BLE modem on this serial port (e.g. /dev/ttyACM0 or /dev/cu.usbmodem*)")
		c.Flags().Bool("modem-relay", true, "enable scan + connectionless relay on the attached modem")
	}

	daemonCmd.AddCommand(daemonRunCmd, daemonStartCmd, daemonRestartCmd, daemonStopCmd, daemonStatusCmd, daemonLogsCmd)
	rootCmd.AddCommand(daemonCmd)
}
