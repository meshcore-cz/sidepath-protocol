package meshcore

import (
	"crypto/sha256"
	"testing"
	"time"
)

func TestIsAdvert(t *testing.T) {
	cases := []struct {
		name  string
		first byte
		want  bool
	}{
		// payload type is header bits 5-2; ADVERT = 0x04 -> 0x04<<2 = 0x10.
		{"advert/flood", 0x10 | 0x01, true},
		{"advert/direct", 0x10 | 0x02, true},
		{"txtmsg", (0x02 << 2) | 0x01, false},
		{"ack", (0x03 << 2) | 0x00, false},
		{"grptxt", (0x05 << 2) | 0x01, false},
	}
	for _, c := range cases {
		if got := IsAdvert([]byte{c.first, 0xAA, 0xBB}); got != c.want {
			t.Errorf("%s: IsAdvert(%#02x)=%v, want %v", c.name, c.first, got, c.want)
		}
	}
	if IsAdvert(nil) {
		t.Error("IsAdvert(nil) should be false")
	}
}

func TestShouldForwardDedup(t *testing.T) {
	b := New(Config{DedupTTL: time.Hour})
	adv := []byte{0x11, 1, 2, 3}
	other := []byte{0x11, 9, 9, 9}

	if !b.shouldForward(adv) {
		t.Fatal("first sighting should forward")
	}
	if b.shouldForward(adv) {
		t.Fatal("identical advert within TTL should be suppressed")
	}
	if !b.shouldForward(other) {
		t.Fatal("a different advert should forward")
	}

	// Expire the dedup entry and confirm it forwards again.
	h := sha256.Sum256(adv)
	b.seen[h] = time.Now().Add(-2 * time.Hour)
	if !b.shouldForward(adv) {
		t.Fatal("advert past TTL should forward again")
	}
}
