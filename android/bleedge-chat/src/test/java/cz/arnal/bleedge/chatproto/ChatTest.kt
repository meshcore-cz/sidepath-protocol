package cz.arnal.bleedge.chatproto

import cz.arnal.bleedge.protocol.BLEEdge
import cz.arnal.bleedge.protocol.Datagram
import cz.arnal.bleedge.protocol.Identity
import cz.arnal.bleedge.protocol.NodeId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTest {
    private fun id(b: Int) = Identity.fromSeed(ByteArray(BLEEdge.SEED_BYTES) { ((it + b) and 0xFF).toByte() })
    private fun ctx(src: NodeId, dst: NodeId) = ChatContext(Datagram.newDatagramId(), src, dst)

    @Test fun publicTextSignVerify() {
        val alice = id(1)
        val c = ctx(alice.nodeId, NodeId.BROADCAST)
        val payload = ChatPublicText.build(alice, c, "hello mesh", sentAt = 1234)
        assertEquals(ChatKind.PUBLIC_TEXT, Chat.peekKind(payload))
        val v = ChatPublicText.open(payload, c)
        assertNotNull(v)
        assertEquals("hello mesh", v!!.text)
        assertEquals(1234L, v.sentAt)
        assertArrayEquals(alice.publicKey, v.senderPublicKey)
    }

    @Test fun publicTextRejectsForgedSource() {
        val alice = id(1)
        val mallory = id(2)
        // alice signs, but the outer source claims mallory -> binding fails
        val signed = ChatPublicText.build(alice, ctx(alice.nodeId, NodeId.BROADCAST), "x", 0)
        assertNull(ChatPublicText.open(signed, ctx(mallory.nodeId, NodeId.BROADCAST)))
    }

    @Test fun publicTextRejectsNonBroadcast() {
        val alice = id(1)
        val bob = id(3)
        val signed = ChatPublicText.build(alice, ctx(alice.nodeId, NodeId.BROADCAST), "x", 0)
        assertNull(ChatPublicText.open(signed, ctx(alice.nodeId, bob.nodeId)))
    }

    @Test fun directTextRoundTrip() {
        val alice = id(10)
        val bob = id(11)
        val c = ChatContext(Datagram.newDatagramId(), alice.nodeId, bob.nodeId)
        val payload = ChatDirectText.seal(alice, bob.publicKey, c, "secret hi", sentAt = 9)
        assertEquals(ChatKind.DIRECT_TEXT, Chat.peekKind(payload))
        assertArrayEquals(alice.publicKey, ChatDirectText.senderPublicKey(payload))
        val opened = ChatDirectText.open(bob, payload, c)
        assertNotNull(opened)
        assertEquals("secret hi", opened!!.text)
        assertEquals(9L, opened.sentAt)
    }

    @Test fun directTextWrongRecipientFails() {
        val alice = id(10)
        val bob = id(11)
        val eve = id(12)
        val c = ChatContext(Datagram.newDatagramId(), alice.nodeId, bob.nodeId)
        val payload = ChatDirectText.seal(alice, bob.publicKey, c, "for bob only", 0)
        // eve cannot open (and the destination binding also fails)
        assertNull(ChatDirectText.open(eve, payload, ChatContext(c.datagramId, alice.nodeId, eve.nodeId)))
    }

    @Test fun directTextAadBindsToDatagramId() {
        val alice = id(10)
        val bob = id(11)
        val c = ChatContext(Datagram.newDatagramId(), alice.nodeId, bob.nodeId)
        val payload = ChatDirectText.seal(alice, bob.publicKey, c, "bound", 0)
        // a relay that replays the ciphertext under a different datagram id must fail AEAD auth
        val tampered = ChatContext(Datagram.newDatagramId(), alice.nodeId, bob.nodeId)
        assertNull(ChatDirectText.open(bob, payload, tampered))
    }

    @Test fun typingSignVerify() {
        val alice = id(20)
        val bob = id(21)
        val c = ChatContext(Datagram.newDatagramId(), alice.nodeId, bob.nodeId)
        val payload = ChatTyping.build(alice, c, sentAt = 5)
        assertNotNull(ChatTyping.open(payload, c))
        // forged source fails
        assertNull(ChatTyping.open(payload, ChatContext(c.datagramId, bob.nodeId, alice.nodeId)))
    }

    @Test fun publicChannelHashIs0x11AndRoundTrips() {
        assertEquals(0x11.toByte(), ChatChannel.channelHash(ChatChannel.PUBLIC_SECRET))
        val payload = ChatChannel.build(ChatChannel.PUBLIC_SECRET, "alice", "gm channel", timestamp = 7)
        assertEquals(ChatKind.CHANNEL_TEXT, Chat.peekKind(payload))
        val d = ChatChannel.open(ChatChannel.PUBLIC_SECRET, payload)
        assertNotNull(d)
        assertEquals("alice", d!!.senderLabel)
        assertEquals("gm channel", d.text)
        // wrong secret -> null (hash mismatch / MAC fail)
        assertNull(ChatChannel.open(ChatChannel.namedSecret("other"), payload))
    }

    @Test fun namedChannelRoundTrip() {
        val secret = ChatChannel.namedSecret("rock climbers")
        val payload = ChatChannel.build(secret, "bob", "anyone out there?", 0)
        val d = ChatChannel.open(secret, payload)
        assertEquals("anyone out there?", d!!.text)
    }
}
