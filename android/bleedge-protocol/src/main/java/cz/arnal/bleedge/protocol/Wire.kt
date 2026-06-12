package cz.arnal.bleedge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level wire helpers shared by signed/AAD byte-layout construction across
 * BLEEdge payload protocols. All multi-byte integers in BLEEdge signed layouts
 * are little-endian (docs/PROTOCOL.md §8.3, CHAT_PROTOCOL.md §4.3/§5.7/§6.3).
 */
object Wire {
    /**
     * Domain-separation prefix: the ASCII bytes of [label] followed by a single
     * NUL terminator. The specs write this as `ascii("...\0")`.
     */
    fun asciiNul(label: String): ByteArray {
        val a = label.toByteArray(Charsets.US_ASCII)
        return a.copyOf(a.size + 1) // trailing 0x00
    }

    fun le16(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((v and 0xFFFF).toShort()).array()

    fun le32(v: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((v and 0xFFFFFFFFL).toInt()).array()

    fun le64(v: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
}
