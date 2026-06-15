//go:build darwin

package macos

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
)

// Sidepath GATT UUIDs (docs/PROTOCOL.md §4.1), as strings for the Swift helper.
const (
	helperServiceUUID   = "9B7E6A10-7D91-4C19-A3B8-6E2A11F3A001"
	helperNodeInfoUUID  = "9B7E6A10-7D91-4C19-A3B8-6E2A11F3A002"
	helperPacketInUUID  = "9B7E6A10-7D91-4C19-A3B8-6E2A11F3A003"
	helperPacketOutUUID = "9B7E6A10-7D91-4C19-A3B8-6E2A11F3A004"
)

// bleHelper drives the native CoreBluetooth helper process over a length-prefixed stdio protocol:
// [uint32 BE total][uint16 BE jsonLen][JSON header][raw payload]. All BLE lives in the helper; this
// is a thin transport. Events are dispatched to [onEvent] on the reader goroutine.
type bleHelper struct {
	cmd     *exec.Cmd
	stdin   io.WriteCloser
	wmu     sync.Mutex
	logf    func(string)
	onEvent func(header map[string]any, payload []byte)
}

// helperPath resolves the helper binary: env override, then alongside the executable, then the dev
// build path.
func helperPath() (string, error) {
	if p := os.Getenv("SIDEPATH_BLE_HELPER"); p != "" {
		return p, nil
	}
	if exe, err := os.Executable(); err == nil {
		cand := filepath.Join(filepath.Dir(exe), "sidepath-macos-ble-helper")
		if _, err := os.Stat(cand); err == nil {
			return cand, nil
		}
	}
	if _, err := os.Stat("bin/sidepath-macos-ble-helper"); err == nil {
		return "bin/sidepath-macos-ble-helper", nil
	}
	return "", fmt.Errorf("sidepath-macos-ble-helper not found (build it or set SIDEPATH_BLE_HELPER)")
}

func startBLEHelper(logf func(string), onEvent func(map[string]any, []byte)) (*bleHelper, error) {
	path, err := helperPath()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(path)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start helper %s: %w", path, err)
	}
	h := &bleHelper{cmd: cmd, stdin: stdin, logf: logf, onEvent: onEvent}
	go h.readLoop(stdout)
	go func() {
		s := bufio.NewScanner(stderr)
		for s.Scan() {
			logf("helper-stderr: " + s.Text())
		}
	}()
	return h, nil
}

func (h *bleHelper) send(header map[string]any, payload []byte) {
	hjson, err := json.Marshal(header)
	if err != nil {
		return
	}
	buf := make([]byte, 6+len(hjson)+len(payload))
	binary.BigEndian.PutUint32(buf[0:4], uint32(2+len(hjson)+len(payload)))
	binary.BigEndian.PutUint16(buf[4:6], uint16(len(hjson)))
	copy(buf[6:], hjson)
	copy(buf[6+len(hjson):], payload)
	h.wmu.Lock()
	_, _ = h.stdin.Write(buf)
	h.wmu.Unlock()
}

func (h *bleHelper) readLoop(stdout io.Reader) {
	r := bufio.NewReaderSize(stdout, 64*1024)
	lenBuf := make([]byte, 4)
	for {
		if _, err := io.ReadFull(r, lenBuf); err != nil {
			h.logf("ble helper closed: " + err.Error())
			return
		}
		total := binary.BigEndian.Uint32(lenBuf)
		body := make([]byte, total)
		if _, err := io.ReadFull(r, body); err != nil {
			h.logf("ble helper read error: " + err.Error())
			return
		}
		if len(body) < 2 {
			continue
		}
		jlen := int(binary.BigEndian.Uint16(body[0:2]))
		if 2+jlen > len(body) {
			continue
		}
		var header map[string]any
		if err := json.Unmarshal(body[2:2+jlen], &header); err != nil {
			continue
		}
		h.onEvent(header, body[2+jlen:])
	}
}

// ---- commands ----

func (h *bleHelper) start(nodeInfo []byte) {
	h.send(map[string]any{
		"type":            "start",
		"service_uuid":    helperServiceUUID,
		"node_info_uuid":  helperNodeInfoUUID,
		"packet_in_uuid":  helperPacketInUUID,
		"packet_out_uuid": helperPacketOutUUID,
	}, nodeInfo)
}

func (h *bleHelper) setNodeInfo(nodeInfo []byte) {
	h.send(map[string]any{"type": "set_node_info"}, nodeInfo)
}
func (h *bleHelper) connect(addr string) {
	h.send(map[string]any{"type": "connect", "addr": addr}, nil)
}
func (h *bleHelper) disconnect(addr string) {
	h.send(map[string]any{"type": "disconnect", "addr": addr}, nil)
}

func (h *bleHelper) sendCentral(addr string, frame []byte, reliable bool) {
	h.send(map[string]any{"type": "send_central", "addr": addr, "reliable": reliable}, frame)
}

func (h *bleHelper) sendPeripheral(centralID string, frame []byte) {
	h.send(map[string]any{"type": "send_peripheral", "central_id": centralID}, frame)
}

// asInt coerces a JSON number (float64) from a header field to int.
func asInt(v any) int {
	if f, ok := v.(float64); ok {
		return int(f)
	}
	return 0
}

func asString(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func asBool(v any) bool {
	if b, ok := v.(bool); ok {
		return b
	}
	return false
}
