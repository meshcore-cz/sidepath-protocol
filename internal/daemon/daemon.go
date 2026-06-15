// Package daemon implements the long-lived sp background process.
//
// One binary does everything: `sp daemon run` starts this server, which writes
// a PID file, listens on a Unix control socket, and answers the control API
// (status, peers) used by the foreground CLI commands. Real transports (BLE,
// MeshCore) and routing will register with the daemon later; for now it owns
// the process lifecycle, the control plane, and an in-memory peer registry that
// those transports will populate.
package daemon

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/meshcore-cz/sidepath-protocol/core"
	"github.com/meshcore-cz/sidepath-protocol/internal/api"
	"github.com/meshcore-cz/sidepath-protocol/internal/config"
)

// Version is the daemon protocol/build version reported over the control API.
const Version = "0.1.0-skeleton"

// Daemon is the running background process.
type Daemon struct {
	cfg       *config.Config
	identity  *core.Identity
	startedAt time.Time
	logf      func(format string, args ...any)

	mu      sync.RWMutex
	peers   []api.Peer // fallback registry when no live node is attached
	peerSrc PeerSource // the running node, when one is attached
}

// PeerSource is anything that can report the current peer set — in practice the
// running node. The daemon stays platform-agnostic by depending on this
// interface rather than the node package directly.
type PeerSource interface {
	Peers() []api.Peer
}

// Tracer is an attached node that can trace the route to a destination. The
// peer source usually also implements this (it is the same running node).
type Tracer interface {
	Trace(ctx context.Context, dest string, route []string) (api.TraceResult, error)
}

// Sender is an attached node that can send chat messages. SendDirect waits for
// an ACK (until ctx is done) when wantAck is set.
type Sender interface {
	SendDirect(ctx context.Context, dest, text string, wantAck bool) (api.SendResult, error)
	SendChannel(channel, text string) error
}

// New builds a daemon bound to the given config and identity. logf is the sink
// for human-readable diagnostics (file + optional stderr); pass nil to discard.
func New(cfg *config.Config, id *core.Identity, logf func(string, ...any)) *Daemon {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &Daemon{cfg: cfg, identity: id, startedAt: time.Now(), logf: logf}
}

// Run starts the control server and blocks until ctx is cancelled. It writes
// the PID file and socket on startup and removes them on exit.
func (d *Daemon) Run(ctx context.Context) error {
	if err := d.cfg.EnsureDir(); err != nil {
		return err
	}
	if c := api.NewClient(d.cfg.SockPath()); c.Ping() {
		return fmt.Errorf("daemon already running (socket %s is live)", d.cfg.SockPath())
	}
	// A stale socket from a crashed daemon blocks bind; clear it now that we
	// know nothing is answering on it.
	_ = os.Remove(d.cfg.SockPath())

	ln, err := net.Listen("unix", d.cfg.SockPath())
	if err != nil {
		return fmt.Errorf("listen %s: %w", d.cfg.SockPath(), err)
	}
	defer ln.Close()
	defer os.Remove(d.cfg.SockPath())

	if err := d.writePID(); err != nil {
		return err
	}
	defer os.Remove(d.cfg.PIDPath())

	d.logf("daemon started pid=%d node=%s socket=%s", os.Getpid(), d.identity.NodeID(), d.cfg.SockPath())

	// Unblock Accept when the context is cancelled.
	go func() {
		<-ctx.Done()
		ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				d.logf("daemon stopping")
				return nil
			default:
				d.logf("accept error: %v", err)
				return err
			}
		}
		go d.handle(conn)
	}
}

func (d *Daemon) handle(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))

	var req api.Request
	if err := json.NewDecoder(conn).Decode(&req); err != nil {
		_ = json.NewEncoder(conn).Encode(api.Response{OK: false, Error: "bad request: " + err.Error()})
		return
	}

	var resp api.Response
	switch req.Method {
	case api.MethodStatus:
		s := d.status()
		resp = api.Response{OK: true, Status: &s}
	case api.MethodPeers:
		resp = api.Response{OK: true, Peers: d.Peers()}
	case api.MethodTrace:
		resp = d.trace(conn, req)
	case api.MethodSend:
		resp = d.send(conn, req)
	default:
		resp = api.Response{OK: false, Error: "unknown method: " + req.Method}
	}
	_ = json.NewEncoder(conn).Encode(resp)
}

