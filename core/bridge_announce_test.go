package core

import (
	"encoding/hex"
	"testing"
)

// sharedBridgeVector is the canonical v2 ANNOUNCE test vector. The same identity seed, fields, and
// bridges are signed in the Kotlin suite (ProtocolTest.bridgeAnnounceCrossImplVector); both impls
// MUST produce these exact signed bytes (and therefore the same signature) or the wire format has
// drifted between Go and Kotlin. Inputs: identity seed offset 7; epoch 3, seq 4, timestamp 100,
// caps 0x1F; neighbors {seed8, seed9}; name "alice", desc "", platform "test"; bridges
// [{"CZ"}, {"EU", 869525000, 250000, sf11, cr5}].
const (
	sharedBridgeSignedMsgHex = "53494445504154482d414e4e4f554e43452d5631000200d05a1d1ea251396d557afbd4588b3c6d99dbeb972fed10a32562ea26dcdcfa03000000000000000400000064000000000000001f0002004bddee550ef734cae04e677edd9c7a6fbaa5d6090500616c6963650000040074657374020002435a000245550108e6d33390d003000b05"
	sharedBridgeSigHex       = "a6c31fbf37746375910f5677ca0e4084214f92f391281301322a648a5e0210e408d5af6a8aff381a1359f8cee05943b7fabe411f8083169f069aa99e28f9fe0b"
)

func sharedBridgeVector() (*Identity, []NodeID, []BridgeAd) {
	return testIdentity(7),
		[]NodeID{testIdentity(8).NodeID(), testIdentity(9).NodeID()},
		[]BridgeAd{
			{Code: "CZ"},
			{Code: "EU", FreqHz: 869525000, BandwidthHz: 250000, SF: 11, CR: 5},
		}
}

func TestBridgeAnnounceVectorLocksWireFormat(t *testing.T) {
	id, nbs, bridges := sharedBridgeVector()
	body := NewAnnounceBody(id, 3, 4, 100, Capabilities(0x1F), nbs, "alice", "", "test", bridges)

	if body.AnnounceVersion != 2 {
		t.Fatalf("a body with bridges must be v2, got %d", body.AnnounceVersion)
	}
	msg := AnnounceSignedMessage(id.Pub, 3, 4, 100, Capabilities(0x1F), body.Neighbors, "alice", "", "test", 2, bridges)
	if got := hex.EncodeToString(msg); got != sharedBridgeSignedMsgHex {
		t.Fatalf("signed-message bytes drifted:\n got  %s\n want %s", got, sharedBridgeSignedMsgHex)
	}
	if got := hex.EncodeToString(body.Signature); got != sharedBridgeSigHex {
		t.Fatalf("signature drifted:\n got  %s\n want %s", got, sharedBridgeSigHex)
	}
	if !body.Valid() {
		t.Fatal("vector body must verify")
	}
}

func TestBridgeAnnounceRoundTripAndValidation(t *testing.T) {
	id, nbs, bridges := sharedBridgeVector()
	body := NewAnnounceBody(id, 3, 4, 100, Capabilities(0x1F), nbs, "alice", "", "test", bridges)

	// CBOR round-trip via the control envelope preserves the bridges (k12).
	ctrl, err := body.ToControl()
	if err != nil {
		t.Fatal(err)
	}
	cm, err := DecodeControl(ctrl)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := DecodeAnnounceBody(cm.Body)
	if err != nil {
		t.Fatal(err)
	}
	if len(decoded.Bridges) != 2 || decoded.Bridges[0].Code != "CZ" || decoded.Bridges[1].Code != "EU" {
		t.Fatalf("bridges not preserved: %+v", decoded.Bridges)
	}
	if decoded.Bridges[0].IsCustom() || !decoded.Bridges[1].IsCustom() {
		t.Fatalf("custom flag wrong: %+v", decoded.Bridges)
	}
	if decoded.Bridges[1].FreqHz != 869525000 || decoded.Bridges[1].SF != 11 || decoded.Bridges[1].CR != 5 {
		t.Fatalf("custom radio params wrong: %+v", decoded.Bridges[1])
	}
	if !decoded.Valid() {
		t.Fatal("decoded body must verify")
	}

	// Tampering a bridge code breaks the signature (bridges are signed, §8.3).
	tampered := decoded
	tampered.Bridges = []BridgeAd{{Code: "XX"}, decoded.Bridges[1]}
	if tampered.Valid() {
		t.Fatal("tampered bridge code must not verify")
	}
}

func TestAnnounceV1BackCompat(t *testing.T) {
	id := testIdentity(7)
	nbs := []NodeID{testIdentity(8).NodeID()}

	// No bridges -> v1, byte-identical to the original layout (no trailing bridges section).
	v1 := NewAnnounceBody(id, 1, 1, 50, Capabilities(0x02), nbs, "n", "", "p", nil)
	if v1.AnnounceVersion != MinAnnounceVersion {
		t.Fatalf("no-bridge announce must be v1, got %d", v1.AnnounceVersion)
	}
	if !v1.Valid() {
		t.Fatal("v1 announce must verify")
	}
	// A v1 body that somehow carries bridges is rejected (bridges only exist from v2).
	bad := v1
	bad.Bridges = []BridgeAd{{Code: "CZ"}}
	if bad.Valid() {
		t.Fatal("v1 body with bridges must be rejected")
	}
}

func TestBridgeAdValidation(t *testing.T) {
	cases := []struct {
		name  string
		b     BridgeAd
		valid bool
	}{
		{"canonical", BridgeAd{Code: "CZ"}, true},
		{"empty code", BridgeAd{Code: ""}, false},
		{"too long code", BridgeAd{Code: "ABCDEF"}, false},
		{"full custom", BridgeAd{Code: "EU", FreqHz: 869525000, BandwidthHz: 250000, SF: 11, CR: 5}, true},
		{"partial custom", BridgeAd{Code: "EU", FreqHz: 869525000}, false},
		{"sf out of range", BridgeAd{Code: "EU", FreqHz: 1, BandwidthHz: 1, SF: 13, CR: 5}, false},
		{"cr out of range", BridgeAd{Code: "EU", FreqHz: 1, BandwidthHz: 1, SF: 7, CR: 9}, false},
	}
	for _, c := range cases {
		if got := c.b.Valid(); got != c.valid {
			t.Errorf("%s: Valid()=%v want %v", c.name, got, c.valid)
		}
	}
}
