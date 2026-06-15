// Package api defines the local control protocol spoken between the sp CLI and
// the background daemon over a Unix socket, plus a thin client for it.
//
// The wire format is deliberately simple: one JSON Request object per
// connection followed by one JSON Response object, both newline-terminated.
// This keeps the daemon's control plane trivial to extend (add a method, add a
// result field) while real transports (BLE, MeshCore) are filled in later.
package api

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"time"
)

// Method names for control requests.
const (
	MethodStatus   = "status"
	MethodPeers    = "peers"
	MethodPeer     = "peer"
	MethodTopology = "topology"
	MethodTrace    = "trace"
	MethodSend     = "send"
	MethodModem    = "modem"
)

// Request is a single control call from the CLI to the daemon.
type Request struct {
	Method string `json:"method"`
	// Dest and Route apply to MethodTrace, MethodSend, and MethodPeer: the target
	// NodeID (hex) and, for trace, an optional explicit relay route (hex NodeIDs).
	Dest  string   `json:"dest,omitempty"`
	Route []string `json:"route,omitempty"`
	// Channel and Text apply to MethodSend. A non-empty Channel selects a
	// channel broadcast; otherwise the message is a direct DM to Dest.
	Channel string `json:"channel,omitempty"`
	Text    string `json:"text,omitempty"`
	// AckWaitMs > 0 makes a direct send wait up to that long for an ACK.
	AckWaitMs int64 `json:"ack_wait_ms,omitempty"`
	// Line applies to MethodModem: the raw modem command line to run on the
	// daemon's attached modem (e.g. "INFO", "SEND 08deadbeef").
	Line string `json:"line,omitempty"`
}

// Response is the daemon's reply. Exactly one of the result fields is populated
// according to the request method; Error is set instead when OK is false.
type Response struct {
	OK       bool            `json:"ok"`
	Error    string          `json:"error,omitempty"`
	Status   *StatusResult   `json:"status,omitempty"`
	Peers    []Peer          `json:"peers,omitempty"`
	Peer     *PeerDetail     `json:"peer,omitempty"`
	Topology *TopologyResult `json:"topology,omitempty"`
	Trace    *TraceResult    `json:"trace,omitempty"`
	Send     *SendResult     `json:"send,omitempty"`
	Modem    *ModemResult    `json:"modem,omitempty"`
}

// ModemResult carries the reply lines from a modem command run on the daemon's
// attached modem. Lines includes the terminal OK/ERR line.
type ModemResult struct {
	Lines []string `json:"lines"`
}

// SendResult reports the outcome of a direct send: how it was routed (always) and,
// when an ACK was awaited, whether one arrived and the round-trip time.
type SendResult struct {
	Acked bool   `json:"acked"`
	From  string `json:"from,omitempty"`
	RTTMs int64  `json:"rtt_ms,omitempty"`
	// Routing details, populated on every direct send (verbose output uses these).
	Dest       string   `json:"dest,omitempty"`
	DatagramID string   `json:"datagram_id,omitempty"`
	Route      []string `json:"route,omitempty"`   // source route hops incl. dest; empty when flooded
	Flooded    bool     `json:"flooded,omitempty"` // true when no route was known and we flooded
}

// TraceResult is the outcome of a trace to a destination node.
type TraceResult struct {
	Tag    uint32   `json:"tag"`
	Metric string   `json:"metric"`
	RTTMs  int64    `json:"rtt_ms"`
	Path   []string `json:"path"` // forward relays (hex NodeIDs); empty = direct
}

// StatusResult describes the running daemon.
type StatusResult struct {
	PID       int       `json:"pid"`
	NodeID    string    `json:"node_id"`
	Pubkey    string    `json:"pubkey"`
	StartedAt time.Time `json:"started_at"`
	PeerCount int       `json:"peer_count"`
	Version   string    `json:"version"`
}

// Uptime is the time elapsed since the daemon started.
func (s StatusResult) Uptime() time.Duration {
	return time.Since(s.StartedAt).Round(time.Second)
}

