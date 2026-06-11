package linux

import (
	"context"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/godbus/dbus/v5"

	"github.com/bleedge/bleedge/core"
)

// GattClient connects to a remote BLEEdge peer and interacts with its GATT service.
type GattClient struct {
	conn       *dbus.Conn
	adapter    *Adapter
	devicePath dbus.ObjectPath

	nodeID    core.NodeID // discovered from NODE_INFO (= pubKey[:8])
	pubKey    []byte      // 32-byte Ed25519 public key from NODE_INFO
	caps      core.Capabilities
	txPHY     core.PHY
	rxPHY     core.PHY
	rssi      int16

	charNodeInfo  dbus.ObjectPath
	charPacketIn  dbus.ObjectPath
	charPacketOut dbus.ObjectPath

	onFrame func(data []byte)
	stopCh  chan struct{}
}

// NewGattClient creates a client for the device at devicePath.
func NewGattClient(adapter *Adapter, devicePath dbus.ObjectPath, onFrame func(data []byte)) *GattClient {
	return &GattClient{
		conn:       adapter.Conn(),
		adapter:    adapter,
		devicePath: devicePath,
		onFrame:    onFrame,
		stopCh:     make(chan struct{}),
		txPHY:      core.PHYUnknown,
		rxPHY:      core.PHYUnknown,
	}
}

// Connect opens the GATT connection, discovers services, subscribes to notifications.
func (c *GattClient) Connect(ctx context.Context) error {
	dev := c.conn.Object(bluezService, c.devicePath)

	// Connect to the device
	log.Printf("[gatt-client] connecting to %s", c.devicePath)
	call := dev.CallWithContext(ctx, deviceIface+".Connect", 0)
	if call.Err != nil {
		return fmt.Errorf("Connect: %w", call.Err)
	}

	// Wait for services to be resolved (BlueZ sets ServicesResolved=true)
	if err := c.waitServicesResolved(ctx, dev); err != nil {
		return err
	}

	// Discover BLEEdge characteristics
	if err := c.discoverChars(ctx); err != nil {
		return fmt.Errorf("discover chars: %w", err)
	}

	// Read NODE_INFO
	if err := c.readNodeInfo(ctx); err != nil {
		log.Printf("[gatt-client] read node info: %v", err)
	}

	// Subscribe to PACKET_OUT
	if err := c.subscribePacketOut(ctx); err != nil {
		log.Printf("[gatt-client] subscribe packet_out: %v", err)
	}

	log.Printf("[gatt-client] connected peer=%s tx=%s rx=%s", c.nodeID, c.txPHY, c.rxPHY)
	return nil
}

// Disconnect closes the connection.
func (c *GattClient) Disconnect(ctx context.Context) error {
	close(c.stopCh)
	dev := c.conn.Object(bluezService, c.devicePath)
	call := dev.CallWithContext(ctx, deviceIface+".Disconnect", 0)
	return call.Err
}

// SendFrame writes a raw frame to the PACKET_IN characteristic.
func (c *GattClient) SendFrame(frame []byte) error {
	if c.charPacketIn == "" {
		return fmt.Errorf("PACKET_IN characteristic not found")
	}
	char := c.conn.Object(bluezService, c.charPacketIn)
	opts := map[string]dbus.Variant{
		"type": dbus.MakeVariant("command"), // write without response
	}
	call := char.Call(gattCharIF+".WriteValue", 0, frame, opts)
	return call.Err
}

// NodeID returns the remote node's ID (read from NODE_INFO).
func (c *GattClient) NodeID() core.NodeID { return c.nodeID }

// TxPHY returns the current TX PHY.
func (c *GattClient) TxPHY() core.PHY { return c.txPHY }

// RxPHY returns the current RX PHY.
func (c *GattClient) RxPHY() core.PHY { return c.rxPHY }

// RSSI returns the last known RSSI.
func (c *GattClient) RSSI() int { return int(c.rssi) }

// waitServicesResolved polls BlueZ until ServicesResolved becomes true.
func (c *GattClient) waitServicesResolved(ctx context.Context, dev dbus.BusObject) error {
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		var v dbus.Variant
		err := dev.Call(propertiesIF+".Get", 0, deviceIface, "ServicesResolved").Store(&v)
		if err == nil {
			if resolved, ok := v.Value().(bool); ok && resolved {
				return nil
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(500 * time.Millisecond):
		}
	}
	return fmt.Errorf("timeout waiting for ServicesResolved")
}

