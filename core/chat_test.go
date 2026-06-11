package core

import (
	"bytes"
	"encoding/hex"
	"testing"
)

// RFC 7748 §5.2 first X25519 test vector.
func TestX25519RFC7748Vector(t *testing.T) {
	scalar, _ := hex.DecodeString("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
	u, _ := hex.DecodeString("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
	want, _ := hex.DecodeString("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")

	got := x25519(scalar, u)
	if !bytes.Equal(got, want) {
		t.Fatalf("x25519 = %x, want %x", got, want)
	}
}

func TestChatSealOpenRoundTrip(t *testing.T) {
	var seedA, seedB [SeedSize]byte
	for i := range seedA {
		seedA[i] = byte(i + 1)
		seedB[i] = byte(i + 100)
	}
	alice := IdentityFromSeed(seedA)
	bob := IdentityFromSeed(seedB)

	env, err := SealChat("ahoj — příliš žluťoučký 🦊", alice, bob.Pub)
	if err != nil {
		t.Fatalf("seal: %v", err)
	}
	got, ok := OpenChat(env, bob)
	if !ok {
		t.Fatal("bob could not open envelope")
	}
	if got != "ahoj — příliš žluťoučký 🦊" {
		t.Fatalf("plaintext = %q", got)
	}
	if !bytes.Equal(ChatEnvelopeSenderPub(env), alice.Pub) {
		t.Error("envelope sender pubkey mismatch")
	}
}

func TestChatWrongRecipientFails(t *testing.T) {
	var sa, sb, se [SeedSize]byte
	for i := range sa {
		sa[i], sb[i], se[i] = byte(i+1), byte(i+2), byte(i+3)
	}
	alice, bob, eve := IdentityFromSeed(sa), IdentityFromSeed(sb), IdentityFromSeed(se)
	env, _ := SealChat("secret", alice, bob.Pub)
	if _, ok := OpenChat(env, eve); ok {
		t.Error("eve must not be able to open the envelope")
	}
}

// ECDH must be symmetric: X25519(a, B) == X25519(b, A).
func TestX25519SharedSecretSymmetric(t *testing.T) {
	var sa, sb [SeedSize]byte
	for i := range sa {
		sa[i], sb[i] = byte(i+7), byte(i+9)
	}
	alice, bob := IdentityFromSeed(sa), IdentityFromSeed(sb)
	ab := x25519(ed25519SeedToX25519Priv(alice.Seed[:]), ed25519PubToX25519(bob.Pub))
	ba := x25519(ed25519SeedToX25519Priv(bob.Seed[:]), ed25519PubToX25519(alice.Pub))
	if !bytes.Equal(ab, ba) {
		t.Fatalf("shared secret asymmetric:\n ab=%x\n ba=%x", ab, ba)
	}
}
