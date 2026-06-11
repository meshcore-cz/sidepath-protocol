package linux

import (
	"context"
	"fmt"
	"log"

	"github.com/godbus/dbus/v5"
	"github.com/godbus/dbus/v5/introspect"
	"github.com/godbus/dbus/v5/prop"

	"github.com/bleedge/bleedge/core"
)

const (
	leAdvertisementIface   = "org.bluez.LEAdvertisement1"
	leAdvertisingManagerIF = "org.bluez.LEAdvertisingManager1"

	advertisementPath = dbus.ObjectPath("/org/bleedge/advertisement0")
)

// Advertiser registers a BlueZ LEAdvertisement1 D-Bus object and starts advertising.
type Advertiser struct {
	adapter *Adapter
	nodeID  core.NodeID
	conn    *dbus.Conn
}

// NewAdvertiser creates an advertiser for the given adapter and node ID.
func NewAdvertiser(adapter *Adapter, nodeID core.NodeID) *Advertiser {
	return &Advertiser{
		adapter: adapter,
		nodeID:  nodeID,
		conn:    adapter.Conn(),
	}
}

// advertisement is the D-Bus object exported as org.bluez.LEAdvertisement1.
type advertisement struct {
	nodeID core.NodeID
}

// Start registers and activates the BLE advertisement.
func (a *Advertiser) Start(ctx context.Context) error {
	adv := &advertisement{nodeID: a.nodeID}

	// Export the advertisement object onto the bus
	if err := a.conn.Export(adv, advertisementPath, leAdvertisementIface); err != nil {
		return fmt.Errorf("export advertisement: %w", err)
	}

	// Export introspection so BlueZ can inspect the object
	node := &introspect.Node{
		Interfaces: []introspect.Interface{
			introspect.IntrospectData,
			prop.IntrospectData,
			{
				Name: leAdvertisementIface,
				Methods: []introspect.Method{
					{Name: "Release"},
				},
				Properties: []introspect.Property{
					{Name: "Type", Type: "s", Access: "read"},
					{Name: "ServiceUUIDs", Type: "as", Access: "read"},
					{Name: "LocalName", Type: "s", Access: "read"},
					{Name: "ManufacturerData", Type: "a{qv}", Access: "read"},
					{Name: "Discoverable", Type: "b", Access: "read"},
					{Name: "Duration", Type: "q", Access: "read"},
				},
			},
		},
	}
	a.conn.Export(introspect.NewIntrospectable(node), advertisementPath, //nolint:errcheck
		"org.freedesktop.DBus.Introspectable")

	// Register advertisement with the adapter
	mgr := a.conn.Object(bluezService, a.adapter.AdapterPath())
	opts := map[string]dbus.Variant{}
	call := mgr.CallWithContext(ctx, leAdvertisingManagerIF+".RegisterAdvertisement", 0,
		advertisementPath, opts)
	if call.Err != nil {
		return fmt.Errorf("RegisterAdvertisement: %w", call.Err)
	}

	log.Printf("[advertiser] advertising started node=%s", a.nodeID)
	return nil
}

// Stop unregisters the advertisement.
func (a *Advertiser) Stop(ctx context.Context) error {
	mgr := a.conn.Object(bluezService, a.adapter.AdapterPath())
	call := mgr.CallWithContext(ctx, leAdvertisingManagerIF+".UnregisterAdvertisement", 0, advertisementPath)
	if call.Err != nil {
		return fmt.Errorf("UnregisterAdvertisement: %w", call.Err)
	}
	a.conn.Export(nil, advertisementPath, leAdvertisementIface) //nolint:errcheck
	log.Printf("[advertiser] advertising stopped")
	return nil
}

// D-Bus method handlers for org.bluez.LEAdvertisement1

// Release is called by BlueZ when the advertisement is unregistered.
func (adv *advertisement) Release() *dbus.Error {
	log.Printf("[advertiser] Release called")
	return nil
}

// GetProperties returns all advertisement properties as a D-Bus property map.
// BlueZ calls org.freedesktop.DBus.Properties.GetAll to populate the advertisement.
func (adv *advertisement) GetAll(iface string) (map[string]dbus.Variant, *dbus.Error) {
	if iface != leAdvertisementIface {
		return nil, dbus.NewError("org.freedesktop.DBus.Error.InvalidArgs", nil)
	}
	return map[string]dbus.Variant{
		"Type":         dbus.MakeVariant("peripheral"),
		"ServiceUUIDs": dbus.MakeVariant([]string{BLEEdgeServiceUUID}),
		"LocalName":    dbus.MakeVariant("BLEEdge-" + adv.nodeID.String()[:8]),
		"Discoverable": dbus.MakeVariant(true),
		// Manufacturer data: [0xBE, 0xED] = custom BLEEdge company ID followed by node ID
		"ManufacturerData": dbus.MakeVariant(map[uint16]dbus.Variant{
			0xBEED: dbus.MakeVariant(adv.nodeID[:]),
		}),
		"Duration": dbus.MakeVariant(uint16(0)), // indefinite
	}, nil
}
