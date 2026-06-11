package linux

import (
	"fmt"
	"log"
	"sync"

	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/introspect"

	"github.com/bleedge/bleedge/core"
)

const (
	gattServiceIF    = "org.bluez.GattService1"
	gattCharIF       = "org.bluez.GattCharacteristic1"
	gattManagerIF    = "org.bluez.GattManager1"
	gattDescriptorIF = "org.bluez.GattDescriptor1"

	servicePath  = dbus.ObjectPath("/org/bleedge/service0")
	charNodeInfo = dbus.ObjectPath("/org/bleedge/service0/char0")
	charPacketIn = dbus.ObjectPath("/org/bleedge/service0/char1")
	charPacketOut = dbus.ObjectPath("/org/bleedge/service0/char2")

	// Service and characteristic UUIDs
	ServiceUUID    = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a001"
	NodeInfoUUID   = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a002"
	PacketInUUID   = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a003"
	PacketOutUUID  = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a004"
)

// GattServer implements the BLEEdge GATT service over BlueZ D-Bus Application API.
type GattServer struct {
	conn     *dbus.Conn
	adapter     *Adapter
	nodeID      core.NodeID
	pubKey      []byte // 32-byte Ed25519 public key advertised via NODE_INFO
	caps        core.Capabilities
	description string
	name        string
	platform    string
	onFrame     func(frame []byte, sender dbus.Sender)

	mu           sync.Mutex
	notifyConns  map[dbus.Sender]bool // peers subscribed to PACKET_OUT notifications
}

// NewGattServer creates and registers the GATT server. pubKey is the node's
// 32-byte Ed25519 public key; the NodeID is derived as pubKey[:8].
func NewGattServer(adapter *Adapter, pubKey []byte, caps core.Capabilities,
	description, name, platform string, onFrame func(frame []byte, sender dbus.Sender)) *GattServer {
	return &GattServer{
		conn:        adapter.Conn(),
		adapter:     adapter,
		nodeID:      core.NodeIDFromPubKey(pubKey),
		pubKey:      pubKey,
		caps:        caps,
		description: description,
		name:        name,
		platform:    platform,
		onFrame:     onFrame,
		notifyConns: make(map[dbus.Sender]bool),
	}
}

// Register exports GATT objects and registers the application with BlueZ.
func (g *GattServer) Register() error {
	// Export service object
	if err := g.conn.Export(g.serviceProps(), servicePath, gattServiceIF); err != nil {
		return fmt.Errorf("export service: %w", err)
	}

	// Export NODE_INFO (read)
	nodeInfoChar := &nodeInfoCharacteristic{server: g}
	if err := g.conn.Export(nodeInfoChar, charNodeInfo, gattCharIF); err != nil {
		return fmt.Errorf("export node_info char: %w", err)
	}

	// Export PACKET_IN (write)
	packetInChar := &packetInCharacteristic{server: g}
	if err := g.conn.Export(packetInChar, charPacketIn, gattCharIF); err != nil {
		return fmt.Errorf("export packet_in char: %w", err)
	}

	// Export PACKET_OUT (notify)
	packetOutChar := &packetOutCharacteristic{server: g}
	if err := g.conn.Export(packetOutChar, charPacketOut, gattCharIF); err != nil {
		return fmt.Errorf("export packet_out char: %w", err)
	}

	// Export introspection for each object
	for _, path := range []dbus.ObjectPath{servicePath, charNodeInfo, charPacketIn, charPacketOut} {
		node := introspect.Node{Name: string(path)}
		g.conn.Export(introspect.NewIntrospectable(&node), path, //nolint:errcheck
			"org.freedesktop.DBus.Introspectable")
	}

	// Register application with BlueZ GATT manager
	gattMgr := g.conn.Object(bluezService, g.adapter.AdapterPath())
	opts := map[string]dbus.Variant{}
	call := gattMgr.Call(gattManagerIF+".RegisterApplication", 0, dbus.ObjectPath("/org/bleedge"), opts)
	if call.Err != nil {
		return fmt.Errorf("RegisterApplication: %w", call.Err)
	}

	log.Printf("[gatt-server] registered node=%s", g.nodeID)
	return nil
}

