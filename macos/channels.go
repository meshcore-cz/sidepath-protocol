//go:build darwin

package macos

import (
	"encoding/hex"
	"sort"
	"strings"
	"time"

	"github.com/bleedge/bleedge/core"
)

// Channel messaging mirrors the Android chat app: the node keeps a registry of
// joined MeshCore-compatible channels (see core/channel.go), broadcasts outbound
// messages as GRP_TXT payloads, and decodes inbound ones against every joined
// channel whose 1-byte hash matches. The Public channel is auto-joined in New.

// Channel is a joined group channel: its 16-byte PSK, display name, and routing hash.
type Channel struct {
	Secret []byte // 16-byte PSK
	Name   string
	Hash   byte // core.ChannelHash(Secret); inbound packets are matched on this
}

// JoinChannel registers (or renames) a channel by its raw 16-byte PSK and returns it.
func (n *Node) JoinChannel(secret []byte, name string) *Channel {
	s := make([]byte, core.ChannelSecretLen)
	copy(s, secret)
	ch := &Channel{Secret: s, Name: name, Hash: core.ChannelHash(s)}
	n.mu.Lock()
	n.channels[hex.EncodeToString(s)] = ch
	n.mu.Unlock()
	return ch
}

// JoinPublicChannel joins MeshCore's well-known Public channel (hash 0x11).
func (n *Node) JoinPublicChannel() *Channel {
	return n.JoinChannel(core.ChannelPublicPSK, "Public")
}

// JoinNamedChannel joins a "public hash" channel whose PSK is SHA-256(name)[:16].
func (n *Node) JoinNamedChannel(name string) *Channel {
	return n.JoinChannel(core.DeriveChannelSecret(name), name)
}

// ChannelByName returns a joined channel by its display name (case-insensitive),
// or nil if none matches.
func (n *Node) ChannelByName(name string) *Channel {
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, ch := range n.channels {
		if strings.EqualFold(ch.Name, name) {
			return ch
		}
	}
	return nil
}

// LeaveChannel removes a joined channel by its PSK hex. Returns false if not joined.
func (n *Node) LeaveChannel(pskHex string) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	if _, ok := n.channels[pskHex]; !ok {
		return false
	}
	delete(n.channels, pskHex)
	return true
}

// Channels returns the joined channels, sorted by name.
func (n *Node) Channels() []Channel {
	n.mu.Lock()
	out := make([]Channel, 0, len(n.channels))
	for _, ch := range n.channels {
		out = append(out, *ch)
	}
	n.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// SendToChannel seals text into a GRP_TXT payload (sender name = this node's
// description) and broadcasts it to the whole mesh. No per-message ACK.
func (n *Node) SendToChannel(secret []byte, text string) error {
	label := n.name
	if label == "" {
		label = n.description
	}
	payload, err := core.BuildChannelText(secret, label, text, uint32(time.Now().Unix()))
	if err != nil {
		return err
	}
	dg := n.router.NewBroadcast(core.ProtocolBLEEdgeChat, payload, 4)
	return n.transmit(dg)
}

// ChannelInbound is a decoded inbound channel message together with the channel
// it arrived on.
type ChannelInbound struct {
	Channel string // channel display name
	core.ChannelMessage
}

// DecodeChannel tries to decode a GRP_TXT payload against every joined channel
// whose hash matches; the MAC disambiguates which PSK actually decrypts it.
func (n *Node) DecodeChannel(payload []byte) (ChannelInbound, bool) {
	if len(payload) == 0 {
		return ChannelInbound{}, false
	}
	cp := core.ChannelPayloadFromChat(payload)
	if len(cp) == 0 {
		return ChannelInbound{}, false
	}
	hash := cp[0]
	n.mu.Lock()
	candidates := make([]*Channel, 0, 2)
	for _, ch := range n.channels {
		if ch.Hash == hash {
			candidates = append(candidates, ch)
		}
	}
	n.mu.Unlock()
	for _, ch := range candidates {
		if msg, ok := core.OpenChannel(ch.Secret, cp); ok {
			return ChannelInbound{Channel: ch.Name, ChannelMessage: msg}, true
		}
	}
	return ChannelInbound{}, false
}
