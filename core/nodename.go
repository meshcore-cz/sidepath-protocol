package core

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
)

// Deterministic "bootstrap" node names. A node's name is a real wire field (see
// AnnouncePayload.Name / NODE_INFO), user-overridable, but when an identity is first
// created it gets a default name derived purely from its public key — so a fresh node
// always has a friendly, stable label and other nodes can show the same default as a
// fallback until they receive the on-wire name.
//
// The algorithm MUST match the Kotlin (NodeName.kt) and C++ (mesh.cpp) ports so the
// fallback a peer computes equals the default the owner actually uses. It is specified
// in docs/PROTOCOL.md. Input is MD5 of the lowercase hex of the 32-byte public key.
var nameWords = []string{
	"barrel", "cedar", "ember", "harbor", "lantern", "meadow", "pebble", "quartz",
	"ripple", "summit", "thistle", "willow", "anchor", "brook", "copper", "dune",
	"fable", "garnet", "hollow", "ivy", "juniper", "kettle", "lichen", "marble",
	"nectar", "orchard", "pine", "raven", "sparrow", "timber", "velvet", "walnut",
	"amber", "birch", "comet", "delta", "echo", "ferry", "glade", "heron",
	"indigo", "jasper", "kelp", "lotus", "moss", "north", "opal", "prairie",
	"return", "saffron", "tundra", "umber", "violet", "wander", "yarrow", "zephyr",
	"basalt", "cinder", "dapple", "fennel", "gully", "hazel", "isle", "kestrel",
}

var numberWords = []string{
	"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
}

// DefaultNodeName derives a deterministic, varied three-token bootstrap name from an
// Ed25519 public key (e.g. "barrel-two-return", "five-meadow-one", "cedar-pine-eight").
// Returns "" if the key is not 32 bytes.
func DefaultNodeName(pub []byte) string {
	if len(pub) != 32 {
		return ""
	}
	d := md5.Sum([]byte(hex.EncodeToString(pub)))
	w0 := nameWords[int(d[0])%len(nameWords)]
	num := numberWords[int(d[1])%len(numberWords)]
	w2 := nameWords[int(d[2])%len(nameWords)]
	switch int(d[3]) % 3 {
	case 1:
		num2 := numberWords[int(d[4])%len(numberWords)]
		return fmt.Sprintf("%s-%s-%s", num, w0, num2)
	case 2:
		return fmt.Sprintf("%s-%s-%s", w0, w2, num)
	default:
		return fmt.Sprintf("%s-%s-%s", w0, num, w2)
	}
}
