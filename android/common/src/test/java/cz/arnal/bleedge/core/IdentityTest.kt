package cz.arnal.bleedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform Ed25519 vector. The expected public key and signature are
 * produced by the Go reference (`core/identity_test.go`) and the ESP32 firmware
 * (orlp/ed25519). BouncyCastle must reproduce them byte-for-byte, proving the
 * three implementations interoperate.
 */
class IdentityTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val seed = ByteArray(32) { it.toByte() } // 00 01 02 ... 1f
    private val caps = Capabilities(Capability.RECEIVER or Capability.RELAY or Capability.CODED_PHY) // 0x16
    private val seq = 7
    private val timestamp = 1_700_000_000
    private val neighbors = listOf(
        NodeID(ByteArray(8) { 0xaa.toByte() }),
        NodeID(ByteArray(8) { 0xbb.toByte() }),
    )

    private val expectedPub = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
    private val expectedSig =
        "e5ef6a4d3347a38de44b739c51c9c2add495c02397505576131c0978474a598a" +
        "aa31cae542fa7e67491b3c8f2e49f45701d9e5e106c873c59b971bcb12620408"

    @Test
    fun vectorMatchesGoAndFirmware() {
        val id = Identity.fromSeed(seed)
        assertEquals(expectedPub, hex(id.publicKey))
        assertEquals("03a107bff3ce10be", id.nodeId.toHexString())

        val sig = id.signAnnounce(timestamp, caps, seq, neighbors)
        assertEquals(expectedSig, hex(sig))

        assertTrue(Identity.verifyAnnounce(id.publicKey, sig, timestamp, caps, seq, neighbors))
        // Tamper: flipping caps must fail verification.
        assertFalse(Identity.verifyAnnounce(id.publicKey, sig, timestamp, Capabilities(caps.value + 1), seq, neighbors))
    }
}
