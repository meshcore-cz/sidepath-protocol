package core

import (
	"bytes"
	"testing"
)

func TestPublicTextRoundTrip(t *testing.T) {
	alice := testIdentity(1)
	ctx := ChatContext{DatagramID: NewDatagramID(), Source: alice.NodeID(), Destination: BroadcastNodeID}
	payload, err := BuildPublicText(alice, ctx, "hello public", 123)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	got, ok := OpenPublicText(payload, ctx)
	if !ok {
		t.Fatal("open failed")
	}
	if got.Text != "hello public" || got.SentAt != 123 || !bytes.Equal(got.SenderPublicKey, alice.Pub) {
		t.Fatalf("got %+v", got)
	}
}

func TestTypingRoundTrip(t *testing.T) {
	alice := testIdentity(1)
	bob := testIdentity(30)
	ctx := ChatContext{DatagramID: NewDatagramID(), Source: alice.NodeID(), Destination: bob.NodeID()}
	payload, err := BuildTyping(alice, ctx, 456)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	sentAt, pub, ok := OpenTyping(payload, ctx)
	if !ok || sentAt != 456 || !bytes.Equal(pub, alice.Pub) {
		t.Fatalf("typing open failed sentAt=%d ok=%v", sentAt, ok)
	}
}

func TestDirectTextRoundTripAndWrongRecipientFails(t *testing.T) {
	alice := testIdentity(1)
	bob := testIdentity(30)
	eve := testIdentity(60)
	ctx := ChatContext{DatagramID: NewDatagramID(), Source: alice.NodeID(), Destination: bob.NodeID()}
	payload, err := SealDirectText(alice, bob.Pub, ctx, "secret", 789)
	if err != nil {
		t.Fatalf("seal: %v", err)
	}
	got, ok := OpenDirectText(bob, payload, ctx)
	if !ok || got.Text != "secret" || got.SentAt != 789 || !bytes.Equal(got.SenderPublicKey, alice.Pub) {
		t.Fatalf("open failed: %+v ok=%v", got, ok)
	}
	if _, ok := OpenDirectText(eve, payload, ChatContext{DatagramID: ctx.DatagramID, Source: alice.NodeID(), Destination: eve.NodeID()}); ok {
		t.Fatal("wrong recipient opened direct text")
	}
}
