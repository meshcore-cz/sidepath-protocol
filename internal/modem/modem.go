// Package modem drives the ESP32-C6 Sidepath BLE modem over its USB serial
// line protocol (see firmware/sidepath_modem_c6/PROTOCOL.md). It exposes a
// typed client for the request/reply commands (PING, INFO, SET_PHY, SEND, ...)
// and a channel of asynchronous RX/RELAY events.
//
// The modem is a dumb radio: it moves opaque `ttl || content` packets. This
// package keeps that contract — it does not parse Sidepath datagrams.
package modem

import (
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/sys/unix"
)

// DefaultBaud is the conventional rate; USB CDC ignores it but a UART bridge
// would honour it.
const DefaultBaud = 115200

// EventKind distinguishes the asynchronous lines the modem emits.
type EventKind string

const (
	EventRX    EventKind = "RX"
	EventRelay EventKind = "RELAY"
	EventReady EventKind = "READY"
)

// Event is one asynchronous modem notification.
type Event struct {
	Kind EventKind
	// RX fields.
	RSSI   int
	PHY    string
	Packet []byte // ttl || content (RX)
	// RELAY fields.
	Hash string
	TTL  int
	Raw  string // the original line, always set
}

// Client is a connected modem. It owns the serial port's read loop; one command
// is in flight at a time (guarded by mu).
type Client struct {
	port    *os.File
	mu      sync.Mutex  // serializes Command calls
	replies chan string // OK/ERR/INFO/STATS lines
	events  chan Event  // RX/RELAY/READY lines
	closed  chan struct{}
	once    sync.Once
	errMu   sync.RWMutex
	readErr error
}

// Open connects to the modem at the given serial path. Pass baud 0 for the
// default.
func Open(path string, baud int) (*Client, error) {
	if baud == 0 {
		baud = DefaultBaud
	}
	f, err := openSerial(path, baud)
	if err != nil {
		return nil, err
	}
	c := &Client{
		port:    f,
		replies: make(chan string, 16),
		events:  make(chan Event, 64),
		closed:  make(chan struct{}),
	}
	go c.readLoop()
	return c, nil
}

// Events returns the channel of asynchronous RX/RELAY/READY events. It is
// closed when the client closes.
func (c *Client) Events() <-chan Event { return c.events }

// Close shuts down the modem connection.
func (c *Client) Close() error {
	c.markClosed(nil)
	return c.port.Close()
}

func (c *Client) markClosed(err error) {
	if err != nil {
		c.errMu.Lock()
		c.readErr = err
		c.errMu.Unlock()
	}
	c.once.Do(func() { close(c.closed) })
}

func (c *Client) closedError() error {
	c.errMu.RLock()
	err := c.readErr
	c.errMu.RUnlock()
	if err != nil {
		return fmt.Errorf("modem closed: %w", err)
	}
	return fmt.Errorf("modem closed")
}

// readLoop is the sole reader of the serial port. It splits the byte stream into
// lines and routes each to the events or replies channel.
func (c *Client) readLoop() {
	defer close(c.events)
	var buf []byte
	tmp := make([]byte, 256)
	for {
		select {
		case <-c.closed:
			return
		default:
		}
		n, err := c.port.Read(tmp)
		if err != nil {
			if isTransientReadErr(err) {
				if errors.Is(err, io.EOF) {
					time.Sleep(100 * time.Millisecond)
				}
				continue
			}
			c.markClosed(err)
			return
		}
		if n == 0 {
			continue // VTIME timeout, nothing read
		}
		buf = append(buf, tmp[:n]...)
		for {
			i := indexLineEnd(buf)
			if i < 0 {
				break
			}
			line := strings.TrimRight(string(buf[:i]), "\r\n")
			buf = buf[i+1:]
			if line == "" {
				continue
			}
			c.route(line)
		}
	}
}

func isTransientReadErr(err error) bool {
	return errors.Is(err, os.ErrDeadlineExceeded) ||
		errors.Is(err, io.EOF) ||
		errors.Is(err, unix.EAGAIN) ||
		errors.Is(err, unix.EWOULDBLOCK) ||
		errors.Is(err, unix.EINTR)
}

func indexLineEnd(b []byte) int {
	for i, ch := range b {
		if ch == '\n' || ch == '\r' {
			return i
		}
	}
	return -1
}

// route classifies a line as an async event or a command reply.
func (c *Client) route(line string) {
	switch {
	case strings.HasPrefix(line, "RX "), strings.HasPrefix(line, "RELAY "),
		strings.HasPrefix(line, "READY "):
		ev := parseEvent(line)
		select {
		case c.events <- ev:
		default: // drop if the consumer is slow rather than block the reader
		}
	default:
		select {
		case c.replies <- line:
		case <-c.closed:
		}
	}
}

func parseEvent(line string) Event {
	ev := Event{Raw: line}
	f := strings.Fields(line)
	switch {
	case f[0] == "RX" && len(f) >= 4:
		ev.Kind = EventRX
		ev.RSSI, _ = strconv.Atoi(f[1])
		ev.PHY = f[2]
		ev.Packet, _ = hex.DecodeString(f[3])
	case f[0] == "RELAY" && len(f) >= 3:
		ev.Kind = EventRelay
		ev.Hash = f[1]
		ev.TTL, _ = strconv.Atoi(f[2])
	case f[0] == "READY":
		ev.Kind = EventReady
	}
	return ev
}

