//go:build darwin

package macos

import (
	"github.com/burningtree/bleedge/core"
	"github.com/go-ble/ble"
)

// MacPeerLink implements core.PeerLink for a go-ble Client connection.
type MacPeerLink struct {
	peerID core.NodeID
	addr   string
	cln    ble.Client
	piChar *ble.Characteristic
	mtu    int
	txPHY  core.PHY
	rxPHY  core.PHY
	rssi   int
}

func (l *MacPeerLink) PeerID() core.NodeID { return l.peerID }
func (l *MacPeerLink) TxPHY() core.PHY     { return l.txPHY }
func (l *MacPeerLink) RxPHY() core.PHY     { return l.rxPHY }
func (l *MacPeerLink) RSSI() int           { return l.rssi }

// sendFrame writes a raw GATT frame to PACKET_IN (write without response).
func (l *MacPeerLink) sendFrame(frame []byte) error {
	return l.cln.WriteCharacteristic(l.piChar, frame, true /* noRsp */)
}

func (l *MacPeerLink) sendFrameReliable(frame []byte) error {
	return l.cln.WriteCharacteristic(l.piChar, frame, false /* with response */)
}

// SendFrame implements core.PeerLink — fragments the payload and sends each frame.
func (l *MacPeerLink) SendFrame(frame []byte) error {
	return l.sendFrame(frame)
}
