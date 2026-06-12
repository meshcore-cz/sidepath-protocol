package core

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/binary"
	"math/big"

	"github.com/fxamacker/cbor/v2"
)

const (
	ChatVersion          = 1
	ChatMaxTextBytes     = 2048
	ChatDirectNonceBytes = 12
)

type ChatKind uint8

const (
	ChatPublicText  ChatKind = 1
	ChatDirectText  ChatKind = 2
	ChatTyping      ChatKind = 3
	ChatChannelText ChatKind = 4
)

type ChatEnvelope struct {
	Version uint8           `cbor:"1,keyasint"`
	Kind    ChatKind        `cbor:"2,keyasint"`
	Body    cbor.RawMessage `cbor:"3,keyasint"`
}

type ChatContext struct {
	DatagramID  DatagramID
	Source      NodeID
	Destination NodeID
}

func textWithinLimit(text string) bool { return len([]byte(text)) <= ChatMaxTextBytes }

func encodeChatEnvelope(kind ChatKind, body any) ([]byte, error) {
	bodyBytes, err := cbor.Marshal(body)
	if err != nil {
		return nil, err
	}
	return cbor.Marshal(ChatEnvelope{Version: ChatVersion, Kind: kind, Body: bodyBytes})
}

func DecodeChatEnvelope(payload []byte) (ChatEnvelope, error) {
	var env ChatEnvelope
	err := cbor.Unmarshal(payload, &env)
	return env, err
}

type chatPublicBody struct {
	SenderPublicKey []byte `cbor:"1,keyasint"`
	SentAt          int64  `cbor:"2,keyasint"`
	Text            string `cbor:"3,keyasint"`
	Signature       []byte `cbor:"4,keyasint"`
}

type PublicText struct {
	Text            string
	SentAt          int64
	SenderPublicKey []byte
}

func BuildPublicText(identity *Identity, ctx ChatContext, text string, sentAt int64) ([]byte, error) {
	if !textWithinLimit(text) {
		return nil, ErrTextTooLong
	}
	msg := publicTextSignedBytes(ctx, identity.Pub, sentAt, text)
	body := chatPublicBody{SenderPublicKey: identity.Pub, SentAt: sentAt, Text: text, Signature: ed25519.Sign(identity.Priv, msg)}
	return encodeChatEnvelope(ChatPublicText, body)
}

func OpenPublicText(payload []byte, ctx ChatContext) (PublicText, bool) {
	if !ctx.Destination.IsBroadcast() {
		return PublicText{}, false
	}
	env, err := DecodeChatEnvelope(payload)
	if err != nil || env.Version != ChatVersion || env.Kind != ChatPublicText {
		return PublicText{}, false
	}
	var body chatPublicBody
	if err := cbor.Unmarshal(env.Body, &body); err != nil {
		return PublicText{}, false
	}
	if len(body.SenderPublicKey) != PublicKeyBytes || NodeIDFromPubKey(body.SenderPublicKey) != ctx.Source || !textWithinLimit(body.Text) {
		return PublicText{}, false
	}
	msg := publicTextSignedBytes(ctx, body.SenderPublicKey, body.SentAt, body.Text)
	if !ed25519.Verify(ed25519.PublicKey(body.SenderPublicKey), msg, body.Signature) {
		return PublicText{}, false
	}
	return PublicText{Text: body.Text, SentAt: body.SentAt, SenderPublicKey: body.SenderPublicKey}, true
}

func publicTextSignedBytes(ctx ChatContext, senderPub []byte, sentAt int64, text string) []byte {
	t := []byte(text)
	out := bytes.NewBuffer(nil)
	out.Write(asciiNul("BLEEDGE-CHAT-PUBLIC-TEXT-V1"))
	out.Write(ctx.DatagramID[:])
	out.Write(ctx.Source[:])
	out.Write(ctx.Destination[:])
	out.Write(senderPub)
	var b [8]byte
	binary.LittleEndian.PutUint64(b[:], uint64(sentAt))
	out.Write(b[:])
	out.Write(appendLE16(nil, uint16(len(t))))
	out.Write(t)
	return out.Bytes()
}

type chatTypingBody struct {
	SenderPublicKey []byte `cbor:"1,keyasint"`
	SentAt          int64  `cbor:"2,keyasint"`
	Signature       []byte `cbor:"3,keyasint"`
}

