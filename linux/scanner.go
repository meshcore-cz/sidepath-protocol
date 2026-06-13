package linux

import (
	"context"
	"fmt"
	"log"
	"strings"

	"github.com/godbus/dbus/v5"

	"github.com/burningtree/bleedge/core"
)

const (
	deviceIface = "org.bluez.Device1"

	// BLEEdge GATT service UUID
	BLEEdgeServiceUUID = "9b7e6a10-7d91-4c19-a3b8-6e2a11f3a001"
)

// bleedgeCompanyID is the manufacturer (company) ID used to tag BLEEdge
// advertisements; the payload is the 8-byte NodeID.
const bleedgeCompanyID = uint16(0xBEED)

// ScanResult represents a discovered BLEEdge peer device.
type ScanResult struct {
	DevicePath dbus.ObjectPath
	Address    string
	Name       string
	RSSI       int16
	UUIDs      []string
	// NodeID is the peer's NodeID parsed from advertised manufacturer data, when
	// present. HasNodeID is false for peers that don't advertise it (e.g. macOS,
	// which can't broadcast manufacturer data) — their NodeID is read from NODE_INFO.
	NodeID    core.NodeID
	HasNodeID bool
}

// Scanner watches for BLEEdge peers using BlueZ D-Bus signals.
type Scanner struct {
	adapter  *Adapter
	results  chan ScanResult
	stopChan chan struct{}
}

// NewScanner creates a Scanner bound to the given adapter.
func NewScanner(adapter *Adapter) *Scanner {
	return &Scanner{
		adapter:  adapter,
		results:  make(chan ScanResult, 64),
		stopChan: make(chan struct{}),
	}
}

// Results returns a channel that receives discovered BLEEdge devices.
func (s *Scanner) Results() <-chan ScanResult { return s.results }

// Start begins scanning. It sets a discovery filter for LE and watches
// InterfacesAdded / PropertiesChanged signals on the D-Bus system bus.
func (s *Scanner) Start(ctx context.Context) error {
	if err := s.adapter.SetDiscoveryFilter(ctx); err != nil {
		log.Printf("[scanner] SetDiscoveryFilter: %v (continuing without filter)", err)
	}

	if err := s.adapter.SetExtendedScanParams(); err != nil {
		log.Printf("[scanner] extended scan params: %v", err)
	}

	if err := s.adapter.StartDiscovery(ctx); err != nil {
		return fmt.Errorf("StartDiscovery: %w", err)
	}

	conn := s.adapter.Conn()

	// Subscribe to InterfacesAdded — fires when new device objects appear
	conn.BusObject().Call( //nolint:errcheck
		"org.freedesktop.DBus.AddMatch", 0,
		"type='signal',sender='org.bluez',interface='org.freedesktop.DBus.ObjectManager',member='InterfacesAdded'",
	)

	// Subscribe to PropertiesChanged — fires when RSSI / UUIDs update
	conn.BusObject().Call( //nolint:errcheck
		"org.freedesktop.DBus.AddMatch", 0,
		"type='signal',sender='org.bluez',interface='org.freedesktop.DBus.Properties',member='PropertiesChanged'",
	)

	sigCh := make(chan *dbus.Signal, 64)
	conn.Signal(sigCh)

	go s.watchSignals(ctx, sigCh)
	return nil
}

// Stop stops discovery and the signal watcher.
func (s *Scanner) Stop(ctx context.Context) {
	s.adapter.StopDiscovery(ctx) //nolint:errcheck
	close(s.stopChan)
}

func (s *Scanner) watchSignals(ctx context.Context, sigCh <-chan *dbus.Signal) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.stopChan:
			return
		case sig, ok := <-sigCh:
			if !ok {
				return
			}
			s.handleSignal(sig)
		}
	}
}

func (s *Scanner) handleSignal(sig *dbus.Signal) {
	switch sig.Name {
	case "org.freedesktop.DBus.ObjectManager.InterfacesAdded":
		if len(sig.Body) < 2 {
			return
		}
		path, ok := sig.Body[0].(dbus.ObjectPath)
		if !ok {
			return
		}
		ifaces, ok := sig.Body[1].(map[string]map[string]dbus.Variant)
		if !ok {
			return
		}
		props, ok := ifaces[deviceIface]
		if !ok {
			return
		}
		s.handleDeviceProps(path, props)

	case "org.freedesktop.DBus.Properties.PropertiesChanged":
		if len(sig.Body) < 2 {
			return
		}
		iface, ok := sig.Body[0].(string)
		if !ok || iface != deviceIface {
			return
		}
		changed, ok := sig.Body[1].(map[string]dbus.Variant)
		if !ok {
			return
		}
		// Fetch full device object for this path
		s.handleDeviceProps(sig.Path, changed)
	}
}

