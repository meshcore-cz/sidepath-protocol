package cz.arnal.bleedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CryptoTest {
    private fun seed(b: Int) = ByteArray(SEED_SIZE) { b.toByte() }

    @Test
    fun sealOpenRoundTrip() {
        val alice = Identity.fromSeed(seed(1))
        val bob = Identity.fromSeed(seed(2))

        val envelope = Crypto.sealChat("hello bob — ěščř 🚀", alice, bob.publicKey)
        val opened = Crypto.openChat(envelope, bob)
        assertEquals("hello bob — ěščř 🚀", opened)
    }

    @Test
    fun sharedSecretIsSymmetric() {
        val alice = Identity.fromSeed(seed(3))
        val bob = Identity.fromSeed(seed(4))

        // Bob can read what Alice sealed to Bob, and the carried sender key is Alice's.
        val envelope = Crypto.sealChat("symmetry", alice, bob.publicKey)
        assertEquals("symmetry", Crypto.openChat(envelope, bob))
        org.junit.Assert.assertArrayEquals(alice.publicKey, Crypto.envelopeSenderPubKey(envelope))
    }

    @Test
    fun wrongRecipientCannotOpen() {
        val alice = Identity.fromSeed(seed(5))
        val bob = Identity.fromSeed(seed(6))
        val eve = Identity.fromSeed(seed(7))

        val envelope = Crypto.sealChat("secret", alice, bob.publicKey)
        assertNull(Crypto.openChat(envelope, eve))
    }

    @Test
    fun edToX25519Deterministic() {
        val id = Identity.fromSeed(seed(8))
        val a = Crypto.ed25519PubToX25519(id.publicKey)
        val b = Crypto.ed25519PubToX25519(id.publicKey)
        org.junit.Assert.assertArrayEquals(a, b)
        assertEquals(32, a.size)
        assertNotEquals(0, a.count { it != 0.toByte() })
    }
}
