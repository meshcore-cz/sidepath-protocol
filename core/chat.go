package core

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"math/big"

	"github.com/fxamacker/cbor/v2"
)

// Chat end-to-end encryption, byte-compatible with the Android `core.Crypto`
// (Kotlin). Identity keys are Ed25519; for encryption both keys are mapped to
// their X25519 (Curve25519) form (the libsodium ed25519→curve25519 transform),
// a static↔static X25519 ECDH shared secret is derived, run through HKDF-SHA256
// (info "bleedge-chat-v1") to a 32-byte AES key, and sealed with AES-256-GCM.
//
// The shared secret is symmetric, so the recipient re-derives the same key from
// the sender's public key carried in the envelope. Implemented with the standard
// library only (RFC 7748 X25519 via math/big; no external crypto dependency).

const chatHKDFInfo = "bleedge-chat-v1"

// curve25519P = 2^255 - 19, the Curve25519 field prime.
var curve25519P = new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 255), big.NewInt(19))

// chatEnvelope is the CBOR wire form of an encrypted DM (integer keys 1/2/3,
// matching the Kotlin sealChat output).
type chatEnvelope struct {
	SenderPub []byte `cbor:"1,keyasint"`
	IV        []byte `cbor:"2,keyasint"`
	CT        []byte `cbor:"3,keyasint"`
}

// SealChat encrypts plain for the holder of recipientEdPub (their 32-byte Ed25519
// public key), authenticated to the sender identity. Returns the CBOR envelope.
func SealChat(plain string, sender *Identity, recipientEdPub []byte) ([]byte, error) {
	myPriv := ed25519SeedToX25519Priv(sender.Seed[:])
	theirPub := ed25519PubToX25519(recipientEdPub)
	key := chatDeriveKey(x25519(myPriv, theirPub))

	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	iv := make([]byte, gcm.NonceSize()) // 12 bytes
	if _, err := rand.Read(iv); err != nil {
		return nil, err
	}
	ct := gcm.Seal(nil, iv, []byte(plain), nil)

	return cbor.Marshal(chatEnvelope{SenderPub: sender.Pub, IV: iv, CT: ct})
}

