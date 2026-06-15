package pathrank

import (
	"testing"

	"github.com/meshcore-cz/sidepath-protocol/core"
)

// nid builds a distinct NodeID from a single byte, enough to tell test nodes apart.
func nid(b byte) core.NodeID {
	var id core.NodeID
	id[0] = b
	return id
}

var (
	self   = nid(0)
	relayA = nid(1)
	relayB = nid(2)
	dest   = nid(9)
)

// strong is a healthy, fresh, locally-observed BLE link.
func strong() Link {
	return Link{
		Transport: core.TransportBLE, TxPHY: core.PHY1M, RxPHY: core.PHY1M,
		RSSI: -45, AgeS: 1, Source: SourceLocal, HasInfo: true,
	}
}

func TestPrefersFewerHops(t *testing.T) {
	g := New(DefaultWeights())
	// Direct self->dest, plus a two-hop detour self->relayA->dest, all strong.
	g.AddLink(self, dest, strong())
	g.AddLink(self, relayA, strong())
	g.AddLink(relayA, dest, strong())

	routes := g.Routes(self, dest, Options{})
	if len(routes) != 2 {
		t.Fatalf("want 2 routes, got %d", len(routes))
	}
	if got := routes[0].Path(); len(got) != 1 || got[0] != dest {
		t.Fatalf("best route should be the direct hop, got %v", got)
	}
	if routes[0].Total >= routes[1].Total {
		t.Fatalf("direct route should cost less: %.1f vs %.1f", routes[0].Total, routes[1].Total)
	}
}

func TestPrefersStrongerLinkAtEqualHops(t *testing.T) {
	g := New(DefaultWeights())
	// Two one-relay routes of equal length; the relayA path has a weak last hop.
	weak := strong()
	weak.RSSI = -100
	g.AddLink(self, relayA, strong())
	g.AddLink(relayA, dest, weak)
	g.AddLink(self, relayB, strong())
	g.AddLink(relayB, dest, strong())

	routes := g.Routes(self, dest, Options{})
	if len(routes) != 2 {
		t.Fatalf("want 2 routes, got %d", len(routes))
	}
	if got := routes[0].Path(); got[0] != relayB {
		t.Fatalf("best route should go via the stronger relayB, got first hop %v", got[0])
	}
}

// A short route with one terrible hop should lose to a longer all-stable route —
// the multiplicative reliability model must make the bad hop dominate.
func TestWorstHopDominates(t *testing.T) {
	g := New(DefaultWeights())
	// 2-hop route with a near-dead middle link.
	terrible := strong()
	terrible.QualityQ8 = 8 // ~3% success
	g.AddLink(self, relayA, terrible)
	g.AddLink(relayA, dest, strong())
	// 3-hop route, every link rock solid.
	g.AddLink(self, relayB, strong())
	g.AddLink(relayB, nid(3), strong())
	g.AddLink(nid(3), dest, strong())

	routes := g.Routes(self, dest, Options{})
	best := routes[0].Path()
	if best[0] != relayB {
		t.Fatalf("the all-stable 3-hop route should win over a 2-hop route with a dead link, got %v", best)
	}
}

func TestQualityBeatsRSSIWhenPresent(t *testing.T) {
	w := DefaultWeights()
	// A link with strong RSSI but an explicit low quality score should be judged
	// by quality (the more trustworthy signal).
	l := strong()
	l.RSSI = -40
	l.QualityQ8 = 20
	if p := w.linkProb(l); p > 0.2 {
		t.Fatalf("quality score should dominate RSSI, got p=%.3f", p)
	}
}

func TestCodedPHYToleratesWeakerRSSI(t *testing.T) {
	w := DefaultWeights()
	oneM := strong()
	oneM.RSSI, oneM.TxPHY, oneM.RxPHY = -92, core.PHY1M, core.PHY1M
	coded := strong()
	coded.RSSI, coded.TxPHY, coded.RxPHY = -92, core.PHYCoded, core.PHYCoded
	if w.linkProb(coded) <= w.linkProb(oneM) {
		t.Fatalf("coded PHY should rate a weak RSSI higher than 1M does")
	}
}