// Command sends one command line and collects reply lines until the terminating
// OK/ERR. It returns the non-terminal info lines (e.g. the INFO/STATS payload)
// and an error if the modem replied ERR or the timeout elapsed. The terminal
// OK line is not included.
func (c *Client) Command(line string, timeout time.Duration) ([]string, error) {
	all, err := c.CommandRaw(line, timeout)
	if err != nil {
		return all, err
	}
	// Drop the terminal OK line for the typed callers.
	if n := len(all); n > 0 && strings.HasPrefix(all[n-1], "OK") {
		all = all[:n-1]
	}
	return all, nil
}

// CommandRaw is Command but returns every reply line, including the terminal
// OK/ERR. On an ERR reply it returns the collected lines and a non-nil error.
func (c *Client) CommandRaw(line string, timeout time.Duration) ([]string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	// Drain any stale replies left from a previous timeout.
	for {
		select {
		case <-c.replies:
		default:
			goto send
		}
	}
send:
	if _, err := c.port.Write([]byte(line + "\n")); err != nil {
		return nil, fmt.Errorf("write %q: %w", line, err)
	}

	deadline := time.After(timeout)
	var lines []string
	for {
		select {
		case r := <-c.replies:
			switch {
			case strings.HasPrefix(r, "OK"):
				lines = append(lines, r)
				return lines, nil
			case strings.HasPrefix(r, "ERR"):
				lines = append(lines, r)
				return lines, fmt.Errorf("%s", strings.TrimSpace(strings.TrimPrefix(r, "ERR")))
			default:
				lines = append(lines, r) // INFO/STATS payload line
			}
		case <-deadline:
			return lines, fmt.Errorf("timeout waiting for reply to %q", line)
		case <-c.closed:
			return lines, c.closedError()
		}
	}
}

// --- typed command helpers ------------------------------------------------

const cmdTimeout = 3 * time.Second

func (c *Client) Ping() error {
	_, err := c.Command("PING", cmdTimeout)
	return err
}

// Info returns the parsed key=value fields from the INFO reply.
func (c *Client) Info() (map[string]string, error) {
	lines, err := c.Command("INFO", cmdTimeout)
	if err != nil {
		return nil, err
	}
	return parseKV(lines, "INFO"), nil
}

// Stats returns the parsed key=value fields from the STATS reply.
func (c *Client) Stats() (map[string]string, error) {
	lines, err := c.Command("STATS", cmdTimeout)
	if err != nil {
		return nil, err
	}
	return parseKV(lines, "STATS"), nil
}

// SetPHY selects "1M" or "CODED"; the returned string is the PHY the modem
// actually applied (a rejected CODED falls back to 1M).
func (c *Client) SetPHY(phy string) (string, error) {
	phy = strings.ToUpper(phy)
	if phy != "1M" && phy != "CODED" {
		return "", fmt.Errorf("phy must be 1M or CODED")
	}
	_, err := c.Command("SET_PHY "+phy, cmdTimeout)
	if err != nil {
		return "", err
	}
	// The modem echoes the applied PHY on the OK line; re-read via INFO to be
	// authoritative and simple.
	info, err := c.Info()
	if err != nil {
		return phy, nil
	}
	if p := info["phy"]; p != "" {
		return p, nil
	}
	return phy, nil
}

func (c *Client) SetTXPower(level string) error {
	level = strings.ToUpper(level)
	switch level {
	case "LOW", "MEDIUM", "HIGH":
	default:
		return fmt.Errorf("tx power must be LOW, MEDIUM or HIGH")
	}
	_, err := c.Command("SET_TX_POWER "+level, cmdTimeout)
	return err
}

func (c *Client) StartScan() error { _, err := c.Command("START_SCAN", cmdTimeout); return err }
func (c *Client) StopScan() error  { _, err := c.Command("STOP_SCAN", cmdTimeout); return err }
func (c *Client) RelayOn() error   { _, err := c.Command("RELAY_ON", cmdTimeout); return err }
func (c *Client) RelayOff() error  { _, err := c.Command("RELAY_OFF", cmdTimeout); return err }

// Send transmits one opaque packet (ttl || content). The modem floods it.
func (c *Client) Send(packet []byte) error {
	if len(packet) < 1 {
		return fmt.Errorf("packet must be at least 1 byte (ttl)")
	}
	_, err := c.Command("SEND "+hex.EncodeToString(packet), cmdTimeout)
	return err
}

// SendTTL is a convenience that prepends ttl to content.
func (c *Client) SendTTL(ttl uint8, content []byte) error {
	return c.Send(append([]byte{ttl}, content...))
}

// parseKV extracts "k=v" tokens from the reply lines, skipping the leading
// keyword (e.g. "INFO").
func parseKV(lines []string, keyword string) map[string]string {
	out := map[string]string{}
	for _, ln := range lines {
		f := strings.Fields(ln)
		for _, tok := range f {
			if tok == keyword {
				continue
			}
			if k, v, ok := strings.Cut(tok, "="); ok {
				out[k] = v
			}
		}
	}
	return out
}
