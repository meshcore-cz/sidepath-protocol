package core

// NODE_INFO characteristic payload layout (ProtocolVersion 2):
//
//	version(1) | pubkey(32) | caps(1) | descLen(1) | desc | nameLen(1) | name | platLen(1) | platform
//
// The desc/name/platform trailers are length-prefixed and appended in that order so
// a reader that stops after `desc` still parses version/pubkey/caps/desc. All three
// strings are unsigned, informational text — never use them for routing or trust.
// Mirrored byte-for-byte in Kotlin (GattServer/GattClient) and C++ (mesh.cpp). Spec:
// docs/PROTOCOL.md.

// NodeInfo is a decoded NODE_INFO characteristic value.
type NodeInfo struct {
	Version     byte
	PubKey      []byte
	Caps        Capabilities
	Description string
	Name        string
	Platform    string
}

func clipStr255(s string) []byte {
	b := []byte(s)
	if len(b) > 255 {
		b = b[:255]
	}
	return b
}

// EncodeNodeInfo builds the NODE_INFO characteristic value. pubKey must be 32 bytes.
func EncodeNodeInfo(version byte, pubKey []byte, caps Capabilities, description, name, platform string) []byte {
	desc := clipStr255(description)
	nm := clipStr255(name)
	plat := clipStr255(platform)
	out := make([]byte, 0, 34+3+len(desc)+len(nm)+len(plat))
	out = append(out, version)
	out = append(out, pubKey[:32]...)
	out = append(out, byte(caps))
	out = append(out, byte(len(desc)))
	out = append(out, desc...)
	out = append(out, byte(len(nm)))
	out = append(out, nm...)
	out = append(out, byte(len(plat)))
	out = append(out, plat...)
	return out
}

// DecodeNodeInfo parses a NODE_INFO value. Returns ok=false if it's too short to
// contain version/pubkey/caps; the trailing strings are optional (best-effort).
func DecodeNodeInfo(data []byte) (NodeInfo, bool) {
	if len(data) < 34 {
		return NodeInfo{}, false
	}
	ni := NodeInfo{
		Version: data[0],
		PubKey:  append([]byte(nil), data[1:33]...),
		Caps:    Capabilities(data[33]),
	}
	off := 34
	readStr := func() (string, bool) {
		if off >= len(data) {
			return "", false
		}
		n := int(data[off])
		off++
		if off+n > len(data) {
			return "", false
		}
		s := string(data[off : off+n])
		off += n
		return s, true
	}
	if s, ok := readStr(); ok {
		ni.Description = s
	}
	if s, ok := readStr(); ok {
		ni.Name = s
	}
	if s, ok := readStr(); ok {
		ni.Platform = s
	}
	return ni, true
}
