package cz.meshcore.sidepath.chat

import com.upokecenter.cbor.CBORObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MeshCore-compatible encrypted group channels (§7). A channel is identified by a
 * 16-byte pre-shared secret; the encrypted `channel_payload` mirrors the MeshCore
 * `GRP_TXT` layout so the same crypto and gateway mapping apply. Carried as a
 * CHANNEL_TEXT chat message (kind 4), body { 1: channel_payload }.
 *
 *   channel_payload = channel_hash[1] || mac[2] || ciphertext[N]
 *   channel_hash    = SHA-256(secret)[0]
 *   mac             = HMAC-SHA256(secret||zero16, ciphertext)[0:2]
 *   plaintext       = ts[4 LE] || flags[1] || utf8("label: text") || zero-pad to 16
 *   ciphertext      = AES-128-ECB(secret, plaintext)
 */
object ChatChannel {
    const val SECRET_BYTES: Int = 16
    private const val MAC_BYTES: Int = 2

    /** MeshCore's well-known Public channel secret (channel hash 0x11). */
    val PUBLIC_SECRET: ByteArray = hex("8b3387e9c5cdea6ac9e5edbaa115cd72")

    /** Named-channel secret derivation: SHA-256(utf8(name))[0:16] (byte-sensitive). */
    fun namedSecret(name: String): ByteArray = sha256(name.toByteArray(Charsets.UTF_8)).copyOf(SECRET_BYTES)

    /** Passphrase secret derivation: SHA-256(utf8(passphrase))[0:16] (byte-sensitive). */
    fun passphraseSecret(passphrase: String): ByteArray = namedSecret(passphrase)

    /** The 1-byte channel hash used as a lookup hint: SHA-256(secret)[0]. */
    fun channelHash(secret: ByteArray): Byte = sha256(secret.copyOf(SECRET_BYTES))[0]

    data class Decoded(val senderLabel: String, val text: String, val timestamp: Long)

    /** Builds a CHANNEL_TEXT chat payload for [secret]. */
    fun build(secret: ByteArray, senderLabel: String, text: String, timestamp: Long): ByteArray {
        require(Chat.textWithinLimit(text)) { "text exceeds ${Chat.MAX_TEXT_BYTES} bytes" }
        val channelPayload = sealGrpTxt(secret, senderLabel, text, timestamp)
        val body = CBORObject.NewOrderedMap()
        body[CBORObject.FromObject(1)] = CBORObject.FromObject(channelPayload)
        return ChatEnvelope(Chat.VERSION, ChatKind.CHANNEL_TEXT, body).encode()
    }

    /** Extracts the raw `channel_payload` (MeshCore GRP_TXT bytes) from a CHANNEL_TEXT payload. */
    fun channelPayload(payload: ByteArray): ByteArray? = runCatching {
        val env = ChatEnvelope.decode(payload)
        if (env.kind != ChatKind.CHANNEL_TEXT) return null
        env.body[CBORObject.FromObject(1)].GetByteString()
    }.getOrNull()

    /** The channel hash of an inbound CHANNEL_TEXT payload (lookup hint), or null. */
    fun inboundChannelHash(payload: ByteArray): Byte? =
        channelPayload(payload)?.takeIf { it.isNotEmpty() }?.get(0)

    /**
     * Opens a CHANNEL_TEXT payload with [secret] (§7.5). Returns null if the hash
     * mismatches, the MAC fails, or the plaintext is malformed.
     */
    fun open(secret: ByteArray, payload: ByteArray): Decoded? {
        val cp = channelPayload(payload) ?: return null
        return openGrpTxt(secret, cp)
    }