func TestConfidenceOrdersSources(t *testing.T) {
	g := New(DefaultWeights())
	mk := func(src LinkSource) Cost {
		l := strong()
		l.Source = src
		return g.w.edgeCost(l)
	}
	if !(mk(SourceLocal).Total < mk(SourceForward).Total &&
		mk(SourceForward).Total < mk(SourceReverse).Total &&
		mk(SourceReverse).Total < mk(SourceLegacy).Total) {
		t.Fatal("cost should increase as link confidence drops local<forward<reverse<legacy")
	}
}

func TestUnknownLinkCostsMoreThanMeasured(t *testing.T) {
	g := New(DefaultWeights())
	g.AddLink(self, dest, Link{Source: SourceLegacy}) // ID-only edge, no metrics
	measured := New(DefaultWeights())
	measured.AddLink(self, dest, strong())

	unknown := g.Routes(self, dest, Options{})[0].Total
	known := measured.Routes(self, dest, Options{})[0].Total
	if unknown <= known {
		t.Fatalf("unknown link (%.1f) should cost more than a measured strong one (%.1f)", unknown, known)
	}
}

func TestStaleLinkCostsMore(t *testing.T) {
	g := New(DefaultWeights())
	fresh := strong()
	stale := strong()
	stale.AgeS = 80
	if g.w.edgeCost(stale).Total <= g.w.edgeCost(fresh).Total {
		t.Fatal("a stale link should cost more than a fresh one")
	}
}

// RoutesAll is the batch form of Routes: for any destination it must yield the
// same ranked routes a per-destination Routes call would, in one traversal.
func TestRoutesAllMatchesRoutes(t *testing.T) {
	g := New(DefaultWeights())
	g.AddLink(self, dest, strong())
	g.AddLink(self, relayA, strong())
	g.AddLink(relayA, dest, strong())
	g.AddLink(relayA, relayB, strong())
	g.AddLink(relayB, dest, strong())

	all := g.RoutesAll(self, Options{})
	for _, target := range []core.NodeID{dest, relayA, relayB} {
		want := g.Routes(self, target, Options{})
		got := all[target]
		if len(got) != len(want) {
			t.Fatalf("%v: RoutesAll has %d routes, Routes has %d", target, len(got), len(want))
		}
		for i := range want {
			if got[i].Total != want[i].Total || len(got[i].Hops) != len(want[i].Hops) {
				t.Fatalf("%v route #%d differs: all=%v/%dh want=%v/%dh", target, i,
					got[i].Total, len(got[i].Hops), want[i].Total, len(want[i].Hops))
			}
		}
	}
	// An unreachable node has no entry.
	if _, ok := all[nid(42)]; ok {
		t.Fatal("unreachable node should not appear in RoutesAll")
	}
}

func TestNoRouteReturnsEmpty(t *testing.T) {
	g := New(DefaultWeights())
	g.AddLink(self, relayA, strong()) // dead end, never reaches dest
	if routes := g.Routes(self, dest, Options{}); len(routes) != 0 {
		t.Fatalf("want no routes, got %d", len(routes))
	}
}

func TestMaxHopsBounds(t *testing.T) {
	g := New(DefaultWeights())
	g.AddLink(self, relayA, strong())
	g.AddLink(relayA, relayB, strong())
	g.AddLink(relayB, dest, strong())
	if routes := g.Routes(self, dest, Options{MaxHops: 2}); len(routes) != 0 {
		t.Fatalf("3-hop route should be excluded by MaxHops=2, got %d routes", len(routes))
	}
	if routes := g.Routes(self, dest, Options{MaxHops: 3}); len(routes) != 1 {
		t.Fatalf("3-hop route should be found with MaxHops=3, got %d routes", len(routes))
	}
}
