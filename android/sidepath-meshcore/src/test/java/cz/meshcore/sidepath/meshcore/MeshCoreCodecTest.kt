package cz.meshcore.sidepath.meshcore

import cz.meshcore.sidepath.chat.ChatChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the pure parts of the MeshCore codec. The JNI `decodeEnvelope` itself can't
 * run off-device, so we feed [MeshCoreCodec.parseEnvelopeJson] a JSON captured from the real
 * meshpkt CLI and verify the mapping, then prove the GRP_TXT payload decrypts via the existing
 * [ChatChannel] crypto on the well-known Public channel.
 *
 * Vector (meshpkt-cli encode-group-text-secret <PUBLIC_SECRET> "Tree" "Hello mesh"):
 *   packet = 154011f7837c5598e1edcff264a9673972fd38b5a44aab062964260c9c4252020365742b20
 */
class MeshCoreCodecTest {
    private val grpTxtEnvelopeJson = """
        {"hopCount":0,"hops":[],"isTransport":false,"pathHashSize":2,
         "payloadHex":"11f7837c5598e1edcff264a9673972fd38b5a44aab062964260c9c4252020365742b20",
         "route":"FLOOD","routeCode":1,"type":"GRP_TXT","typeCode":5,"version":0}
    """.trimIndent()

    @Test
    fun parsesGroupTextEnvelope() {
        val env = MeshCoreCodec.parseEnvelopeJson(grpTxtEnvelopeJson)
        assertNotNull(env)
        env!!
        assertEquals("GRP_TXT", env.type)
        assertEquals(5, env.typeCode)
        assertEquals("FLOOD", env.route)
        assertEquals(1, env.routeCode)
        assertEquals(0, env.hopCount)
        assertEquals(2, env.pathHashSize)
        assertTrue(env.hops.isEmpty())
        assertTrue(env.isGroupText)
        assertNull(env.transportCodes)
        assertEquals(35, env.payload.size)
        assertEquals(0x11, env.payload[0].toInt() and 0xFF) // Public channel hash
    }

    @Test
    fun groupTextPayloadDecryptsOnPublicChannel() {
        val env = MeshCoreCodec.parseEnvelopeJson(grpTxtEnvelopeJson)!!
        val decoded = ChatChannel.decodePayload(ChatChannel.PUBLIC_SECRET, env.payload)
        assertNotNull(decoded)
        assertEquals("Tree", decoded!!.senderLabel)
        assertEquals("Hello mesh", decoded.text)
    }

    @Test
    fun errorJsonReturnsNull() {
        assertNull(MeshCoreCodec.parseEnvelopeJson("""{"error":"bad packet"}"""))
        assertNull(MeshCoreCodec.parseEnvelopeJson("not json"))
    }

    // Vector: meshpkt-cli decode-advert of an encode-advert payload (name "Repeater1").
    private val advertJson = """
        {"hasGPS":false,"name":"Repeater1","nodeType":1,
         "publicKey":"0000000000000000000000000000000000000000000000000000000000000001",
         "sigVerified":false,"timestamp":1781273821}
    """.trimIndent()

    @Test
    fun parsesAdvert() {
        val adv = MeshCoreCodec.parseAdvertJson(advertJson)
        assertNotNull(adv)
        adv!!
        assertEquals("Repeater1", adv.name)
        assertEquals(1, adv.nodeType)
        assertEquals(1781273821L, adv.timestampSec)
        assertEquals("0000000000000000000000000000000000000000000000000000000000000001", adv.publicKeyHex)
        assertEquals(false, adv.hasGps)
        assertEquals(false, adv.sigVerified)
    }

    @Test
    fun advertErrorReturnsNull() {
        assertNull(MeshCoreCodec.parseAdvertJson("""{"error":"too short"}"""))
        assertNull(MeshCoreCodec.parseAdvertJson("nonsense"))
    }

    @Test
    fun parsesDirectText() {
        val directTextJson = """{"attempt":1,"text":"bridged dm test","timestamp":1700000000}"""
        val dm = MeshCoreCodec.parseDirectTextJson(directTextJson)
        assertNotNull(dm)
        dm!!
        assertEquals("bridged dm test", dm.text)
        assertEquals(1700000000L, dm.timestampSec)
        assertEquals(1, dm.attempt)
    }

    @Test
    fun directTextErrorReturnsNull() {
        assertNull(MeshCoreCodec.parseDirectTextJson("""{"error":"mac failed"}"""))
        assertNull(MeshCoreCodec.parseDirectTextJson("""{"timestamp":1700000000}"""))
        assertNull(MeshCoreCodec.parseDirectTextJson("nonsense"))
    }

    @Test
    fun parsesPathAckCrcOnlyForAckExtra() {
        assertEquals(0xd8779aeaL, MeshCoreCodec.parsePathAckCrcJson("""{"ackCrc":3631717098,"extraType":3}"""))
        assertNull(MeshCoreCodec.parsePathAckCrcJson("""{"ackCrc":3631717098,"extraType":5}"""))
        assertNull(MeshCoreCodec.parsePathAckCrcJson("""{"error":"no ack"}"""))
        assertNull(MeshCoreCodec.parsePathAckCrcJson("nonsense"))
    }

    @Test
    fun parsesReturnedPath() {
        val path = MeshCoreCodec.parsePathJson(
            """{"destHash":"29","srcHash":"03","path":"aabbccdd","extraType":3,"extra":"ea9a77d8","ackCrc":3631717098}""",
        )
        assertNotNull(path)
        path!!
        assertEquals("29", path.destHash)
        assertEquals("03", path.srcHash)
        assertEquals("aabbccdd", path.pathHex)
        assertEquals(3, path.extraType)
        assertEquals("ea9a77d8", path.extraHex)
        assertEquals(0xd8779aeaL, path.ackCrc)
    }

    @Test
    fun parsesMultipartAckCrcOnlyForAckInnerType() {
        assertEquals(
            0xd8779aeaL,
            MeshCoreCodec.parseMultipartAckCrcJson(
                """{"ackCrc":3631717098,"ackCrcHex":"d8779aea","innerPayloadHex":"ea9a77d8","innerType":"ACK","innerTypeCode":3,"remaining":1}""",
            ),
        )
        assertNull(MeshCoreCodec.parseMultipartAckCrcJson("""{"innerType":"TXT_MSG","innerTypeCode":2,"remaining":1}"""))
        assertNull(MeshCoreCodec.parseMultipartAckCrcJson("""{"error":"too short"}"""))
        assertNull(MeshCoreCodec.parseMultipartAckCrcJson("nonsense"))
    }
}
