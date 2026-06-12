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

// Identity is a node's Ed25519 keypair, derived deterministically from a 32-byte
// seed. The 32-byte public key is the canonical identity, and the routing NodeID
// is its first 10 bytes.
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

// NodeID is the routing address: the first 10 bytes of the Ed25519 public key.
func (id *Identity) NodeID() NodeID {
	return NodeIDFromPubKey(id.Pub)
}

// NodeIDFromPubKey derives the 10-byte routing NodeID from an Ed25519 public key.
func NodeIDFromPubKey(pub []byte) NodeID {
	var n NodeID
	copy(n[:], pub)
	return n
}

func asciiNul(label string) []byte {
	out := make([]byte, len(label)+1)
	copy(out, label)
	return out
}

func appendLE16(buf []byte, v uint16) []byte {
	var b [2]byte
	binary.LittleEndian.PutUint16(b[:], v)
	return append(buf, b[:]...)
}

func appendLE32(buf []byte, v uint32) []byte {
	var b [4]byte
	binary.LittleEndian.PutUint32(b[:], v)
	return append(buf, b[:]...)
}

func appendLE64(buf []byte, v uint64) []byte {
	var b [8]byte
	binary.LittleEndian.PutUint64(b[:], v)
	return append(buf, b[:]...)
}

func appendString16(buf []byte, s string) []byte {
	b := []byte(s)
	buf = appendLE16(buf, uint16(len(b)))
	return append(buf, b...)
}

func AnnounceSignedMessage(pub []byte, epoch uint64, seq uint32, timestamp int64, caps Capabilities, neighbors []NodeID, name, desc, platform string) []byte {
	buf := make([]byte, 0, 128+len(neighbors)*NodeIDBytes+len(name)+len(desc)+len(platform))
	buf = append(buf, asciiNul("BLEEDGE-ANNOUNCE-V1")...)
	buf = append(buf, AnnounceVersion)
	buf = append(buf, pub...)
	buf = appendLE64(buf, epoch)
	buf = appendLE32(buf, seq)
	buf = appendLE64(buf, uint64(timestamp))
	buf = appendLE16(buf, uint16(caps))
	buf = appendLE16(buf, uint16(len(neighbors)))
	for _, nb := range neighbors {
		buf = append(buf, nb[:]...)
	}
	buf = appendString16(buf, name)
	buf = appendString16(buf, desc)
	buf = appendString16(buf, platform)
	return buf
}

// SignAnnounce signs the canonical announce message with this identity.
func (id *Identity) SignAnnounce(epoch uint64, seq uint32, timestamp int64, caps Capabilities, neighbors []NodeID, name, desc, platform string) []byte {
	return ed25519.Sign(id.Priv, AnnounceSignedMessage(id.Pub, epoch, seq, timestamp, caps, neighbors, name, desc, platform))
}

// VerifyAnnounce verifies an announce signature against the carried public key.
func VerifyAnnounce(pub, sig []byte, epoch uint64, seq uint32, timestamp int64, caps Capabilities, neighbors []NodeID, name, desc, platform string) bool {
	if len(pub) != ed25519.PublicKeySize || len(sig) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(ed25519.PublicKey(pub), AnnounceSignedMessage(pub, epoch, seq, timestamp, caps, neighbors, name, desc, platform), sig)
}
