package core

import (
	"encoding/hex"
	"testing"
)

var vectorSeed = [32]byte{
	0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
	0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
	0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
	0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
}

const vectorPubHex = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"

func TestIdentityVectorAndV3AnnounceSignature(t *testing.T) {
	id := IdentityFromSeed(vectorSeed)
	if got := hex.EncodeToString(id.Pub); got != vectorPubHex {
		t.Fatalf("pubkey vector drift: got %s want %s", got, vectorPubHex)
	}
	if got := id.NodeID().String(); got != vectorPubHex[:NodeIDBytes*2] {
		t.Fatalf("node id = %s want %s", got, vectorPubHex[:NodeIDBytes*2])
	}
	neighbors := []NodeID{testNodeID(1), testNodeID(2)}
	sig := id.SignAnnounce(3, 7, 1_700_000_000, Capabilities(CapReceiver|CapRelay|CapCodedPHY), neighbors, "name", "desc", "platform")
	if !VerifyAnnounce(id.Pub, sig, 3, 7, 1_700_000_000, Capabilities(CapReceiver|CapRelay|CapCodedPHY), neighbors, "name", "desc", "platform") {
		t.Fatal("self verify failed")
	}
	if VerifyAnnounce(id.Pub, sig, 3, 7, 1_700_000_000, Capabilities(CapReceiver), neighbors, "name", "desc", "platform") {
		t.Fatal("tampered caps verified")
	}
	if VerifyAnnounce(id.Pub, sig, 3, 7, 1_700_000_000, Capabilities(CapReceiver|CapRelay|CapCodedPHY), neighbors, "other", "desc", "platform") {
		t.Fatal("tampered metadata verified")
	}
}
