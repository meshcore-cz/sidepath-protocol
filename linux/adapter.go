// Package linux provides BlueZ D-Bus bindings for BLEEdge on Linux.
package linux

import (
	"context"
	"fmt"
	"log"
	"os/exec"
	"strings"

	"github.com/godbus/dbus/v5"
)

const (
	bluezService    = "org.bluez"
	adapterIface    = "org.bluez.Adapter1"
	objectManagerIF = "org.freedesktop.DBus.ObjectManager"
	propertiesIF    = "org.freedesktop.DBus.Properties"
)

// Adapter wraps a BlueZ HCI adapter accessed via D-Bus.
type Adapter struct {
	conn        *dbus.Conn
	adapterPath dbus.ObjectPath
	adapterName string // e.g. "hci0"

	CodedPHYSupported bool
}

// NewAdapter discovers the BlueZ adapter via D-Bus ObjectManager.
// adapterName can be "hci0", "hci1", etc.
func NewAdapter(adapterName string) (*Adapter, error) {
	conn, err := dbus.SystemBus()
	if err != nil {
		return nil, fmt.Errorf("connect system dbus: %w", err)
	}

	path := dbus.ObjectPath("/org/bluez/" + adapterName)

	// Verify the adapter object exists
	obj := conn.Object(bluezService, "/")
	var managed map[dbus.ObjectPath]map[string]map[string]dbus.Variant
	err = obj.Call(objectManagerIF+".GetManagedObjects", 0).Store(&managed)
	if err != nil {
		return nil, fmt.Errorf("GetManagedObjects: %w", err)
	}

	if _, ok := managed[path]; !ok {
		// Try to find any adapter
		for p, ifaces := range managed {
			if _, hasAdapter := ifaces[adapterIface]; hasAdapter {
				path = p
				parts := strings.Split(string(p), "/")
				adapterName = parts[len(parts)-1]
				break
			}
		}
		if path == "" {
			return nil, fmt.Errorf("no bluetooth adapter found")
		}
	}

	a := &Adapter{
		conn:        conn,
		adapterPath: path,
		adapterName: adapterName,
	}

	a.CodedPHYSupported = a.checkCodedPHYSupport()
	return a, nil
}

// checkCodedPHYSupport uses btmgmt to determine if LE Coded PHY is supported.
func (a *Adapter) checkCodedPHYSupport() bool {
	out, err := exec.Command("btmgmt", "info").Output()
	if err != nil {
		log.Printf("btmgmt info: %v (assuming no coded PHY)", err)
		return false
	}
	lower := strings.ToLower(string(out))
	// Look for "le coded phy" or "coded phy" in features list
	return strings.Contains(lower, "le coded phy") || strings.Contains(lower, "coded phy")
}

// PowerOn ensures the adapter is powered on.
func (a *Adapter) PowerOn(ctx context.Context) error {
	obj := a.conn.Object(bluezService, a.adapterPath)
	call := obj.CallWithContext(ctx, propertiesIF+".Set", 0,
		adapterIface, "Powered", dbus.MakeVariant(true))
	return call.Err
}

// SetDiscoveryFilter sets BLE scan filter to prefer extended scanning.
func (a *Adapter) SetDiscoveryFilter(ctx context.Context) error {
	obj := a.conn.Object(bluezService, a.adapterPath)
	filter := map[string]dbus.Variant{
		"Transport": dbus.MakeVariant("le"),
		"RSSI":      dbus.MakeVariant(int16(-100)),
		"DuplicateData": dbus.MakeVariant(false),
	}
	call := obj.CallWithContext(ctx, adapterIface+".SetDiscoveryFilter", 0, filter)
	return call.Err
}

// StartDiscovery starts BLE scanning.
func (a *Adapter) StartDiscovery(ctx context.Context) error {
	obj := a.conn.Object(bluezService, a.adapterPath)
	call := obj.CallWithContext(ctx, adapterIface+".StartDiscovery", 0)
	return call.Err
}

// StopDiscovery stops BLE scanning.
func (a *Adapter) StopDiscovery(ctx context.Context) error {
	obj := a.conn.Object(bluezService, a.adapterPath)
	call := obj.CallWithContext(ctx, adapterIface+".StopDiscovery", 0)
	return call.Err
}

// AdapterPath returns the D-Bus object path of this adapter.
func (a *Adapter) AdapterPath() dbus.ObjectPath { return a.adapterPath }

// Conn returns the D-Bus connection.
func (a *Adapter) Conn() *dbus.Conn { return a.conn }

// AdapterName returns e.g. "hci0".
func (a *Adapter) AdapterName() string { return a.adapterName }

// Address reads the adapter's Bluetooth address.
func (a *Adapter) Address() (string, error) {
	obj := a.conn.Object(bluezService, a.adapterPath)
	var addr dbus.Variant
	err := obj.Call(propertiesIF+".Get", 0, adapterIface, "Address").Store(&addr)
	if err != nil {
		return "", err
	}
	s, ok := addr.Value().(string)
	if !ok {
		return "", fmt.Errorf("unexpected address type")
	}
	return s, nil
}

// SetExtendedScanParams attempts to configure extended scan parameters via
// raw HCI socket to enable LE Coded PHY scanning.
// This requires CAP_NET_RAW and kernel ≥5.4 with extended advertising support.
//
// TODO: implement raw HCI LE_Set_Extended_Scan_Parameters command
// (OGF=0x08 OCF=0x0041) to set:
//   - ScanType: 0x01 (Active) or 0x00 (Passive)
//   - ScanInterval, ScanWindow for LE Coded PHY (PHY=0x04)
//
// Currently this function logs a message and relies on BlueZ's own
// extended scan support when available.
func (a *Adapter) SetExtendedScanParams() error {
	if !a.CodedPHYSupported {
		return fmt.Errorf("adapter does not support LE Coded PHY")
	}
	log.Printf("[adapter] Extended scan params: relying on BlueZ extended scan (kernel 5.4+)")
	log.Printf("[adapter] TODO: raw HCI LE_Set_Extended_Scan_Parameters for explicit Coded PHY")
	return nil
}

// SetConnectionPHY sends HCI LE_Set_PHY for a connected handle to upgrade to LE Coded.
//
// TODO: implement raw HCI LE_Set_PHY command
// (OGF=0x08 OCF=0x0031):
//   - All_PHYs: 0x00 (no preference override)
//   - TX_PHYs: 0x04 (LE Coded)
//   - RX_PHYs: 0x04 (LE Coded)
//   - PHY_Options: 0x02 (S=8 coding for max range)
//
// BlueZ exposes SetPHY on Device1 in BlueZ 5.56+ but the D-Bus API
// does not yet expose per-connection PHY parameters. For now, rely on
// the GattClient to call gatt.setPreferredPhy on the Android side.
func (a *Adapter) SetConnectionPHY(handle uint16) error {
	log.Printf("[adapter] TODO: raw HCI LE_Set_PHY handle=0x%04x coded-S8", handle)
	return nil
}
