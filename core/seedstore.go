package core

import (
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// LoadOrCreateIdentity loads a 32-byte hex Ed25519 seed from path and derives the
// identity, or generates a new identity and persists its seed. The file stores
// the seed only; the public key and NodeID are always derived from it.
func LoadOrCreateIdentity(path string) (*Identity, error) {
	if data, err := os.ReadFile(path); err == nil {
		b, err := hex.DecodeString(strings.TrimSpace(string(data)))
		if err != nil || len(b) != SeedSize {
			return nil, fmt.Errorf("invalid seed file %s", path)
		}
		var seed [SeedSize]byte
		copy(seed[:], b)
		return IdentityFromSeed(seed), nil
	}

	id, err := NewIdentity()
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create %s: %w", filepath.Dir(path), err)
	}
	if err := os.WriteFile(path, []byte(hex.EncodeToString(id.Seed[:])+"\n"), 0o600); err != nil {
		return nil, fmt.Errorf("write seed: %w", err)
	}
	return id, nil
}