func BuildTyping(identity *Identity, ctx ChatContext, sentAt int64) ([]byte, error) {
	msg := typingSignedBytes(ctx, identity.Pub, sentAt)
	body := chatTypingBody{SenderPublicKey: identity.Pub, SentAt: sentAt, Signature: ed25519.Sign(identity.Priv, msg)}
	return encodeChatEnvelope(ChatTyping, body)
}

func OpenTyping(payload []byte, ctx ChatContext) (int64, []byte, bool) {
	env, err := DecodeChatEnvelope(payload)
	if err != nil || env.Version != ChatVersion || env.Kind != ChatTyping {
		return 0, nil, false
	}
	var body chatTypingBody
	if err := cbor.Unmarshal(env.Body, &body); err != nil {
		return 0, nil, false
	}
	if len(body.SenderPublicKey) != PublicKeyBytes || NodeIDFromPubKey(body.SenderPublicKey) != ctx.Source {
		return 0, nil, false
	}
	if !ed25519.Verify(ed25519.PublicKey(body.SenderPublicKey), typingSignedBytes(ctx, body.SenderPublicKey, body.SentAt), body.Signature) {
		return 0, nil, false
	}
	return body.SentAt, body.SenderPublicKey, true
}

func typingSignedBytes(ctx ChatContext, senderPub []byte, sentAt int64) []byte {
	out := bytes.NewBuffer(nil)
	out.Write(asciiNul("BLEEDGE-CHAT-TYPING-V1"))
	out.Write(ctx.DatagramID[:])
	out.Write(ctx.Source[:])
	out.Write(ctx.Destination[:])
	out.Write(senderPub)
	out.Write(appendLE64(nil, uint64(sentAt)))
	return out.Bytes()
}

type chatDirectBody struct {
	SenderPublicKey []byte `cbor:"1,keyasint"`
	Nonce           []byte `cbor:"2,keyasint"`
	Ciphertext      []byte `cbor:"3,keyasint"`
}

type directPlaintext struct {
	SentAt int64  `cbor:"1,keyasint"`
	Text   string `cbor:"2,keyasint"`
}

type DirectText struct {
	Text            string
	SentAt          int64
	SenderPublicKey []byte
}

func SealDirectText(sender *Identity, recipientPublicKey []byte, ctx ChatContext, text string, sentAt int64) ([]byte, error) {
	if !textWithinLimit(text) {
		return nil, ErrTextTooLong
	}
	key, err := pairwiseKey(sender.Seed[:], sender.Pub, recipientPublicKey)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, ChatDirectNonceBytes)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	pt, err := cbor.Marshal(directPlaintext{SentAt: sentAt, Text: text})
	if err != nil {
		return nil, err
	}
	ct := gcm.Seal(nil, nonce, pt, directAAD(ctx, sender.Pub))
	return encodeChatEnvelope(ChatDirectText, chatDirectBody{SenderPublicKey: sender.Pub, Nonce: nonce, Ciphertext: ct})
}

func OpenDirectText(recipient *Identity, payload []byte, ctx ChatContext) (DirectText, bool) {
	if ctx.Destination != recipient.NodeID() {
		return DirectText{}, false
	}
	env, err := DecodeChatEnvelope(payload)
	if err != nil || env.Version != ChatVersion || env.Kind != ChatDirectText {
		return DirectText{}, false
	}
	var body chatDirectBody
	if err := cbor.Unmarshal(env.Body, &body); err != nil {
		return DirectText{}, false
	}
	if len(body.SenderPublicKey) != PublicKeyBytes || len(body.Nonce) != ChatDirectNonceBytes || NodeIDFromPubKey(body.SenderPublicKey) != ctx.Source {
		return DirectText{}, false
	}
	key, err := pairwiseKey(recipient.Seed[:], recipient.Pub, body.SenderPublicKey)
	if err != nil {
		return DirectText{}, false
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return DirectText{}, false
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return DirectText{}, false
	}
	pt, err := gcm.Open(nil, body.Nonce, body.Ciphertext, directAAD(ctx, body.SenderPublicKey))
	if err != nil {
		return DirectText{}, false
	}
	var plain directPlaintext
	if err := cbor.Unmarshal(pt, &plain); err != nil || !textWithinLimit(plain.Text) {
		return DirectText{}, false
	}
	return DirectText{Text: plain.Text, SentAt: plain.SentAt, SenderPublicKey: body.SenderPublicKey}, true
}