// trace runs a trace via the attached node. It extends the connection deadline
// because reaching the destination and awaiting the reply takes longer than a
// local query.
func (d *Daemon) trace(conn net.Conn, req api.Request) api.Response {
	d.mu.RLock()
	tr, ok := d.peerSrc.(Tracer)
	d.mu.RUnlock()
	if !ok || tr == nil {
		return api.Response{OK: false, Error: "no node attached to daemon"}
	}
	_ = conn.SetDeadline(time.Now().Add(25 * time.Second))
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	res, err := tr.Trace(ctx, req.Dest, req.Route)
	if err != nil {
		return api.Response{OK: false, Error: err.Error()}
	}
	return api.Response{OK: true, Trace: &res}
}

// send dispatches a chat message via the attached node: a channel broadcast
// when req.Channel is set, otherwise a direct DM to req.Dest. A direct send with
// AckWaitMs > 0 waits that long for an ACK (the connection deadline is extended
// to match).
func (d *Daemon) send(conn net.Conn, req api.Request) api.Response {
	d.mu.RLock()
	s, ok := d.peerSrc.(Sender)
	d.mu.RUnlock()
	if !ok || s == nil {
		return api.Response{OK: false, Error: "no node attached to daemon"}
	}

	if req.Channel != "" {
		if req.AckWaitMs > 0 {
			return api.Response{OK: false, Error: "ack is only supported for direct messages"}
		}
		if err := s.SendChannel(req.Channel, req.Text); err != nil {
			return api.Response{OK: false, Error: err.Error()}
		}
		return api.Response{OK: true}
	}

	wantAck := req.AckWaitMs > 0
	ctx := context.Background()
	cancel := func() {}
	if wantAck {
		wait := time.Duration(req.AckWaitMs) * time.Millisecond
		_ = conn.SetDeadline(time.Now().Add(wait + 5*time.Second))
		ctx, cancel = context.WithTimeout(context.Background(), wait)
	}
	defer cancel()

	res, err := s.SendDirect(ctx, req.Dest, req.Text, wantAck)
	if err != nil {
		return api.Response{OK: false, Error: err.Error()}
	}
	return api.Response{OK: true, Send: &res}
}

func (d *Daemon) status() api.StatusResult {
	return api.StatusResult{
		PID:       os.Getpid(),
		NodeID:    d.identity.NodeID().String(),
		Pubkey:    hex.EncodeToString(d.identity.Pub),
		StartedAt: d.startedAt,
		PeerCount: len(d.Peers()),
		Version:   Version,
	}
}

// Peers returns a snapshot of the current peers, preferring the live node when
// one is attached.
func (d *Daemon) Peers() []api.Peer {
	d.mu.RLock()
	src := d.peerSrc
	d.mu.RUnlock()
	if src != nil {
		return src.Peers()
	}
	d.mu.RLock()
	defer d.mu.RUnlock()
	out := make([]api.Peer, len(d.peers))
	copy(out, d.peers)
	return out
}

// SetPeerSource attaches the running node as the authoritative peer source.
func (d *Daemon) SetPeerSource(src PeerSource) {
	d.mu.Lock()
	d.peerSrc = src
	d.mu.Unlock()
}

// SetPeers replaces the fallback peer registry (used when no node is attached).
func (d *Daemon) SetPeers(peers []api.Peer) {
	d.mu.Lock()
	d.peers = peers
	d.mu.Unlock()
}

func (d *Daemon) writePID() error {
	return os.WriteFile(d.cfg.PIDPath(), []byte(strconv.Itoa(os.Getpid())+"\n"), 0o600)
}
