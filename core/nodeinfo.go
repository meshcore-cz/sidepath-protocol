package core

import "encoding/binary"

type NodeInfo struct {
	Version         byte
	PubKey          []byte
	ProvisionalCaps Capabilities
}

func EncodeNodeInfo(pubKey []byte, caps Capabilities) []byte {
	out := make([]byte, 1+PublicKeyBytes+2)
	out[0] = NodeInfoVersion
	copy(out[1:33], pubKey[:PublicKeyBytes])
	binary.LittleEndian.PutUint16(out[33:35], uint16(caps))
	return out
}

func DecodeNodeInfo(data []byte) (NodeInfo, bool) {
	if len(data) != 35 || data[0] != NodeInfoVersion {
		return NodeInfo{}, false
	}
	return NodeInfo{
		Version:         data[0],
		PubKey:          append([]byte(nil), data[1:33]...),
		ProvisionalCaps: Capabilities(binary.LittleEndian.Uint16(data[33:35])),
	}, true
}
