//go:build darwin

package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/bleedge/bleedge/core"
	blenode "github.com/bleedge/bleedge/macos"
)

// bot bridges the BLEEdge node to a user script run by the Bun JS/TS runtime.
//
// Communication is newline-delimited JSON over the Bun process's stdin/stdout:
//
//	Go → Bun (events, on Bun's stdin):
//	  {"type":"ready","self":{"node":"<hex>","name":"<desc>","channels":["Public",...]}}
//	  {"type":"message","from":"<hex>","name":"<desc>","text":"...","channel":bool,
//	      "channelName":"<name>","sender":"<name>","ts":<unixMillis>}   // channelName/sender only for channel msgs
//	  {"type":"stats","peers":N,"neighbors":N,"topology":N,"node":"<hex>","name":"<desc>"}
//
//	Bun → Go (commands, on Bun's stdout):
//	  {"type":"reply","to":"<hex>","text":"..."}            // E2E-encrypted DM back to a sender
//	  {"type":"broadcast","text":"...","channel":"<name>"}  // channel msg; channel optional, defaults to primary
//	  {"type":"query","what":"stats"}                       // ask Go for live mesh stats
//	  {"type":"log","text":"..."}                           // diagnostic, printed to stderr
//
// Bun scripts must keep stdout clean (protocol JSON only); use console.error for logs.
type bot struct {
	node      *blenode.Node
	mu        sync.Mutex
	stdin     io.Writer
	logf      func(string)           // where bot diagnostics go (TUI log when interactive, else stderr)
	pubByNode map[core.NodeID][]byte // sender Ed25519 pubkeys learned from inbound DMs, for replies
	channels  []*blenode.Channel     // channels this bot listens/broadcasts on; channels[0] is the default
}

func startBot(ctx context.Context, node *blenode.Node, bunPath, script string, channelNames []string, logf func(string)) (*bot, error) {
	cmd := exec.CommandContext(ctx, bunPath, "run", script)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	// Bun's stderr (console.error from the script) goes line-by-line to the same sink as
	// our own bot diagnostics, so nothing leaks to the terminal and corrupts the TUI.
	cmd.Stderr = lineWriter{emit: logf}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start bun (%s): %w", bunPath, err)
	}

	// Join the configured channels (default: Public). Public is auto-joined by
	// the node; "Public" is recognised specially, anything else is a named channel.
	b := &bot{node: node, stdin: stdin, logf: logf, pubByNode: make(map[core.NodeID][]byte)}
	for _, name := range channelNames {
		var ch *blenode.Channel
		if name == "Public" {
			ch = node.JoinPublicChannel()
		} else {
			ch = node.JoinNamedChannel(name)
		}
		b.channels = append(b.channels, ch)
	}

	go b.readLoop(stdout)
	go func() {
		_ = cmd.Wait()
		logf("bot: bun process exited")
	}()

	chNames := make([]string, len(b.channels))
	for i, ch := range b.channels {
		chNames[i] = ch.Name
	}
	b.send(map[string]any{
		"type": "ready",
		"self": map[string]any{
			"node":     node.NodeID().String(),
			"name":     node.Name(),
			"channels": chNames,
		},
	})
	return b, nil
}

// defaultChannel returns the bot's primary channel (the first configured one).
func (b *bot) defaultChannel() *blenode.Channel {
	if len(b.channels) == 0 {
		return nil
	}
	return b.channels[0]
}

// channelByName returns a configured channel by name (case-insensitive), or the
// default channel when name is empty / unknown.
func (b *bot) channelByName(name string) *blenode.Channel {
	if name != "" {
		for _, ch := range b.channels {
			if strings.EqualFold(ch.Name, name) {
				return ch
			}
		}
	}
	return b.defaultChannel()
}

func (b *bot) send(v any) {
	data, err := json.Marshal(v)
	if err != nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	fmt.Fprintf(b.stdin, "%s\n", data)
}

// onIncoming is wired to the node's OnMessage callback.
func (b *bot) onIncoming(from core.NodeID, ptype core.PayloadType, payload []byte) {
	msg := map[string]any{
		"type": "message",
		"from": from.String(),
		"name": b.node.NameFor(from),
		"ts":   time.Now().UnixMilli(),
	}
	switch ptype {
	case core.PayloadTypeChatEncrypted:
		t, ok := core.OpenChat(payload, b.node.Identity())
		if !ok {
			return // not for us / undecryptable
		}
		msg["text"] = t
		msg["channel"] = false
		if pub := core.ChatEnvelopeSenderPub(payload); len(pub) == 32 {
			b.mu.Lock()
			b.pubByNode[from] = pub
			b.mu.Unlock()
		}
	case core.PayloadTypeChannel:
		in, ok := b.node.DecodeChannel(payload)
		if !ok {
			return // not on a channel we've joined
		}
		msg["text"] = in.Text
		msg["channel"] = true
		msg["channelName"] = in.Channel
		msg["sender"] = in.Sender
	case core.PayloadTypeTextTest:
		msg["text"] = string(payload)
		msg["channel"] = true
	default:
		return
	}
	b.send(msg)
}

type botCommand struct {
	Type    string `json:"type"`
	To      string `json:"to"`
	Text    string `json:"text"`
	What    string `json:"what"`
	Channel string `json:"channel"` // optional channel name for "broadcast"; defaults to the bot's primary channel
}

func (b *bot) readLoop(r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var c botCommand
		if err := json.Unmarshal(line, &c); err != nil {
			b.logf(fmt.Sprintf("bot: bad command json: %v", err))
			continue
		}
		switch c.Type {
		case "reply":
			id, err := core.ParseNodeID(c.To)
			if err != nil {
				b.logf(fmt.Sprintf("bot reply: bad 'to' %q: %v", c.To, err))
				continue
			}
			b.mu.Lock()
			pub := b.pubByNode[id]
			b.mu.Unlock()
			if len(pub) != 32 {
				pub = b.node.PublicKeyFor(id) // fall back to the topology (signed ANNOUNCE)
			}
			if len(pub) != 32 {
				b.logf(fmt.Sprintf("bot reply: no public key known for %s", c.To))
				continue
			}
			if err := b.node.SendChatTo(id, pub, c.Text); err != nil {
				b.logf(fmt.Sprintf("bot reply send: %v", err))
			}
		case "broadcast":
			// Broadcasts go to the named channel, or the bot's primary channel by default.
			ch := b.channelByName(c.Channel)
			if ch == nil {
				b.logf("bot broadcast: bot has no channels to broadcast on")
				continue
			}
			if err := b.node.SendToChannel(ch.Secret, c.Text); err != nil {
				b.logf(fmt.Sprintf("bot broadcast: %v", err))
			}
		case "typing":
			id, err := core.ParseNodeID(c.To)
			if err != nil {
				b.logf(fmt.Sprintf("bot typing: bad 'to' %q: %v", c.To, err))
				continue
			}
			if err := b.node.SendTyping(id); err != nil {
				b.logf(fmt.Sprintf("bot typing send: %v", err))
			}
		case "query":
			if c.What == "stats" {
				b.send(map[string]any{
					"type":      "stats",
					"peers":     len(b.node.ConnectedPeers()),
					"neighbors": len(b.node.Neighbors()),
					"topology":  len(b.node.Topology()),
					"node":      b.node.NodeID().String(),
					"name":      b.node.Name(),
				})
			}
		case "log":
			b.logf(fmt.Sprintf("bot: %s", c.Text))
		default:
			b.logf(fmt.Sprintf("bot: unknown command %q", c.Type))
		}
	}
}
