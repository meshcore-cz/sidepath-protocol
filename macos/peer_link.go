//go:build darwin

package macos

import (
	"time"

	"github.com/meshcore-cz/sidepath-protocol/core"
)

// MacPeerLink implements core.PeerLink for an outgoing connection owned by the BLE helper. Frames
// are sent by writing the peer's PACKET_IN characteristic via a helper command keyed by [addr].
type MacPeerLink struct {
	peerID      core.NodeID
	addr        string
	helper      *bleHelper
	mtu         int
	txPHY       core.PHY
	rxPHY       core.PHY
	rssi        int
	connectedAt time.Time
}

func (l *MacPeerLink) PeerID() core.NodeID { return l.peerID }
func (l *MacPeerLink) TxPHY() core.PHY     { return l.txPHY }
func (l *MacPeerLink) RxPHY() core.PHY     { return l.rxPHY }
func (l *MacPeerLink) RSSI() int           { return l.rssi }

// sendFrame writes a raw GATT frame to PACKET_IN with response so CoreBluetooth reports
// didWriteValueFor and the helper can emit a per-frame link_sample.
func (l *MacPeerLink) sendFrame(frame []byte) error {
	l.helper.sendCentral(l.addr, frame, true)
	return nil
}

func (l *MacPeerLink) sendFrameReliable(frame []byte) error {
	l.helper.sendCentral(l.addr, frame, true)
	return nil
}

// SendFrame implements core.PeerLink.
func (l *MacPeerLink) SendFrame(frame []byte) error {
	return l.sendFrame(frame)
}