// Peer is one node the daemon knows about — every node learned via signed
// ANNOUNCE plus any directly-linked peer — enriched with that node's latest
// announce data and this node's current route to it. Connected reports whether
// there is a live BLE link right now.
type Peer struct {
	NodeID      string `json:"node_id"`
	Name        string `json:"name,omitempty"`
	Platform    string `json:"platform,omitempty"`
	Description string `json:"description,omitempty"`
	// Connected is true when a BLE link is currently up; Direction is then the
	// link direction (outbound/inbound/in+out) and RSSI the neighbor's signal.
	Connected bool   `json:"connected"`
	Direction string `json:"direction,omitempty"`
	RSSI      int    `json:"rssi,omitempty"`
	// TxPHY/RxPHY are the BLE PHY of the live link (e.g. "1M", "LE Coded").
	TxPHY     string `json:"tx_phy,omitempty"`
	RxPHY     string `json:"rx_phy,omitempty"`
	Relay     bool   `json:"relay"`
	Gateway   bool   `json:"gateway"`
	Neighbors int    `json:"neighbors,omitempty"` // neighbor count from the node's announce
	Hops      int    `json:"hops"`                // route to reach it: 0 = direct, N relays, -1 = no route
	// AnnounceEpoch/AnnounceSeq identify the latest announce seen from this node.
	AnnounceEpoch uint64 `json:"announce_epoch,omitempty"`
	AnnounceSeq   uint32 `json:"announce_seq,omitempty"`
	// LastAnnounceS is seconds since that announce arrived; -1 if none seen yet.
	LastAnnounceS int64 `json:"last_announce_s"`
}

// NeighborDetail is one neighbor a peer advertised in its latest ANNOUNCE, with
// the per-link details carried by a v3 announce (§8.8). For v1/v2 announces only
// ID (and Name, if we know that node) are populated and HasInfo is false.
type NeighborDetail struct {
	NodeID string `json:"node_id"`
	Name   string `json:"name,omitempty"` // display name if that node is also in our topology
	// HasInfo is true when the peer's announce carried v3 per-link details below.
	HasInfo bool `json:"has_info"`
	// RSSI in dBm as the peer measured this link; 0 means "no sample".
	RSSI int `json:"rssi,omitempty"`
	// TxPHY/RxPHY are the BLE PHY of the link (e.g. "1M", "2M", "LE Coded").
	TxPHY string `json:"tx_phy,omitempty"`
	RxPHY string `json:"rx_phy,omitempty"`
	// Direction is which side opened the link, from the peer's view: "outbound" or "inbound".
	Direction string `json:"direction,omitempty"`
	// AgeS is seconds since the peer last received a packet from this neighbor.
	AgeS uint32 `json:"age_s,omitempty"`
}

// PeerDetail is the full view of a single node returned by MethodPeer: the same
// summary fields as Peer plus the public key, any bridged networks, and the
// peer's own neighbor list with per-link details from its latest ANNOUNCE.
type PeerDetail struct {
	Peer
	Pubkey       string           `json:"pubkey,omitempty"`
	Bridges      []string         `json:"bridges,omitempty"`
	NeighborList []NeighborDetail `json:"neighbor_list,omitempty"`
	// NeighborOfList is the inverse of NeighborList: every node that advertises
	// THIS node as one of its own neighbors, carrying the per-link details that
	// node reported about the link (from its v3 announce, or its live link for the
	// local node). It lets you see a link from both ends — RSSI/PHY/direction can
	// differ per side. NodeID is the advertising node; Direction is from its view.
	NeighborOfList []NeighborDetail `json:"neighbor_of_list,omitempty"`
}

// TopologyResult is the mesh graph as the local node sees it: every node it
// knows about (itself plus all nodes learned via ANNOUNCE) and each node's
// advertised neighbors. Self is the local NodeID.
type TopologyResult struct {
	Self  string         `json:"self"`
	Nodes []TopologyNode `json:"nodes"`
}

// TopologyNode is one vertex of the mesh graph and its outgoing adjacency (the
// neighbors that node advertises, or — for the local node — its live links).
type TopologyNode struct {
	NodeID string `json:"node_id"`
	Name   string `json:"name,omitempty"`
	// Self marks the local node; Connected marks a node with a live link to us.
	Self      bool     `json:"self,omitempty"`
	Connected bool     `json:"connected,omitempty"`
	Neighbors []string `json:"neighbors"` // neighbor NodeIDs (hex)
	// LastAnnounceS is seconds since this node's last announce; -1 for the local
	// node and any node known only from a live link.
	LastAnnounceS int64 `json:"last_announce_s"`
}

// Client is a one-shot control connection to the daemon socket.
type Client struct {
	sock    string
	timeout time.Duration
}

// NewClient returns a client targeting the given Unix socket path.
func NewClient(sock string) *Client {
	return &Client{sock: sock, timeout: 2 * time.Second}
}

// Status fetches the daemon's status.
func (c *Client) Status() (*StatusResult, error) {
	resp, err := c.call(Request{Method: MethodStatus})
	if err != nil {
		return nil, err
	}
	if resp.Status == nil {
		return nil, fmt.Errorf("daemon returned no status")
	}
	return resp.Status, nil
}

// Peers fetches the daemon's current peer list.
func (c *Client) Peers() ([]Peer, error) {
	resp, err := c.call(Request{Method: MethodPeers})
	if err != nil {
		return nil, err
	}
	return resp.Peers, nil
}

