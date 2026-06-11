package core

import (
	"sync"
	"time"
)

// TopoNode represents a mesh node as seen in the global topology.
type TopoNode struct {
	ID        NodeID
	Caps      Capabilities
	Neighbors []NodeID
	Seq       uint32
	LastSeen  time.Time
}

// Topology maintains the global mesh graph learned via ANNOUNCE packets.
type Topology struct {
	mu     sync.RWMutex
	nodes  map[NodeID]*TopoNode
	expiry time.Duration
}

func NewTopology() *Topology {
	t := &Topology{
		nodes:  make(map[NodeID]*TopoNode),
		expiry: 90 * time.Second,
	}
	go t.reap()
	return t
}

// Update inserts or refreshes a topology node, ignoring stale (lower Seq) updates.
func (t *Topology) Update(n TopoNode) {
	t.mu.Lock()
	defer t.mu.Unlock()
	existing, ok := t.nodes[n.ID]
	if ok && existing.Seq >= n.Seq {
		return // stale update
	}
	n.LastSeen = time.Now()
	t.nodes[n.ID] = &n
}

// BFSPath returns the source-route path from `from` to `to` (excluding `from`, including `to`).
// Returns nil if no path found.
func (t *Topology) BFSPath(from, to NodeID) []NodeID {
	t.mu.RLock()
	defer t.mu.RUnlock()

	if from == to {
		return []NodeID{}
	}

	visited := map[NodeID]bool{from: true}
	prev := map[NodeID]NodeID{}
	queue := []NodeID{from}

	for len(queue) > 0 {
		cur := queue[0]
		queue = queue[1:]
		if cur == to {
			// Reconstruct path (excluding 'from', including 'to')
			var path []NodeID
			for cur != from {
				path = append([]NodeID{cur}, path...)
				cur = prev[cur]
			}
			return path
		}
		node, ok := t.nodes[cur]
		if !ok {
			continue
		}
		for _, nb := range node.Neighbors {
			if !visited[nb] {
				visited[nb] = true
				prev[nb] = cur
				queue = append(queue, nb)
			}
		}
	}
	return nil
}

// Nodes returns a snapshot of all known topology nodes.
func (t *Topology) Nodes() []TopoNode {
	t.mu.RLock()
	defer t.mu.RUnlock()
	out := make([]TopoNode, 0, len(t.nodes))
	for _, n := range t.nodes {
		out = append(out, *n)
	}
	return out
}

// GetNode returns a topology node by ID.
func (t *Topology) GetNode(id NodeID) (TopoNode, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	n, ok := t.nodes[id]
	if !ok {
		return TopoNode{}, false
	}
	return *n, true
}

// ExpireNode forcibly removes a node — useful for testing.
func (t *Topology) ExpireNode(id NodeID) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.nodes, id)
}

func (t *Topology) reap() {
	ticker := time.NewTicker(15 * time.Second)
	for range ticker.C {
		t.Reap()
	}
}

// Reap removes topology entries that have exceeded the expiry duration.
// Exported for testing.
func (t *Topology) Reap() {
	t.mu.Lock()
	now := time.Now()
	for id, n := range t.nodes {
		if now.Sub(n.LastSeen) > t.expiry {
			delete(t.nodes, id)
		}
	}
	t.mu.Unlock()
}
