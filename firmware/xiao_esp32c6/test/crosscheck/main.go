// crosscheck validates the XIAO ESP32-C6 firmware mesh logic (mesh.cpp) against
// the Go reference implementation in core/. It generates packets, runs them
// through the compiled host_test binary, and verifies the results decode
// correctly and that CRC32 / fragmentation are byte-identical.
//
// Usage: go run ./firmware/xiao_esp32c6/test/crosscheck <path-to-host_test>
// (host_test is built by firmware/xiao_esp32c6/test/run_tests.sh)
//go:build v2obsolete

package main

import (
	"bytes"
	"encoding/hex"
	"fmt"
	"hash/crc32"
	"os"
	"os/exec"
	"strings"

	"github.com/bleedge/bleedge/core"
)

var (
	bin  string
	self = core.NodeID{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88}
	fail bool
)

func check(name string, cond bool) {
	if !cond {
		fmt.Printf("FAIL %s\n", name)
		fail = true
		return
	}
	fmt.Printf("ok   %s\n", name)
}

func runFwd(pkt []byte) (parsed string, fwd []byte) {
	out, err := exec.Command(bin, hex.EncodeToString(pkt), hex.EncodeToString(self[:])).Output()
	if err != nil {
		fmt.Printf("FAIL exec forward: %v\n", err)
		os.Exit(1)
	}
	lines := strings.Split(strings.TrimSpace(string(out)), "\n")
	fwd, _ = hex.DecodeString(lines[1])
	return lines[0], fwd
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: crosscheck <path-to-host_test>")
		os.Exit(2)
	}
	bin = os.Args[1]

	// ---- relay transcode --------------------------------------------------
	pktA := core.Packet{Version: 1, Type: core.PacketTypeData, ID: core.NewPacketID(),
		Source: core.NodeID{0, 0, 0, 0, 0, 0, 0, 0xAA}, Mode: core.RoutingModeFlood, TTL: 4,
		PayloadType: core.PayloadTypeTextTest, Payload: []byte("hi")}
	encA, _ := pktA.Encode()
	parsedA, fwdA := runFwd(encA)
	gotA, errA := core.DecodePacket(fwdA)
	check("A: fresh flood (trace=nil) decodes", errA == nil)
	check("A: ttl 4->3", gotA.TTL == 3)
	check("A: trace=[self]", len(gotA.Trace) == 1 && gotA.Trace[0] == self)
	check("A: id/payload/source unchanged", gotA.ID == pktA.ID && string(gotA.Payload) == "hi" && gotA.Source == pktA.Source)
	// fresh packet → direct neighbor is the source
	check("A: neighbor=source", strings.Contains(parsedA, "neighbor="+hex.EncodeToString(pktA.Source[:])))

	x := core.NodeID{0xDE, 0, 0, 0, 0, 0, 0, 0x01}
	pktB := core.Packet{Version: 1, Type: core.PacketTypeData, ID: core.NewPacketID(),
		Source: core.NodeID{0, 0, 0, 0, 0, 0, 0, 0xBB}, Destination: core.NodeID{0, 0, 0, 0, 0, 0, 0, 0xCC},
		Mode: core.RoutingModeFlood, TTL: 3, Trace: []core.NodeID{x},
		PayloadType: core.PayloadTypeTextTest, Payload: []byte("relay me")}
	encB, _ := pktB.Encode()
	parsedB, fwdB := runFwd(encB)
	gotB, errB := core.DecodePacket(fwdB)
	check("B: existing trace decodes", errB == nil)
	check("B: ttl 3->2", gotB.TTL == 2)
	check("B: trace=[x,self]", len(gotB.Trace) == 2 && gotB.Trace[0] == x && gotB.Trace[1] == self)
	check("B: dest unchanged", gotB.Destination == pktB.Destination)
	// packet with a trace → direct neighbor is the last hop (x)
	check("B: neighbor=lastHop(x)", strings.Contains(parsedB, "neighbor="+hex.EncodeToString(x[:])))

	ap := core.AnnouncePayload{NodeID: pktB.Source, Caps: core.Capabilities(0x07), Seq: 9, Timestamp: 1700000000}
	apEnc, _ := ap.Encode()
	pktC := core.Packet{Version: 1, Type: core.PacketTypeAnnounce, ID: core.NewPacketID(),
		Source: pktB.Source, Mode: core.RoutingModeFlood, TTL: 3,
		PayloadType: core.PayloadTypeMeshCoreRaw, Payload: apEnc}
	encC, _ := pktC.Encode()
	_, fwdC := runFwd(encC)
	gotC, errC := core.DecodePacket(fwdC)
	check("C: announce decodes", errC == nil)
	check("C: ttl 3->2 trace=[self]", gotC.TTL == 2 && len(gotC.Trace) == 1 && gotC.Trace[0] == self)
	gotAP, errAP := core.DecodeAnnounce(gotC.Payload)
	check("C: announce payload intact", errAP == nil && gotAP.Seq == 9 && gotAP.Caps == 0x07 && gotAP.NodeID == pktB.Source)

	pktD := core.Packet{Version: 1, Type: core.PacketTypeData, ID: core.NewPacketID(),
		Source: core.NodeID{0, 0, 0, 0, 0, 0, 0, 0xBB}, Mode: core.RoutingModeFlood, TTL: 3,
		Trace: []core.NodeID{self}, PayloadType: core.PayloadTypeTextTest, Payload: []byte("x")}
	encD, _ := pktD.Encode()
	parsedD, _ := runFwd(encD)
	check("D: loop detected (self in trace)", strings.Contains(parsedD, "loop=1"))

	carol := core.NodeID{0xCA, 0, 0, 0, 0, 0, 0, 0x03}
	traceRoute := []core.NodeID{self, carol}
	routeData, _ := core.TraceRouteData(traceRoute, core.TraceHashWidth8)
	tracePayload := core.EncodeTracePayload(core.TracePayload{Tag: 1, Flags: 3, RouteData: routeData})
	pktE := core.Packet{Version: core.ProtocolVersion, Type: core.PacketTypeData, ID: core.NewPacketID(),
		Source: pktB.Source, Destination: carol, Mode: core.RoutingModeSourceRoute, TTL: 4,
		Route: traceRoute, PayloadType: core.PayloadTypeTraceRequest, Payload: tracePayload}
	encE, _ := pktE.Encode()
	outE, errE := exec.Command(bin, "--trace-fwd", hex.EncodeToString(encE), hex.EncodeToString(self[:])).Output()
	check("E: trace source-route helper runs", errE == nil)
	fwdE, _ := hex.DecodeString(strings.TrimSpace(string(outE)))
	gotE, errE2 := core.DecodePacket(fwdE)
	check("E: trace source-route decodes", errE2 == nil)
	check("E: ttl 4->3 cursor 0->1", gotE.TTL == 3 && gotE.RouteCursor == 1)
	check("E: trace=[self]", len(gotE.Trace) == 1 && gotE.Trace[0] == self)
	check("E: trace metric appended", len(gotE.TraceMetric) == 1 && gotE.TraceMetric[0] == 0)

	// ---- CRC32 ------------------------------------------------------------
	for _, s := range []string{"", "00", "74657374", "deadbeefcafe0011223344556677"} {
		d, _ := hex.DecodeString(s)
		want := fmt.Sprintf("%08x", crc32.ChecksumIEEE(d))
		out, _ := exec.Command(bin, "--crc", s).Output()
		check("crc("+s+")", strings.TrimSpace(string(out)) == want)
	}

	// ---- fragmentation byte-identical to core.FragmentPacket --------------
	pid := core.NewPacketID()
	data := make([]byte, 600)
	for i := range data {
		data[i] = byte(i)
	}
	mtu := 200
	var goHex []string
	for _, f := range core.FragmentPacket(data, mtu, pid) {
		goHex = append(goHex, hex.EncodeToString(f.Encode()))
	}
	out, _ := exec.Command(bin, "--frag", hex.EncodeToString(data), fmt.Sprint(mtu), hex.EncodeToString(pid[:])).Output()
	cppHex := strings.Split(strings.TrimSpace(string(out)), "\n")
	check("frag count matches", len(goHex) == len(cppHex))
	allEq := len(goHex) == len(cppHex)
	for i := 0; i < len(goHex) && i < len(cppHex); i++ {
		if goHex[i] != cppHex[i] {
			allEq = false
			fmt.Printf("  frame %d differs\n   go : %s\n   cpp: %s\n", i, goHex[i], cppHex[i])
		}
	}
	check("frames byte-identical", allEq)

	// ---- signed ANNOUNCE: firmware derives keypair + signs, Go verifies -------
	// This is the load-bearing proof that orlp/ed25519 (C) and crypto/ed25519
	// (Go) agree on the public key and signature for the same seed + message.
	var seed [core.SeedSize]byte
	for i := range seed {
		seed[i] = byte(i)
	}
	goID := core.IdentityFromSeed(seed)
	caps := core.Capabilities(core.CapReceiver | core.CapRelay | core.CapCodedPHY) // 0x16
	n1 := core.NodeID{0xaa, 0xaa, 0xaa, 0xaa, 0xaa, 0xaa, 0xaa, 0xaa}
	n2 := core.NodeID{0xbb, 0xbb, 0xbb, 0xbb, 0xbb, 0xbb, 0xbb, 0xbb}
	annOut, _ := exec.Command(bin, "--announce", hex.EncodeToString(seed[:]),
		fmt.Sprint(uint8(caps)), "7",
		hex.EncodeToString(n1[:]), hex.EncodeToString(n2[:])).Output()
	annBytes, _ := hex.DecodeString(strings.TrimSpace(string(annOut)))
	annPkt, errAnn := core.DecodePacket(annBytes)
	check("announce packet decodes", errAnn == nil)
	check("announce is ANNOUNCE type, flood, ttl=3, v2", annPkt.Type == core.PacketTypeAnnounce &&
		annPkt.Mode == core.RoutingModeFlood && annPkt.TTL == 3 && annPkt.Version == core.ProtocolVersion)
	payload, errPL := core.DecodeAnnounce(annPkt.Payload)
	check("announce payload decodes", errPL == nil)
	check("fw pubkey == go pubkey (orlp == stdlib)", bytes.Equal(payload.PublicKey, goID.Pub))
	check("nodeId == pubkey[:8]", payload.NodeID == goID.NodeID() && annPkt.Source == goID.NodeID())
	check("announce caps/seq/neighbors", payload.Caps == caps && payload.Seq == 7 &&
		len(payload.Neighbors) == 2 && payload.Neighbors[0] == n1 && payload.Neighbors[1] == n2)
	check("announce platform (text, unsigned)", payload.Platform == "esp32-c6")
	check("announce description empty + name empty (name derived from pubkey by peers)",
		payload.Description == "" && payload.Name == "")
	check("fw signature verifies in Go", core.VerifyAnnounce(payload.PublicKey, payload.Signature,
		uint32(payload.Timestamp), payload.Caps, payload.Seq, payload.Neighbors))

	if fail {
		os.Exit(1)
	}
	fmt.Println("ALL PASS")
}