// Peer fetches the full detail for a single node by NodeID (hex), including its
// advertised neighbor list with per-link details.
func (c *Client) Peer(id string) (*PeerDetail, error) {
	resp, err := c.call(Request{Method: MethodPeer, Dest: id})
	if err != nil {
		return nil, err
	}
	if resp.Peer == nil {
		return nil, fmt.Errorf("daemon returned no peer detail")
	}
	return resp.Peer, nil
}

// Topology fetches the mesh graph (nodes and their advertised neighbors) as the
// local node currently sees it.
func (c *Client) Topology() (*TopologyResult, error) {
	resp, err := c.call(Request{Method: MethodTopology})
	if err != nil {
		return nil, err
	}
	if resp.Topology == nil {
		return nil, fmt.Errorf("daemon returned no topology")
	}
	return resp.Topology, nil
}

// Trace traces the route to dest (hex NodeID), optionally pinned to an explicit
// relay route. It uses a longer timeout since the daemon must reach the
// destination and await the reply, and aborts promptly if ctx is cancelled
// (e.g. Ctrl-C).
func (c *Client) Trace(ctx context.Context, dest string, route []string) (*TraceResult, error) {
	resp, err := c.callCtx(ctx, Request{Method: MethodTrace, Dest: dest, Route: route}, 20*time.Second)
	if err != nil {
		return nil, err
	}
	if resp.Trace == nil {
		return nil, fmt.Errorf("daemon returned no trace result")
	}
	return resp.Trace, nil
}

// SendDirect sends an encrypted direct message to dest (hex NodeID). An optional
// route (relay hops, hex NodeIDs) forces an explicit source route instead of the
// auto-selected one. It returns the routing details the daemon reported.
func (c *Client) SendDirect(dest, text string, route []string) (*SendResult, error) {
	resp, err := c.call(Request{Method: MethodSend, Dest: dest, Text: text, Route: route})
	if err != nil {
		return nil, err
	}
	if resp.Send == nil {
		return &SendResult{}, nil
	}
	return resp.Send, nil
}

// SendDirectAck sends a direct message (optionally over an explicit route) and
// waits up to wait for an ACK. The result reports the routing details plus whether
// the ACK arrived (Acked) and, if so, the round-trip time. It aborts promptly if
// ctx is cancelled.
func (c *Client) SendDirectAck(ctx context.Context, dest, text string, route []string, wait time.Duration) (*SendResult, error) {
	req := Request{Method: MethodSend, Dest: dest, Text: text, Route: route, AckWaitMs: wait.Milliseconds()}
	resp, err := c.callCtx(ctx, req, wait+5*time.Second)
	if err != nil {
		return nil, err
	}
	if resp.Send == nil {
		return &SendResult{}, nil
	}
	return resp.Send, nil
}

// SendChannel broadcasts a message on the named channel ("Public" or any name).
func (c *Client) SendChannel(channel, text string) error {
	_, err := c.call(Request{Method: MethodSend, Channel: channel, Text: text})
	return err
}

// Modem runs a raw modem command line on the daemon's attached modem and
// returns the reply lines (including the terminal OK/ERR).
func (c *Client) Modem(line string) ([]string, error) {
	resp, err := c.callCtx(context.Background(), Request{Method: MethodModem, Line: line}, 5*time.Second)
	if err != nil {
		return nil, err
	}
	if resp.Modem == nil {
		return nil, nil
	}
	return resp.Modem.Lines, nil
}

// Ping reports whether a daemon is listening and answering on the socket.
func (c *Client) Ping() bool {
	_, err := c.Status()
	return err == nil
}

func (c *Client) call(req Request) (*Response, error) {
	return c.callCtx(context.Background(), req, c.timeout)
}

// callCtx performs one request/response. It aborts promptly when ctx is
// cancelled by closing the connection, which unblocks the otherwise-blocking
// socket read (a slow trace would otherwise hang until the deadline).
func (c *Client) callCtx(ctx context.Context, req Request, timeout time.Duration) (*Response, error) {
	d := net.Dialer{Timeout: c.timeout}
	conn, err := d.DialContext(ctx, "unix", c.sock)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if timeout > 0 {
		_ = conn.SetDeadline(time.Now().Add(timeout))
	}

	// Closing the conn on cancellation makes the blocking Encode/Decode below
	// return immediately; ctx.Err() then takes precedence over the I/O error.
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-ctx.Done():
			conn.Close()
		case <-stop:
		}
	}()

	if err := json.NewEncoder(conn).Encode(req); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("send request: %w", err)
	}
	var resp Response
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&resp); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("read response: %w", err)
	}
	if !resp.OK {
		return nil, fmt.Errorf("daemon error: %s", resp.Error)
	}
	return &resp, nil
}