func DirectSenderPublicKey(payload []byte) []byte {
	env, err := DecodeChatEnvelope(payload)
	if err != nil || env.Kind != ChatDirectText {
		return nil
	}
	var body chatDirectBody
	if err := cbor.Unmarshal(env.Body, &body); err != nil || len(body.SenderPublicKey) != PublicKeyBytes {
		return nil
	}
	return body.SenderPublicKey
}

func directAAD(ctx ChatContext, senderPub []byte) []byte {
	out := bytes.NewBuffer(nil)
	out.Write(asciiNul("BLEEDGE-CHAT-DIRECT-AAD-V1"))
	out.Write(ctx.DatagramID[:])
	out.Write(ctx.Source[:])
	out.Write(ctx.Destination[:])
	out.Write(senderPub)
	out.Write(appendLE16(nil, uint16(ProtocolBLEEdgeChat)))
	out.WriteByte(ChatVersion)
	out.WriteByte(byte(ChatDirectText))
	return out.Bytes()
}

func pairwiseKey(mySeed, myPub, peerPub []byte) ([]byte, error) {
	myPriv := ed25519SeedToX25519Priv(mySeed)
	theirPub, err := ed25519PubToX25519(peerPub)
	if err != nil {
		return nil, err
	}
	priv, err := ecdh.X25519().NewPrivateKey(myPriv)
	if err != nil {
		return nil, err
	}
	pub, err := ecdh.X25519().NewPublicKey(theirPub)
	if err != nil {
		return nil, err
	}
	secret, err := priv.ECDH(pub)
	if err != nil {
		return nil, err
	}
	low, high := myPub, peerPub
	if bytes.Compare(low, high) > 0 {
		low, high = high, low
	}
	info := bytes.NewBuffer(nil)
	info.Write(asciiNul("BLEEDGE-CHAT-DIRECT-V1"))
	info.Write(low)
	info.Write(high)
	return hkdfSHA256(secret, nil, info.Bytes(), 32), nil
}

var curve25519P = new(big.Int).Sub(new(big.Int).Lsh(big.NewInt(1), 255), big.NewInt(19))

func ed25519PubToX25519(edPub []byte) ([]byte, error) {
	if len(edPub) != PublicKeyBytes {
		return nil, ErrInvalidPublicKey
	}
	le := append([]byte(nil), edPub...)
	le[31] &= 0x7f
	y := leToBig(le)
	oneMinusY := new(big.Int).Mod(new(big.Int).Sub(big.NewInt(1), y), curve25519P)
	if oneMinusY.Sign() == 0 {
		return nil, ErrInvalidPublicKey
	}
	u := new(big.Int).Mul(new(big.Int).Mod(new(big.Int).Add(big.NewInt(1), y), curve25519P), new(big.Int).ModInverse(oneMinusY, curve25519P))
	u.Mod(u, curve25519P)
	return bigToLe(u), nil
}

func ed25519SeedToX25519Priv(seed []byte) []byte {
	h := sha512.Sum512(seed)
	a := append([]byte(nil), h[:32]...)
	a[0] &= 248
	a[31] &= 127
	a[31] |= 64
	return a
}

func hkdfSHA256(secret, salt, info []byte, n int) []byte {
	if salt == nil {
		salt = make([]byte, sha256.Size)
	}
	prk := hmacSha256(salt, secret)
	var out, prev []byte
	counter := byte(1)
	for len(out) < n {
		m := hmac.New(sha256.New, prk)
		m.Write(prev)
		m.Write(info)
		m.Write([]byte{counter})
		prev = m.Sum(nil)
		out = append(out, prev...)
		counter++
	}
	return out[:n]
}

func hmacSha256(key, data []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(data)
	return m.Sum(nil)
}

func leToBig(le []byte) *big.Int {
	be := make([]byte, len(le))
	for i := range le {
		be[len(le)-1-i] = le[i]
	}
	return new(big.Int).SetBytes(be)
}

func bigToLe(v *big.Int) []byte {
	be := v.Bytes()
	out := make([]byte, 32)
	for i := 0; i < len(be) && i < 32; i++ {
		out[i] = be[len(be)-1-i]
	}
	return out
}

var (
	ErrTextTooLong      = fmtError("text too long")
	ErrInvalidPublicKey = fmtError("invalid public key")
)

type fmtError string

func (e fmtError) Error() string { return string(e) }
