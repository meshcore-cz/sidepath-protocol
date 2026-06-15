package core

import (
	"encoding/hex"
	"testing"
)

// sharedV3SignedMsgHex / sharedV3SigHex lock the v3 ANNOUNCE wire format the same way the v2 bridge
// vector does (see bridge_announce_test.go). A Kotlin mirror MUST sign these exact bytes. Inputs:
// identity seed offset 7; epoch 3, seq 4, timestamp 100, caps 0x1F; name "alice", desc "", platform
// "test"; no bridges; neighbor_info [{seed8, rssi -50, tx 1M, rx 2M, dir out, age 12}, {seed9, rssi
// -70, tx coded, rx coded, dir in, age 300}] (sorted by ID).
const (
	sharedV3SignedMsgHex = "53494445504154482d414e4e4f554e43452d5631000300d05a1d1ea251396d557afbd4588b3c6d99dbeb972fed10a32562ea26dcdcfa03000000000000000400000064000000000000001f0000000500616c6963650000040074657374000002004bddee550ef734cae04ece0102010c000000677edd9c7a6fbaa5d609ba0303022c010000"
	sharedV3SigHex       = "fd9ad6da0b001f66bf57bc234a5f19294d53de57e4c422732218691138bde5f45bdddfe0671f5f8ffcec9323f89a335b2480ddd872e491ca48444d16ee166a0b"
)

func sharedV3Vector() (*Identity, []NeighborInfo) {
	return testIdentity(7), []NeighborInfo{
		{ID: testIdentity(8).NodeID(), RSSI: -50, TxPHY: PHY1M, RxPHY: PHY2M, Dir: DirectionOutgoing, AgeS: 12},
		{ID: testIdentity(9).NodeID(), RSSI: -70, TxPHY: PHYCoded, RxPHY: PHYCoded, Dir: DirectionIncoming, AgeS: 300},
	}
}

func TestAnnounceV3VectorLocksWireFormat(t *testing.T) {
	id, infos := sharedV3Vector()
	body := NewAnnounceBodyV3(id, 3, 4, 100, Capabilities(0x1F), infos, "alice", "", "test", nil)

	if body.AnnounceVersion != 3 {
		t.Fatalf("a body with neighbor info must be v3, got %d", body.AnnounceVersion)
	}
	if len(body.Neighbors) != 0 {
		t.Fatalf("v3 must leave the bare neighbor list empty, got %d entries", len(body.Neighbors))
	}
	msg := AnnounceSignedMessage(id.Pub, 3, 4, 100, Capabilities(0x1F), nil, "alice", "", "test", 3, nil, body.NeighborInfo)
	if sharedV3SignedMsgHex != "" {
		if got := hex.EncodeToString(msg); got != sharedV3SignedMsgHex {
			t.Fatalf("signed-message bytes drifted:\n got  %s\n want %s", got, sharedV3SignedMsgHex)
		}
		if got := hex.EncodeToString(body.Signature); got != sharedV3SigHex {
			t.Fatalf("signature drifted:\n got  %s\n want %s", got, sharedV3SigHex)
		}
	} else {
		t.Logf("LOCK ME: signed=%s sig=%s", hex.EncodeToString(msg), hex.EncodeToString(body.Signature))
	}
	if !body.Valid() {
		t.Fatal("vector body must verify")
	}
}

func TestAnnounceV3RoundTripAndValidation(t *testing.T) {
	id, infos := sharedV3Vector()
	// Pass unsorted to confirm the constructor sorts by ID.
	body := NewAnnounceBodyV3(id, 3, 4, 100, Capabilities(0x1F), []NeighborInfo{infos[1], infos[0]}, "alice", "", "test", nil)

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
	if len(decoded.NeighborInfo) != 2 {
		t.Fatalf("neighbor info not preserved: %+v", decoded.NeighborInfo)
	}
	if !decoded.NeighborInfo[0].ID.Less(decoded.NeighborInfo[1].ID) {
		t.Fatal("neighbor info must be sorted by ID")
	}
	got := decoded.NeighborInfo[0]
	if got.RSSI != -50 || got.TxPHY != PHY1M || got.RxPHY != PHY2M || got.Dir != DirectionOutgoing || got.AgeS != 12 {
		t.Fatalf("first neighbor info fields wrong: %+v", got)
	}
	if !decoded.Valid() {
		t.Fatal("decoded body must verify")
	}
	// NeighborIDs reads through to the v3 info list.
	if ids := decoded.NeighborIDs(); len(ids) != 2 || ids[0] != got.ID {
		t.Fatalf("NeighborIDs wrong: %v", ids)
	}

	// Tampering any signed neighbor field breaks the signature.
	tampered := decoded
	tampered.NeighborInfo = append([]NeighborInfo(nil), decoded.NeighborInfo...)
	tampered.NeighborInfo[0].RSSI = -51
	if tampered.Valid() {
		t.Fatal("tampered neighbor RSSI must not verify")
	}
}

func TestAnnounceV3ValidationRules(t *testing.T) {
	id, infos := sharedV3Vector()
	good := NewAnnounceBodyV3(id, 3, 4, 100, Capabilities(0x1F), infos, "alice", "", "test", nil)

	// neighbor info on a sub-v3 body is rejected.
	badVer := good
	badVer.AnnounceVersion = 2
	if badVer.Valid() {
		t.Fatal("neighbor info on a v2 body must be rejected")
	}

	// carrying both the bare list and neighbor info is rejected.
	mixed := good
	mixed.Neighbors = []NodeID{testNodeID(1)}
	if mixed.Valid() {
		t.Fatal("a body with both bare neighbors and neighbor info must be rejected")
	}

	// out-of-range PHY is rejected by NeighborInfo.Valid.
	if (NeighborInfo{ID: testNodeID(1), TxPHY: PHY(9)}).Valid() {
		t.Fatal("out-of-range PHY must be invalid")
	}
}

func TestAnnounceV3BuildAnnounceEmitsInfo(t *testing.T) {
	id := testIdentity(7)
	r := NewRouterForIdentity(id)
	r.Neighbors.Upsert(Neighbor{ID: testIdentity(8).NodeID(), Direction: DirectionOutgoing, RSSI: -42, TxPHY: PHY1M, RxPHY: PHY1M})

	dg, err := r.BuildAnnounce(Capabilities(CapRelay), 1, 1)
	if err != nil {
		t.Fatal(err)
	}
	ctrl, err := DecodeControl(dg.Payload)
	if err != nil {
		t.Fatal(err)
	}
	body, err := DecodeAnnounceBody(ctrl.Body)
	if err != nil {
		t.Fatal(err)
	}
	if body.AnnounceVersion != 3 {
		t.Fatalf("a node with live neighbors must announce v3, got %d", body.AnnounceVersion)
	}
	if len(body.NeighborInfo) != 1 || body.NeighborInfo[0].RSSI != -42 {
		t.Fatalf("neighbor info not populated from the table: %+v", body.NeighborInfo)
	}
	if !body.Valid() {
		t.Fatal("built announce must verify")
	}
}
