package cz.arnal.bleedge.meshcore

import cz.arnal.bleedge.protocol.Identity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MeshCore direct messages (TXT_MSG). Pure-Kotlin port of the firmware/meshpkt crypto so an
 * incoming MeshCore DM carried opaquely over BLEEdge can be decrypted on-device.
 *
 *   payload   = dest_hash[1] || src_hash[1] || mac[2] || ciphertext[N*16]
 *   key16     = X25519_shared_secret(our_seed, sender_pubkey)[0:16]     (firmware calcSharedSecret)
 *   mac       = HMAC-SHA256(key16 || zero16, ciphertext)[0:2]
 *   ciphertext= AES-128-ECB(key16, plaintext)
 *   plaintext = ts[4 LE] || flags[1] || utf8(text) || zero-pad to 16
 *   flags     = (txt_type << 2) | (attempt & 3)   (txt_type 0 for plain chat)
 *
 * The 16-byte shared secret comes from [Identity.sharedSecretWith], which is byte-identical to
 * MeshCore firmware `Identity::calcSharedSecret` (SHA-512(seed)[:32] clamp → ed→Montgomery → X25519)
 * and meshpkt `Identity.SharedSecret`. Crypto mirrors [cz.arnal.bleedge.chatproto.ChatChannel].
 */
object MeshCoreDirect {
    private const val SECRET_BYTES = 16
    private const val MAC_BYTES = 2

    data class DirectText(
        val destHash: Int,        // first byte of recipient's public key
        val srcHash: Int,         // first byte of sender's public key
        val timestampSec: Long,
        val attempt: Int,
        val text: String,
    )

    /**
     * Decrypts a MeshCore TXT_MSG [txtPayload] addressed (by hash) to us, from [senderPub] (the
     * sender's full 32-byte Ed25519 public key). Returns null if the shape is wrong or the MAC
     * fails (i.e. [senderPub] is not the real sender).
     */
    fun decode(identity: Identity, senderPub: ByteArray, txtPayload: ByteArray): DirectText? {
        if (senderPub.size != 32) return null
        if (txtPayload.size < 2 + MAC_BYTES + SECRET_BYTES) return null
        val destHash = txtPayload[0].toInt() and 0xFF
        val srcHash = txtPayload[1].toInt() and 0xFF
        val mac = txtPayload.copyOfRange(2, 2 + MAC_BYTES)
        val ct = txtPayload.copyOfRange(2 + MAC_BYTES, txtPayload.size)
        if (ct.isEmpty() || ct.size % 16 != 0) return null

        val key = identity.sharedSecretWith(senderPub).copyOf(SECRET_BYTES)
        if (!constantTimeEquals(mac, mac2(key, ct))) return null

        val pt = aesEcb(Cipher.DECRYPT_MODE, key, ct)
        if (pt.size < 5) return null
        val ts = ByteBuffer.wrap(pt, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val flags = pt[4].toInt() and 0xFF
        var end = pt.size
        while (end > 5 && pt[end - 1] == 0.toByte()) end--
        val text = String(pt, 5, end - 5, Charsets.UTF_8)
        return DirectText(destHash, srcHash, ts, flags and 0x03, text)
    }

    /**
     * Computes the ACK CRC the recipient must return for a received TXT_MSG, matching firmware
     * `BaseChatMesh::composeMsgPacket`: SHA-256( ts[4 LE] || (attempt&3) || utf8(text) ||
     * senderPub[32] )[0:4] as a little-endian unsigned 32-bit value.
     */
    fun ackCrc(timestampSec: Long, attempt: Int, text: String, senderPub: ByteArray): Long {
        val textB = text.toByteArray(Charsets.UTF_8)
        val temp = ByteBuffer.allocate(5 + textB.size).order(ByteOrder.LITTLE_ENDIAN)
        temp.putInt(timestampSec.toInt())
        temp.put((attempt and 0x03).toByte())
        temp.put(textB)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(temp.array())
        md.update(senderPub)
        val sum = md.digest()
        return ByteBuffer.wrap(sum, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    private fun aesEcb(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    private fun mac2(secret16: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = ByteArray(32) // secret16 || zero16
        System.arraycopy(secret16, 0, key, 0, SECRET_BYTES)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(ciphertext).copyOf(MAC_BYTES)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}
