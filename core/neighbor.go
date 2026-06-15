package core

import (
	"fmt"
	"sync"
	"time"
)

type ConnDirection uint8

const (
	DirectionOutgoing ConnDirection = 1
	DirectionIncoming ConnDirection = 2
)

// Neighbor represents a directly connected peer.
type Neighbor struct {
	ID        NodeID
	Direction ConnDirection
	LastSeen  time.Time
	RSSI      int
	TxPHY     PHY
	RxPHY     PHY
	Caps      Capabilities
	PublicKey []byte
}

func (n Neighbor) String() string {
	return fmt.Sprintf("%s rssi=%d tx=%s rx=%s relay=%v gateway=%v seen=%s",
		n.ID, n.RSSI, n.TxPHY, n.RxPHY,
		n.Caps.IsRelay(), n.Caps.IsGateway(),
		time.Since(n.LastSeen).Round(time.Second))
}

// NeighborTable is a thread-safe map of directly connected peers.
type NeighborTable struct {
	mu        sync.RWMutex
	neighbors map[NodeID]*Neighbor
	timeout   time.Duration
}

func NewNeighborTable() *NeighborTable {
	t := &NeighborTable{
		neighbors: make(map[NodeID]*Neighbor),
		timeout:   60 * time.Second,
	}
	go t.reap()
	return t
}

func (t *NeighborTable) Upsert(n Neighbor) {
	t.mu.Lock()
	defer t.mu.Unlock()
	n.LastSeen = time.Now()
	t.neighbors[n.ID] = &n
}

// SetRSSI refreshes an existing neighbor's signal strength in place (and its
// LastSeen, since hearing an advertisement is a sign of liveness). It is a no-op
// if the neighbor isn't in the table.
func (t *NeighborTable) SetRSSI(id NodeID, rssi int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if n, ok := t.neighbors[id]; ok {
		n.RSSI = rssi
		n.LastSeen = time.Now()
	}
}

func (t *NeighborTable) Get(id NodeID) (*Neighbor, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	n, ok := t.neighbors[id]
	return n, ok
}

func (t *NeighborTable) All() []Neighbor {
	t.mu.RLock()
	defer t.mu.RUnlock()
	out := make([]Neighbor, 0, len(t.neighbors))
	for _, n := range t.neighbors {
		out = append(out, *n)
	}
	return out
}

func (t *NeighborTable) Remove(id NodeID) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.neighbors, id)
}

func (t *NeighborTable) IDs() []NodeID {
	t.mu.RLock()
	defer t.mu.RUnlock()
	ids := make([]NodeID, 0, len(t.neighbors))
	for id := range t.neighbors {
		ids = append(ids, id)
	}
	return ids
}

func (t *NeighborTable) Reap() {
	t.mu.Lock()
	defer t.mu.Unlock()
	now := time.Now()
	for id, n := range t.neighbors {
		if now.Sub(n.LastSeen) > t.timeout {
			delete(t.neighbors, id)
		}
	}
}

func (t *NeighborTable) Touch(id NodeID) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if n, ok := t.neighbors[id]; ok {
		n.LastSeen = time.Now()
	}
}

func (t *NeighborTable) reap() {
	ticker := time.NewTicker(10 * time.Second)
	for range ticker.C {
		t.Reap()
	}
}