    /** The channel-hash lookup byte (§7.4) of a raw GRP_TXT `channel_payload`, or null. */
    fun payloadChannelHash(channelPayload: ByteArray): Int? =
        channelPayload.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF }

    /** Opens a raw GRP_TXT `channel_payload` (already extracted from the envelope) with [secret]. */
    fun decodePayload(secret: ByteArray, channelPayload: ByteArray): Decoded? =
        openGrpTxt(secret, channelPayload)

    /**
     * Seals arbitrary bytes into a channel-payload envelope (`channel_hash || mac || AES-128-ECB`),
     * the same membership-authenticated crypto as GRP_TXT but with a caller-defined plaintext.
     * Used by native channel extensions (e.g. [ChatChannelReaction]) that are NOT MeshCore
     * GRP_TXT text, so they carry their own framing. [plaintext] is zero-padded to the AES block.
     */
    fun sealRaw(secret: ByteArray, plaintext: ByteArray): ByteArray {
        val padded = zeroPad(plaintext, 16)
        val ct = aesEcb(Cipher.ENCRYPT_MODE, secret.copyOf(SECRET_BYTES), padded)
        val mac = mac2(secret, ct)
        val out = ByteArray(1 + MAC_BYTES + ct.size)
        out[0] = channelHash(secret)
        System.arraycopy(mac, 0, out, 1, MAC_BYTES)
        System.arraycopy(ct, 0, out, 1 + MAC_BYTES, ct.size)
        return out
    }

    /**
     * Verifies + decrypts a [sealRaw] channel-payload with [secret], returning the (still
     * zero-padded) plaintext bytes, or null on hash/MAC/shape failure. Callers strip padding or
     * parse a self-delimiting format (CBOR ignores trailing zero bytes).
     */
    fun openRaw(secret: ByteArray, channelPayload: ByteArray): ByteArray? {
        val cp = channelPayload
        if (cp.size < 1 + MAC_BYTES) return null
        if (cp[0] != channelHash(secret)) return null
        val ct = cp.copyOfRange(1 + MAC_BYTES, cp.size)
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        if (!constantTimeEquals(cp.copyOfRange(1, 1 + MAC_BYTES), mac2(secret, ct))) return null
        return aesEcb(Cipher.DECRYPT_MODE, secret.copyOf(SECRET_BYTES), ct)
    }

    // ---- GRP_TXT crypto -------------------------------------------------------

    private fun sealGrpTxt(secret: ByteArray, sender: String, text: String, timestamp: Long): ByteArray {
        val body = "$sender: $text".toByteArray(Charsets.UTF_8)
        val plain = ByteBuffer.allocate(4 + 1 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        plain.putInt(timestamp.toInt())
        plain.put(0) // flags: text type 0, attempt 0
        plain.put(body)
        val padded = zeroPad(plain.array(), 16)
        val ct = aesEcb(Cipher.ENCRYPT_MODE, secret.copyOf(SECRET_BYTES), padded)
        val mac = mac2(secret, ct)
        val out = ByteArray(1 + MAC_BYTES + ct.size)
        out[0] = channelHash(secret)
        System.arraycopy(mac, 0, out, 1, MAC_BYTES)
        System.arraycopy(ct, 0, out, 1 + MAC_BYTES, ct.size)
        return out
    }

    private fun openGrpTxt(secret: ByteArray, cp: ByteArray): Decoded? {
        if (cp.size < 1 + MAC_BYTES) return null
        if (cp[0] != channelHash(secret)) return null
        val ct = cp.copyOfRange(1 + MAC_BYTES, cp.size)
        if (ct.isEmpty() || ct.size % 16 != 0) return null
        if (!constantTimeEquals(cp.copyOfRange(1, 1 + MAC_BYTES), mac2(secret, ct))) return null
        val pt = aesEcb(Cipher.DECRYPT_MODE, secret.copyOf(SECRET_BYTES), ct)
        if (pt.size < 5) return null
        val ts = ByteBuffer.wrap(pt, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        var end = pt.size
        while (end > 5 && pt[end - 1] == 0.toByte()) end--
        val body = String(pt, 5, end - 5, Charsets.UTF_8)
        val sep = body.indexOf(": ")
        val sender = if (sep >= 0) body.substring(0, sep) else ""
        val text = if (sep >= 0) body.substring(sep + 2) else body
        return Decoded(sender, text, ts)
    }

    private fun aesEcb(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    private fun mac2(secret: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = ByteArray(32)
        System.arraycopy(secret, 0, key, 0, SECRET_BYTES) // secret16 || zero16
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(ciphertext).copyOf(MAC_BYTES)
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun zeroPad(data: ByteArray, block: Int): ByteArray {
        val rem = data.size % block
        return if (rem == 0) data else data.copyOf(data.size + (block - rem))
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
