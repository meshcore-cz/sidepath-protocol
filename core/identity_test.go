package core

import (
	"encoding/hex"
	"testing"
)

// vectorSeed is the shared cross-platform test vector seed. The derived public
// key and the announce signature below MUST be reproduced byte-for-byte by the
// Kotlin (Android) and C++ (ESP32) ports — that is what proves Ed25519
// interop across the three implementations. Do not change without updating the
// firmware host_test.cpp and the Android unit test in lockstep.
var vectorSeed = [32]byte{
	0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
	0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
	0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
	0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
}

// vectorNeighbors / caps / seq / timestamp are the fixed announce inputs.
var (
	vectorCaps      = Capabilities(CapReceiver | CapRelay | CapCodedPHY)
	vectorSeq       = uint32(7)
	vectorTimestamp = uint32(1_700_000_000)
)

func vectorNeighbors() []NodeID {
	return []NodeID{
		{0xaa, 0xaa, 0xaa, 0xaa, 0xaa, 0xaa, 0xaa, 0xaa},
		{0xbb, 0xbb, 0xbb, 0xbb, 0xbb, 0xbb, 0xbb, 0xbb},
	}
}

// Expected outputs for vectorSeed — the cross-platform contract.
const (
	vectorPubHex = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
	vectorSigHex = "e5ef6a4d3347a38de44b739c51c9c2add495c02397505576131c0978474a598a" +
		"aa31cae542fa7e67491b3c8f2e49f45701d9e5e106c873c59b971bcb12620408"
)

func TestIdentityVector(t *testing.T) {
	id := IdentityFromSeed(vectorSeed)
	pubHex := hex.EncodeToString(id.Pub)
	nodeID := id.NodeID()
	sig := id.SignAnnounce(vectorTimestamp, vectorCaps, vectorSeq, vectorNeighbors())
	sigHex := hex.EncodeToString(sig)

	if pubHex != vectorPubHex {
		t.Fatalf("pubkey vector drift:\n got  %s\n want %s", pubHex, vectorPubHex)
	}
	if sigHex != vectorSigHex {
		t.Fatalf("signature vector drift:\n got  %s\n want %s", sigHex, vectorSigHex)
	}

	if !VerifyAnnounce(id.Pub, sig, vectorTimestamp, vectorCaps, vectorSeq, vectorNeighbors()) {
		t.Fatal("self-verify failed")
	}
	// Tamper: flipping caps must fail verification.
	if VerifyAnnounce(id.Pub, sig, vectorTimestamp, vectorCaps+1, vectorSeq, vectorNeighbors()) {
		t.Fatal("tampered caps verified")
	}
	if nodeID != NodeIDFromPubKey(id.Pub) {
		t.Fatal("nodeID mismatch")
	}
}

func TestHandleAnnounceRejectsTampered(t *testing.T) {
	r := NewRouter(nodeID(1))
	bob, _ := NewIdentity()
	pkt := signedAnnounce(bob, vectorCaps, vectorNeighbors(), 3)

	// Valid announce is accepted into topology.
	src := bob.NodeID()
	r.HandlePacket(pkt, &src)
	if _, ok := r.Topology.GetNode(bob.NodeID()); !ok {
		t.Fatal("valid signed announce should populate topology")
	}

	// Tamper the caps in the payload (signature no longer matches) -> dropped.
	ap, _ := DecodeAnnounce(pkt.Payload)
	ap.Caps = ap.Caps + 1
	bad := pkt
	bad.ID = NewPacketID()
	bad.Payload, _ = ap.Encode()
	acts := r.HandlePacket(bad, &src)
	if len(acts) != 1 || acts[0].Type != ActionDrop || acts[0].Reason != string(DropBadSignature) {
		t.Fatalf("tampered announce should drop as bad-signature, got %+v", acts)
	}
}
