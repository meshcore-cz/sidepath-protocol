package cz.arnal.bleedge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * A GATT frame (docs/PROTOCOL.md §5): one hop-local fragment of a serialized
 * datagram. The header is fixed-width big-endian:
 *
 *   frame_version(1) | transfer_id(16) | fragment_index(1) | fragment_count(1)
 *   | payload_crc32(4) | data(N)
 *
 * [transferId] is hop-local and MUST NOT be confused with the end-to-end
 * datagram id. A relay generates a fresh transfer_id when re-serializing.
 */
data class Frame(
    val frameVersion: Int,
    val transferId: ByteArray, // 16 bytes
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val payloadCrc32: Int,      // CRC-32/IEEE of the complete datagram, stored as raw int bits
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(BLEEdge.FRAME_HEADER_SIZE + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(frameVersion.toByte())
        buf.put(transferId)
        buf.put(fragmentIndex.toByte())
        buf.put(fragmentCount.toByte())
        buf.putInt(payloadCrc32)
        buf.put(data)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return frameVersion == other.frameVersion && transferId.contentEquals(other.transferId) &&
            fragmentIndex == other.fragmentIndex && fragmentCount == other.fragmentCount &&
            payloadCrc32 == other.payloadCrc32 && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = transferId.contentHashCode() * 31 + fragmentIndex

    companion object {
        fun decode(raw: ByteArray): Frame {
            require(raw.size >= BLEEdge.FRAME_HEADER_SIZE) {
                "Frame too short: ${raw.size} bytes (need ${BLEEdge.FRAME_HEADER_SIZE})"
            }
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val version = buf.get().toInt() and 0xFF
            val transferId = ByteArray(BLEEdge.TRANSFER_ID_BYTES).also { buf.get(it) }
            val fragmentIndex = buf.get().toInt() and 0xFF
            val fragmentCount = buf.get().toInt() and 0xFF
            val crc = buf.int
            val data = ByteArray(raw.size - BLEEdge.FRAME_HEADER_SIZE).also { buf.get(it) }
            return Frame(version, transferId, fragmentIndex, fragmentCount, crc, data)
        }

        fun newTransferId(): ByteArray =
            ByteArray(BLEEdge.TRANSFER_ID_BYTES).also { SecureRandom().nextBytes(it) }

        /**
         * Splits a serialized [datagramBytes] into frames of at most [maxFrameSize]
         * total bytes, all sharing [transferId], fragment_count and the datagram CRC.
         */
        fun fragment(
            datagramBytes: ByteArray,
            maxFrameSize: Int = BLEEdge.MAX_FRAME_SIZE,
            transferId: ByteArray = newTransferId(),
        ): List<Frame> {
            require(transferId.size == BLEEdge.TRANSFER_ID_BYTES) { "transfer id must be ${BLEEdge.TRANSFER_ID_BYTES} bytes" }
            val maxData = (maxFrameSize - BLEEdge.FRAME_HEADER_SIZE).coerceAtLeast(1)
            val crc = CRC32().also { it.update(datagramBytes) }.value.toInt()
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            // A zero-length datagram still produces one (empty) fragment.
            do {
                val end = (offset + maxData).coerceAtMost(datagramBytes.size)
                chunks += datagramBytes.copyOfRange(offset, end)
                offset = end
            } while (offset < datagramBytes.size)
            val count = chunks.size
            return chunks.mapIndexed { i, c ->
                Frame(BLEEdge.FRAME_VERSION, transferId, i, count, crc, c)
            }
        }
    }
}

/**
 * Reassembles frames into complete datagram bytes. Buffers are keyed by
 * `(peerLinkId, transfer_id)` (§5.3) so fragments from distinct links or relay
 * copies never collide. Stale buffers are pruned lazily on each [addFrame] call
 * and via [reap]; the library stays coroutine-free.
 */
class Reassembler(private val timeoutMs: Long = BLEEdge.REASSEMBLY_TIMEOUT_MS) {
    private class Assembly(val count: Int, val crc: Int) {
        val frags = HashMap<Int, ByteArray>()
        var lastSeen = System.currentTimeMillis()
    }

    private val pending = ConcurrentHashMap<String, Assembly>()

    private fun key(peerLinkId: String, transferId: ByteArray): String =
        peerLinkId + ":" + transferId.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Adds [frame] received on link [peerLinkId]. Returns the complete datagram
     * bytes once all fragments are present and the CRC verifies, or null while
     * more fragments are awaited. Returns null (dropping the buffer) on CRC
     * mismatch rather than throwing.
     */
    fun addFrame(peerLinkId: String, frame: Frame): ByteArray? {
        reapIfDue()
        val k = key(peerLinkId, frame.transferId)
        val assembly = pending.getOrPut(k) { Assembly(frame.fragmentCount, frame.payloadCrc32) }
        synchronized(assembly) {
            assembly.lastSeen = System.currentTimeMillis()
            if (assembly.frags.containsKey(frame.fragmentIndex)) return null // duplicate index
            assembly.frags[frame.fragmentIndex] = frame.data
            if (assembly.frags.size < assembly.count) return null

            val total = (0 until assembly.count).sumOf { assembly.frags[it]?.size ?: return null }
            val out = ByteArray(total)
            var off = 0
            for (i in 0 until assembly.count) {
                val chunk = assembly.frags[i] ?: return null
                chunk.copyInto(out, off); off += chunk.size
            }
            pending.remove(k)
            val actual = CRC32().also { it.update(out) }.value.toInt()
            return if (actual == assembly.crc) out else null
        }
    }

    private var lastReap = 0L
    private fun reapIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastReap < 5_000) return
        lastReap = now
        reap()
    }

    fun reap() {
        val now = System.currentTimeMillis()
        pending.entries.filter { now - it.value.lastSeen > timeoutMs }.forEach { pending.remove(it.key) }
    }
}