// Unregister removes the application from BlueZ.
func (g *GattServer) Unregister() error {
	gattMgr := g.conn.Object(bluezService, g.adapter.AdapterPath())
	call := gattMgr.Call(gattManagerIF+".UnregisterApplication", 0, dbus.ObjectPath("/org/bleedge"))
	return call.Err
}

// NotifyFrame sends a frame to all subscribed PACKET_OUT clients.
func (g *GattServer) NotifyFrame(frame []byte) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if len(g.notifyConns) == 0 {
		return
	}
	// Signal value change on PACKET_OUT characteristic
	g.conn.Emit(charPacketOut, gattCharIF+".ValueUpdated", frame) //nolint:errcheck
}

// nodeInfoValue encodes the NODE_INFO characteristic (see core.EncodeNodeInfo).
func (g *GattServer) nodeInfoValue() []byte {
	return core.EncodeNodeInfo(core.ProtocolVersion, g.pubKey, g.caps, g.description, g.name, g.platform)
}

// serviceProps returns a D-Bus property map for the GattService1 interface.
func (g *GattServer) serviceProps() map[string]dbus.Variant {
	return map[string]dbus.Variant{
		"UUID":    dbus.MakeVariant(ServiceUUID),
		"Primary": dbus.MakeVariant(true),
	}
}

// ---- NODE_INFO characteristic (READ) ----------------------------------------

type nodeInfoCharacteristic struct{ server *GattServer }

func (c *nodeInfoCharacteristic) ReadValue(opts map[string]dbus.Variant) ([]byte, *dbus.Error) {
	return c.server.nodeInfoValue(), nil
}

func (c *nodeInfoCharacteristic) GetAll(iface string) (map[string]dbus.Variant, *dbus.Error) {
	return map[string]dbus.Variant{
		"UUID":    dbus.MakeVariant(NodeInfoUUID),
		"Service": dbus.MakeVariant(servicePath),
		"Flags":   dbus.MakeVariant([]string{"read"}),
		"Value":   dbus.MakeVariant(c.server.nodeInfoValue()),
	}, nil
}

// ---- PACKET_IN characteristic (WRITE) ---------------------------------------

type packetInCharacteristic struct{ server *GattServer }

func (c *packetInCharacteristic) WriteValue(data []byte, opts map[string]dbus.Variant) *dbus.Error {
	sender, _ := opts["sender"].Value().(string)
	log.Printf("[gatt-server] PACKET_IN write %d bytes from %s", len(data), sender)
	if c.server.onFrame != nil {
		c.server.onFrame(data, dbus.Sender(sender))
	}
	return nil
}

func (c *packetInCharacteristic) GetAll(iface string) (map[string]dbus.Variant, *dbus.Error) {
	return map[string]dbus.Variant{
		"UUID":    dbus.MakeVariant(PacketInUUID),
		"Service": dbus.MakeVariant(servicePath),
		"Flags":   dbus.MakeVariant([]string{"write", "write-without-response"}),
	}, nil
}

// ---- PACKET_OUT characteristic (NOTIFY) -------------------------------------

type packetOutCharacteristic struct{ server *GattServer }

func (c *packetOutCharacteristic) StartNotify() *dbus.Error {
	log.Printf("[gatt-server] PACKET_OUT StartNotify")
	return nil
}

func (c *packetOutCharacteristic) StopNotify() *dbus.Error {
	log.Printf("[gatt-server] PACKET_OUT StopNotify")
	return nil
}

func (c *packetOutCharacteristic) GetAll(iface string) (map[string]dbus.Variant, *dbus.Error) {
	return map[string]dbus.Variant{
		"UUID":    dbus.MakeVariant(PacketOutUUID),
		"Service": dbus.MakeVariant(servicePath),
		"Flags":   dbus.MakeVariant([]string{"notify"}),
		"Value":   dbus.MakeVariant([]byte{}),
		"Notifying": dbus.MakeVariant(false),
	}, nil
}