func (s *Scanner) handleDeviceProps(path dbus.ObjectPath, props map[string]dbus.Variant) {
	// Only care about BLEEdge devices
	if !s.hasBLEEdgeUUID(props) {
		return
	}

	result := ScanResult{DevicePath: path}
	if v, ok := props["Address"]; ok {
		result.Address, _ = v.Value().(string)
	}
	if v, ok := props["Name"]; ok {
		result.Name, _ = v.Value().(string)
	}
	if v, ok := props["RSSI"]; ok {
		result.RSSI, _ = v.Value().(int16)
	}
	if v, ok := props["UUIDs"]; ok {
		result.UUIDs, _ = v.Value().([]string)
	}
	if id, ok := nodeIDFromManufacturerData(props); ok {
		result.NodeID, result.HasNodeID = id, true
	}

	log.Printf("[scanner] found device path=%s addr=%s rssi=%d node=%s", path, result.Address, result.RSSI, nodeIDOrUnknown(result))

	select {
	case s.results <- result:
	default:
		log.Printf("[scanner] results channel full, dropping %s", path)
	}
}

// nodeIDFromManufacturerData parses the BLEEdge NodeID from a device's
// ManufacturerData property (BlueZ type a{qv} → map[uint16]variant of []byte).
func nodeIDFromManufacturerData(props map[string]dbus.Variant) (core.NodeID, bool) {
	v, ok := props["ManufacturerData"]
	if !ok {
		return core.NodeID{}, false
	}
	m, ok := v.Value().(map[uint16]dbus.Variant)
	if !ok {
		return core.NodeID{}, false
	}
	entry, ok := m[bleedgeCompanyID]
	if !ok {
		return core.NodeID{}, false
	}
	b, ok := entry.Value().([]byte)
	if !ok || len(b) < 8 {
		return core.NodeID{}, false
	}
	var id core.NodeID
	copy(id[:], b[:8])
	return id, true
}

func nodeIDOrUnknown(r ScanResult) string {
	if r.HasNodeID {
		return r.NodeID.String()
	}
	return "unknown"
}

func (s *Scanner) hasBLEEdgeUUID(props map[string]dbus.Variant) bool {
	v, ok := props["UUIDs"]
	if !ok {
		return false
	}
	uuids, ok := v.Value().([]string)
	if !ok {
		return false
	}
	for _, u := range uuids {
		if strings.EqualFold(u, BLEEdgeServiceUUID) {
			return true
		}
	}
	return false
}

// GetExistingDevices returns BLEEdge devices already known to BlueZ.
func (s *Scanner) GetExistingDevices(ctx context.Context) ([]ScanResult, error) {
	conn := s.adapter.Conn()
	obj := conn.Object(bluezService, "/")
	var managed map[dbus.ObjectPath]map[string]map[string]dbus.Variant
	if err := obj.CallWithContext(ctx, objectManagerIF+".GetManagedObjects", 0).Store(&managed); err != nil {
		return nil, err
	}

	adapterPrefix := string(s.adapter.AdapterPath()) + "/"
	var results []ScanResult
	for path, ifaces := range managed {
		if !strings.HasPrefix(string(path), adapterPrefix) {
			continue
		}
		props, ok := ifaces[deviceIface]
		if !ok {
			continue
		}
		if !s.hasBLEEdgeUUID(props) {
			continue
		}
		r := ScanResult{DevicePath: path}
		if v, ok := props["Address"]; ok {
			r.Address, _ = v.Value().(string)
		}
		if v, ok := props["RSSI"]; ok {
			r.RSSI, _ = v.Value().(int16)
		}
		if v, ok := props["UUIDs"]; ok {
			r.UUIDs, _ = v.Value().([]string)
		}
		if id, ok := nodeIDFromManufacturerData(props); ok {
			r.NodeID, r.HasNodeID = id, true
		}
		results = append(results, r)
	}
	return results, nil
}
