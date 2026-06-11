package linux

import (
	"github.com/bleedge/bleedge/core"
)

// BLEPeerLink implements core.PeerLink using a GATT client connection.
type BLEPeerLink struct {
	peerID     core.NodeID
	txPHY      core.PHY
	rxPHY      core.PHY
	rssi       int
	devicePath string
	writeFn    func(data []byte) error
}

// NewBLEPeerLink creates a PeerLink from a connected GattClient.
func NewBLEPeerLink(client *GattClient) *BLEPeerLink {
	return &BLEPeerLink{
		peerID:     client.NodeID(),
		txPHY:      client.TxPHY(),
		rxPHY:      client.RxPHY(),
		rssi:       client.RSSI(),
		devicePath: string(client.devicePath),
		writeFn:    client.SendFrame,
	}
}

// PeerID returns the remote node's ID.
func (l *BLEPeerLink) PeerID() core.NodeID { return l.peerID }

// TxPHY returns the TX PHY currently in use.
func (l *BLEPeerLink) TxPHY() core.PHY { return l.txPHY }

// RxPHY returns the RX PHY currently in use.
func (l *BLEPeerLink) RxPHY() core.PHY { return l.rxPHY }

// RSSI returns the last known RSSI in dBm.
func (l *BLEPeerLink) RSSI() int { return l.rssi }

// SendFrame transmits a raw GATT frame to the peer's PACKET_IN characteristic.
func (l *BLEPeerLink) SendFrame(frame []byte) error {
	return l.writeFn(frame)
}

// UpdatePHY updates the stored PHY values (called after PHY negotiation completes).
func (l *BLEPeerLink) UpdatePHY(tx, rx core.PHY) {
	l.txPHY = tx
	l.rxPHY = rx
}

// UpdateRSSI refreshes the stored RSSI value.
func (l *BLEPeerLink) UpdateRSSI(rssi int) {
	l.rssi = rssi
}