// discoverChars finds the BLEEdge characteristics under the connected device.
func (c *GattClient) discoverChars(ctx context.Context) error {
	obj := c.conn.Object(bluezService, "/")
	var managed map[dbus.ObjectPath]map[string]map[string]dbus.Variant
	if err := obj.CallWithContext(ctx, objectManagerIF+".GetManagedObjects", 0).Store(&managed); err != nil {
		return err
	}

	prefix := string(c.devicePath) + "/"
	for path, ifaces := range managed {
		if !strings.HasPrefix(string(path), prefix) {
			continue
		}
		props, ok := ifaces[gattCharIF]
		if !ok {
			continue
		}
		uuidV, ok := props["UUID"]
		if !ok {
			continue
		}
		uuid, _ := uuidV.Value().(string)
		switch strings.ToLower(uuid) {
		case NodeInfoUUID:
			c.charNodeInfo = path
		case PacketInUUID:
			c.charPacketIn = path
		case PacketOutUUID:
			c.charPacketOut = path
		}
	}

	if c.charPacketIn == "" {
		return fmt.Errorf("BLEEdge PACKET_IN characteristic not found under %s", c.devicePath)
	}
	log.Printf("[gatt-client] discovered chars: node_info=%s packet_in=%s packet_out=%s",
		c.charNodeInfo, c.charPacketIn, c.charPacketOut)
	return nil
}

// readNodeInfo reads and parses the NODE_INFO characteristic.
// Format: version(1) + pubkey(32) + caps(1) = 34 bytes
func (c *GattClient) readNodeInfo(ctx context.Context) error {
	if c.charNodeInfo == "" {
		return fmt.Errorf("NODE_INFO characteristic not found")
	}
	char := c.conn.Object(bluezService, c.charNodeInfo)
	opts := map[string]dbus.Variant{}
	var data []byte
	if err := char.CallWithContext(ctx, gattCharIF+".ReadValue", 0, opts).Store(&data); err != nil {
		return err
	}
	if len(data) < 34 {
		return fmt.Errorf("node info too short: %d bytes", len(data))
	}
	c.pubKey = append([]byte(nil), data[1:33]...)
	c.nodeID = core.NodeIDFromPubKey(c.pubKey)
	c.caps = core.Capabilities(data[33])
	log.Printf("[gatt-client] node_info: peer=%s caps=0x%02x", c.nodeID, c.caps)
	return nil
}

// subscribePacketOut enables notifications on PACKET_OUT and starts watching signals.
func (c *GattClient) subscribePacketOut(ctx context.Context) error {
	if c.charPacketOut == "" {
		return fmt.Errorf("PACKET_OUT characteristic not found")
	}
	char := c.conn.Object(bluezService, c.charPacketOut)
	call := char.CallWithContext(ctx, gattCharIF+".StartNotify", 0)
	if call.Err != nil {
		return call.Err
	}

	// Subscribe to PropertiesChanged for this characteristic
	c.conn.BusObject().Call( //nolint:errcheck
		"org.freedesktop.DBus.AddMatch", 0,
		fmt.Sprintf("type='signal',sender='org.bluez',path='%s',interface='org.freedesktop.DBus.Properties',member='PropertiesChanged'",
			string(c.charPacketOut)),
	)

	sigCh := make(chan *dbus.Signal, 64)
	c.conn.Signal(sigCh)

	go c.watchNotifications(ctx, sigCh)
	return nil
}

func (c *GattClient) watchNotifications(ctx context.Context, sigCh <-chan *dbus.Signal) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stopCh:
			return
		case sig, ok := <-sigCh:
			if !ok {
				return
			}
			if sig.Path != c.charPacketOut {
				continue
			}
			if sig.Name != "org.freedesktop.DBus.Properties.PropertiesChanged" {
				continue
			}
			if len(sig.Body) < 2 {
				continue
			}
			changed, ok := sig.Body[1].(map[string]dbus.Variant)
			if !ok {
				continue
			}
			v, ok := changed["Value"]
			if !ok {
				continue
			}
			data, ok := v.Value().([]byte)
			if !ok {
				continue
			}
			if c.onFrame != nil {
				c.onFrame(data)
			}
		}
	}
}