// OpenChat decrypts a SealChat envelope addressed to me. Returns the plaintext and
// true, or ("", false) if the envelope is malformed / not decryptable with this
// identity.
func OpenChat(envelope []byte, me *Identity) (string, bool) {
	var env chatEnvelope
	if err := cbor.Unmarshal(envelope, &env); err != nil || len(env.SenderPub) != 32 {
		return "", false
	}
	myPriv := ed25519SeedToX25519Priv(me.Seed[:])
	theirPub := ed25519PubToX25519(env.SenderPub)
	key := chatDeriveKey(x25519(myPriv, theirPub))

	block, err := aes.NewCipher(key)
	if err != nil {
		return "", false
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil || len(env.IV) != gcm.NonceSize() {
		return "", false
	}
	pt, err := gcm.Open(nil, env.IV, env.CT, nil)
	if err != nil {
		return "", false
	}
	return string(pt), true
}

// ChatEnvelopeSenderPub returns the sender's Ed25519 public key carried in an
// envelope, or nil.
func ChatEnvelopeSenderPub(envelope []byte) []byte {
	var env chatEnvelope
	if err := cbor.Unmarshal(envelope, &env); err != nil {
		return nil
	}
	return env.SenderPub
}

// ---- key conversion & ECDH --------------------------------------------------

// ed25519PubToX25519 converts a 32-byte Ed25519 public key (compressed Edwards
// point) to the equivalent X25519 (Montgomery) public key: u = (1+y)/(1-y) mod p.
func ed25519PubToX25519(edPub []byte) []byte {
	le := make([]byte, 32)
	copy(le, edPub)
	le[31] &= 0x7f // clear the x-sign bit
	y := leToBig(le)
	oneMinusY := new(big.Int).Mod(new(big.Int).Sub(big.NewInt(1), y), curve25519P)
	onePlusY := new(big.Int).Mod(new(big.Int).Add(big.NewInt(1), y), curve25519P)
	u := new(big.Int).Mul(onePlusY, new(big.Int).ModInverse(oneMinusY, curve25519P))
	u.Mod(u, curve25519P)
	return bigToLe(u)
}

// ed25519SeedToX25519Priv derives the X25519 private scalar from an Ed25519 seed:
// a = clamp(SHA-512(seed)[0:32]).
func ed25519SeedToX25519Priv(seed []byte) []byte {
	h := sha512.Sum512(seed)
	a := make([]byte, 32)
	copy(a, h[:32])
	a[0] &= 248
	a[31] &= 127
	a[31] |= 64
	return a
}

func chatDeriveKey(secret []byte) []byte {
	// HKDF-SHA256 (RFC 5869): salt nil → zero key; one expand block is enough for 32 bytes.
	prk := hmacSha256(make([]byte, sha256.Size), secret) // extract
	t := hmacSha256(prk, append([]byte(chatHKDFInfo), 0x01))
	return t[:32]
}

func hmacSha256(key, data []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(data)
	return m.Sum(nil)
}

// x25519 performs the RFC 7748 X25519 scalar multiplication (Montgomery ladder).
func x25519(scalar, uIn []byte) []byte {
	// Clamp the scalar.
	e := make([]byte, 32)
	copy(e, scalar)
	e[0] &= 248
	e[31] &= 127
	e[31] |= 64
	k := leToBig(e)

	// Decode the u-coordinate (mask the high bit).
	uc := make([]byte, 32)
	copy(uc, uIn)
	uc[31] &= 0x7f
	x1 := leToBig(uc)

	x2 := big.NewInt(1)
	z2 := big.NewInt(0)
	x3 := new(big.Int).Set(x1)
	z3 := big.NewInt(1)
	a24 := big.NewInt(121665)
	swap := 0

	for t := 254; t >= 0; t-- {
		kt := int(k.Bit(t))
		swap ^= kt
		cswap(swap, x2, x3)
		cswap(swap, z2, z3)
		swap = kt

		A := fadd(x2, z2)
		AA := fmul(A, A)
		B := fsub(x2, z2)
		BB := fmul(B, B)
		E := fsub(AA, BB)
		C := fadd(x3, z3)
		D := fsub(x3, z3)
		DA := fmul(D, A)
		CB := fmul(C, B)
		x3 = fmul(fadd(DA, CB), fadd(DA, CB))
		z3 = fmul(x1, fmul(fsub(DA, CB), fsub(DA, CB)))
		x2 = fmul(AA, BB)
		z2 = fmul(E, fadd(AA, fmul(a24, E)))
	}
	cswap(swap, x2, x3)
	cswap(swap, z2, z3)

	res := fmul(x2, new(big.Int).ModInverse(z2, curve25519P))
	return bigToLe(res)
}

func fadd(a, b *big.Int) *big.Int { return new(big.Int).Mod(new(big.Int).Add(a, b), curve25519P) }
func fsub(a, b *big.Int) *big.Int { return new(big.Int).Mod(new(big.Int).Sub(a, b), curve25519P) }
func fmul(a, b *big.Int) *big.Int { return new(big.Int).Mod(new(big.Int).Mul(a, b), curve25519P) }

func cswap(swap int, a, b *big.Int) {
	if swap == 1 {
		tmp := new(big.Int).Set(a)
		a.Set(b)
		b.Set(tmp)
	}
}

func leToBig(le []byte) *big.Int {
	be := make([]byte, len(le))
	for i := range le {
		be[len(le)-1-i] = le[i]
	}
	return new(big.Int).SetBytes(be)
}

func bigToLe(v *big.Int) []byte {
	be := v.Bytes() // big-endian, may be shorter than 32
	out := make([]byte, 32)
	for i := 0; i < len(be) && i < 32; i++ {
		out[i] = be[len(be)-1-i]
	}
	return out
}
