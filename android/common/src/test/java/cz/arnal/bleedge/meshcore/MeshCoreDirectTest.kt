package cz.arnal.bleedge.meshcore

import cz.arnal.bleedge.protocol.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host test for [MeshCoreDirect]. The vector was produced by meshpkt
 * (firmware-compatible Identity.SharedSecret + DirectTextPacket): sender seed 00..1f,
 * recipient seed 20..3f, text "bridged dm test", ts 1700000000.
 */
class MeshCoreDirectTest {
    private val recipSeed = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
    private val senderPub = hex("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8")
    private val payload = hex("29038caa9a6150d4b79ae56a06b74af5c9fe2f5b2a80219fe6c6d3d232610bdebbe97226")

    @Test
    fun decodesDirectTextWithFirmwareCompatibleSharedSecret() {
        val identity = Identity.fromSeed(recipSeed)
        val dt = MeshCoreDirect.decode(identity, senderPub, payload)
        requireNotNull(dt)
        assertEquals("bridged dm test", dt.text)
        assertEquals(1700000000L, dt.timestampSec)
        assertEquals(0x29, dt.destHash) // recipient pubkey[0]
        assertEquals(0x03, dt.srcHash)  // sender pubkey[0]
        assertEquals(0, dt.attempt)
    }

    @Test
    fun ackCrcMatchesFirmwareFormula() {
        // meshpkt TextAckCRC(1700000000, 0, "bridged dm test", senderPub) = 0xd8779aea
        assertEquals(0xd8779aeaL, MeshCoreDirect.ackCrc(1700000000L, 0, "bridged dm test", senderPub))
    }

    @Test
    fun rejectsWrongSender() {
        val identity = Identity.fromSeed(recipSeed)
        val wrongPub = senderPub.copyOf().also { it[5] = (it[5] + 1).toByte() }
        assertNull(MeshCoreDirect.decode(identity, wrongPub, payload))
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val identity = Identity.fromSeed(recipSeed)
        val tampered = payload.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(MeshCoreDirect.decode(identity, senderPub, tampered))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
