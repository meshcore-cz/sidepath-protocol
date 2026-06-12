package core

import (
	"sync"
	"time"
)

type DropReason string

const (
	DropDuplicate      DropReason = "duplicate"
	DropExpired        DropReason = "expired"
	DropLoop           DropReason = "loop"
	DropBadTTL         DropReason = "bad-ttl"
	DropBadRoute       DropReason = "bad-route"
	DropInvalidVersion DropReason = "invalid-version"
	DropMalformed      DropReason = "malformed"
	DropNotNextHop     DropReason = "not-next-hop"
	DropPeerNotAllowed DropReason = "peer-not-allowed"
	DropPHYNotCoded    DropReason = "phy-not-coded"
	DropBadSignature   DropReason = "bad-signature"
)

// DedupCache is a thread-safe seen-datagram cache keyed by DatagramID.
type DedupCache struct {
	mu      sync.Mutex
	entries map[DatagramID]time.Time
	maxSize int
	ttl     time.Duration
}

func NewDedupCache() *DedupCache {
	c := &DedupCache{
		entries: make(map[DatagramID]time.Time),
		maxSize: 4096,
		ttl:     5 * time.Minute,
	}
	go c.reap()
	return c
}

// SeenOrAdd returns true if the packet ID was already seen (and still within TTL).
// If not seen, it adds the entry and returns false.
func (c *DedupCache) SeenOrAdd(id DatagramID) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if t, ok := c.entries[id]; ok && time.Since(t) < c.ttl {
		return true
	}
	if len(c.entries) >= c.maxSize {
		// evict oldest
		var oldest DatagramID
		var oldestT time.Time
		for k, v := range c.entries {
			if oldestT.IsZero() || v.Before(oldestT) {
				oldest, oldestT = k, v
			}
		}
		delete(c.entries, oldest)
	}
	c.entries[id] = time.Now()
	return false
}

func (c *DedupCache) reap() {
	ticker := time.NewTicker(1 * time.Minute)
	for range ticker.C {
		c.mu.Lock()
		now := time.Now()
		for k, t := range c.entries {
			if now.Sub(t) > c.ttl {
				delete(c.entries, k)
			}
		}
		c.mu.Unlock()
	}
}
