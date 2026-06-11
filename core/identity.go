package core

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"runtime"
)

// PlatformDescription is the default node description for Go nodes: the OS and
// architecture, e.g. "linux/arm64" or "darwin/arm64".
func PlatformDescription() string {
	return runtime.GOOS + "/" + runtime.GOARCH
}

// SeedSize is the length of the Ed25519 identity seed in bytes (RFC 8032).
const SeedSize = 32

// Identity is a node's Ed25519 keypair, derived deterministically from a 32-byte
// seed. This mirrors the MeshCore node-identity format: the 32-byte public key is
// the canonical identity, and the routing NodeID is its first 8 bytes. The same
// seed produces the same keypair under any RFC 8032 implementation (Go stdlib,
// BouncyCastle on Android, orlp/ed25519 on the ESP32), so identities and
// signatures are interoperable across all platforms.
type Identity struct {
	Seed [SeedSize]byte
	Pub  ed25519.PublicKey  // 32 bytes
	Priv ed25519.PrivateKey // 64 bytes (seed || pub)
}

// NewIdentity generates a fresh random identity.
func NewIdentity() (*Identity, error) {
	var seed [SeedSize]byte
	if _, err := rand.Read(seed[:]); err != nil {
		return nil, err
	}
	return IdentityFromSeed(seed), nil
}

// IdentityFromSeed derives the Ed25519 keypair from a 32-byte seed.
func IdentityFromSeed(seed [SeedSize]byte) *Identity {
	priv := ed25519.NewKeyFromSeed(seed[:])
	pub := priv.Public().(ed25519.PublicKey)
	return &Identity{Seed: seed, Pub: pub, Priv: priv}
}

// NodeID is the routing address: the first 8 bytes of the Ed25519 public key.
func (id *Identity) NodeID() NodeID {
	return NodeIDFromPubKey(id.Pub)
}

// NodeIDFromPubKey derives the 8-byte routing NodeID from an Ed25519 public key.
func NodeIDFromPubKey(pub []byte) NodeID {
	var n NodeID
	copy(n[:], pub)
	return n
}

// AnnounceSignedMessage builds the canonical byte string that an ANNOUNCE's
// signature covers. It is a FIXED explicit layout (not the CBOR encoding, which
// is not byte-stable across libraries) and MUST be reproduced identically in the
// Kotlin and C++ ports:
//
//	pubkey         [32]
//	timestamp      [4]  uint32 little-endian (unix seconds, low 32 bits)
//	caps           [1]
//	seq            [4]  uint32 little-endian
//	neighbor_count [1]
//	neighbors      [neighbor_count * 8]   each NodeID, in ANNOUNCE order
func AnnounceSignedMessage(pub []byte, timestamp uint32, caps Capabilities, seq uint32, neighbors []NodeID) []byte {
	buf := make([]byte, 0, 32+4+1+4+1+len(neighbors)*8)
	buf = append(buf, pub...)
	var u [4]byte
	binary.LittleEndian.PutUint32(u[:], timestamp)
	buf = append(buf, u[:]...)
	buf = append(buf, byte(caps))
	binary.LittleEndian.PutUint32(u[:], seq)
	buf = append(buf, u[:]...)
	buf = append(buf, byte(len(neighbors)))
	for _, nb := range neighbors {
		buf = append(buf, nb[:]...)
	}
	return buf
}

// SignAnnounce signs the canonical announce message with this identity.
func (id *Identity) SignAnnounce(timestamp uint32, caps Capabilities, seq uint32, neighbors []NodeID) []byte {
	return ed25519.Sign(id.Priv, AnnounceSignedMessage(id.Pub, timestamp, caps, seq, neighbors))
}

// VerifyAnnounce verifies an announce signature against the carried public key.
func VerifyAnnounce(pub, sig []byte, timestamp uint32, caps Capabilities, seq uint32, neighbors []NodeID) bool {
	if len(pub) != ed25519.PublicKeySize || len(sig) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(ed25519.PublicKey(pub), AnnounceSignedMessage(pub, timestamp, caps, seq, neighbors), sig)
}
