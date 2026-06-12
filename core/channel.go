package core

import (
	"crypto/aes"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"strings"

	"github.com/fxamacker/cbor/v2"
)

// MeshCore-compatible group/channel messaging, matching `meshcore-cz/meshpkt`
// and byte-identical to the Android `core.ChannelCrypto` (Kotlin).
//
//	payload     = channelHash[1] | mac[2] | ciphertext[...]
//	plaintext   = timestamp[4 LE] | flags[1] | "SenderName: MessageText"  (zero-padded to 16)
//	ciphertext  = AES-128-ECB(secret, plaintext)
//	mac         = HMAC-SHA256(key = secret16 ‖ zero16, ciphertext)[:2]
//	channelHash = SHA-256(secret)[0]

const ChannelSecretLen = 16
const channelMACLen = 2

// ChannelPublicPSK is MeshCore's default Public channel PSK (16 bytes); hash = 0x11.
var ChannelPublicPSK = mustHex("8b3387e9c5cdea6ac9e5edbaa115cd72")

// DeriveChannelSecret derives the 16-byte PSK for a name-only channel: SHA-256(name)[:16].
func DeriveChannelSecret(name string) []byte {
	h := sha256.Sum256([]byte(name))
	out := make([]byte, ChannelSecretLen)
	copy(out, h[:ChannelSecretLen])
	return out
}

func DeriveChannelPassphraseSecret(passphrase string) []byte {
	return DeriveChannelSecret(passphrase)
}

// ChannelHash is the 1-byte routing hash: SHA-256(secret)[0].
func ChannelHash(secret []byte) byte {
	s := make([]byte, ChannelSecretLen)
	copy(s, secret)
	h := sha256.Sum256(s)
	return h[0]
}

// SealChannel builds a MeshCore GRP_TXT payload for a channel message.
func SealChannel(secret []byte, sender, text string, timestamp uint32) []byte {
	body := []byte(sender + ": " + text)
	plain := make([]byte, 4+1+len(body))
	binary.LittleEndian.PutUint32(plain[0:4], timestamp)
	plain[4] = 0 // flags
	copy(plain[5:], body)
	padded := zeroPadBlock(plain, aes.BlockSize)

	ct := aesECB(secret[:ChannelSecretLen], padded, true)
	mac := channelMAC(secret, ct)

	out := make([]byte, 1+channelMACLen+len(ct))
	out[0] = ChannelHash(secret)
	copy(out[1:1+channelMACLen], mac)
	copy(out[1+channelMACLen:], ct)
	return out
}

type chatChannelBody struct {
	ChannelPayload []byte `cbor:"1,keyasint"`
}

func BuildChannelText(secret []byte, sender, text string, timestamp uint32) ([]byte, error) {
	if !textWithinLimit(text) {
		return nil, ErrTextTooLong
	}
	return encodeChatEnvelope(ChatChannelText, chatChannelBody{ChannelPayload: SealChannel(secret, sender, text, timestamp)})
}

func ChannelPayloadFromChat(payload []byte) []byte {
	env, err := DecodeChatEnvelope(payload)
	if err != nil || env.Version != ChatVersion || env.Kind != ChatChannelText {
		return nil
	}
	var body chatChannelBody
	if err := cbor.Unmarshal(env.Body, &body); err != nil {
		return nil
	}
	return body.ChannelPayload
}

// ChannelMessage is a decoded channel message.
type ChannelMessage struct {
	Sender    string
	Text      string
	Timestamp uint32
}

// OpenChannel decodes a GRP_TXT payload with secret. Returns ok=false if the channel
// hash doesn't match, the MAC fails, or the plaintext is malformed.
func OpenChannel(secret, payload []byte) (ChannelMessage, bool) {
	if len(payload) < 1+channelMACLen {
		return ChannelMessage{}, false
	}
	if payload[0] != ChannelHash(secret) {
		return ChannelMessage{}, false
	}
	mac := payload[1 : 1+channelMACLen]
	ct := payload[1+channelMACLen:]
	if len(ct) == 0 || len(ct)%aes.BlockSize != 0 {
		return ChannelMessage{}, false
	}
	if subtle.ConstantTimeCompare(mac, channelMAC(secret, ct)) != 1 {
		return ChannelMessage{}, false
	}
	pt := aesECB(secret[:ChannelSecretLen], ct, false)
	if len(pt) < 5 {
		return ChannelMessage{}, false
	}
	ts := binary.LittleEndian.Uint32(pt[0:4])
	body := strings.TrimRight(string(pt[5:]), "\x00")
	sender, text := "", body
	if i := strings.Index(body, ": "); i >= 0 {
		sender, text = body[:i], body[i+2:]
	}
	return ChannelMessage{Sender: sender, Text: text, Timestamp: ts}, true
}

func OpenChannelText(secret, payload []byte) (ChannelMessage, bool) {
	cp := ChannelPayloadFromChat(payload)
	if cp == nil {
		return ChannelMessage{}, false
	}
	return OpenChannel(secret, cp)
}

// ---- primitives -------------------------------------------------------------

func channelMAC(secret, ciphertext []byte) []byte {
	key := make([]byte, 32) // secret16 ‖ zero16
	copy(key, secret[:ChannelSecretLen])
	m := hmac.New(sha256.New, key)
	m.Write(ciphertext)
	return m.Sum(nil)[:channelMACLen]
}

// aesECB encrypts (enc=true) or decrypts a zero-padded buffer block-by-block.
func aesECB(key, data []byte, enc bool) []byte {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil
	}
	out := make([]byte, len(data))
	for i := 0; i < len(data); i += aes.BlockSize {
		if enc {
			block.Encrypt(out[i:i+aes.BlockSize], data[i:i+aes.BlockSize])
		} else {
			block.Decrypt(out[i:i+aes.BlockSize], data[i:i+aes.BlockSize])
		}
	}
	return out
}

func zeroPadBlock(data []byte, block int) []byte {
	rem := len(data) % block
	if rem == 0 {
		return data
	}
	return append(data, make([]byte, block-rem)...)
}

func mustHex(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}
