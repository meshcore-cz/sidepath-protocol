package cz.arnal.bleedge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Frame wire encoding: version(1) + packet_id(16) + frag_idx(1) + frag_count(1) + crc32(4) + data
private const val FRAME_HEADER_SIZE = 23

// MAX_FRAME_SIZE is the maximum GATT frame (single ATT write) size used for
// fragmentation. It must stay <= the smallest peer's negotiated (ATT_MTU - 3);
// the ESP32 relay negotiates an ATT MTU of 247, so 200 is safe for every peer.
// Mirrors core.MaxFrameSize (Go) and FRAGMENT_MTU (firmware). See PROTOCOL.md.
const val MAX_FRAME_SIZE = 200

/**
 * Frame is the GATT transport unit. One or more frames reassemble into a Packet's encoded bytes.
 */
data class Frame(
    val version: Byte,
    val packetId: ByteArray,    // 16 bytes
    val fragmentIndex: Byte,
    val fragmentCount: Byte,
    val payloadCrc32: Int,      // unsigned CRC32 stored as Int
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(FRAME_HEADER_SIZE + data.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(version)
            put(packetId)
            put(fragmentIndex)
            put(fragmentCount)
            putInt(payloadCrc32)
            put(data)
        }
        return buf.array()
    }

    companion object {
        fun decode(raw: ByteArray): Frame {
            require(raw.size >= FRAME_HEADER_SIZE) {
                "Frame too short: ${raw.size} bytes (need $FRAME_HEADER_SIZE)"
            }
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val version = buf.get()
            val packetId = ByteArray(16).also { buf.get(it) }
            val fragmentIndex = buf.get()
            val fragmentCount = buf.get()
            val crc32 = buf.int
            val data = ByteArray(raw.size - FRAME_HEADER_SIZE).also { buf.get(it) }
            return Frame(version, packetId, fragmentIndex, fragmentCount, crc32, data)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return version == other.version &&
            packetId.contentEquals(other.packetId) &&
            fragmentIndex == other.fragmentIndex &&
            fragmentCount == other.fragmentCount &&
            payloadCrc32 == other.payloadCrc32 &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int = packetId.contentHashCode() * 31 + fragmentIndex
}

/**
 * Splits [packetData] into GATT frames of at most [mtu] bytes total.
 */
fun fragmentPacket(packetData: ByteArray, mtu: Int, packetId: ByteArray): List<Frame> {
    val maxData = (mtu - FRAME_HEADER_SIZE).coerceAtLeast(1)
    val crc = CRC32().also { it.update(packetData) }.value.toInt()

    val frames = mutableListOf<Frame>()
    var offset = 0
    while (offset < packetData.size) {
        val end = (offset + maxData).coerceAtMost(packetData.size)
        frames += Frame(
            version = 1,
            packetId = packetId,
            fragmentIndex = frames.size.toByte(),
            fragmentCount = 0, // filled below
            payloadCrc32 = crc,
            data = packetData.copyOfRange(offset, end),
        )
        offset = end
    }
    val count = frames.size.toByte()
    return frames.map { it.copy(fragmentCount = count) }
}

private data class Assembly(
    val frags: MutableMap<Int, ByteArray> = mutableMapOf(),
    val count: Int,
    val crc: Int,
    var lastSeen: Long = System.currentTimeMillis(),
)

/**
 * Reassembler collects frames and reassembles complete packets.
 * Thread-safe. Background coroutine reaps stale assemblies.
 */
class Reassembler(
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val timeoutMs: Long = 10_000L,
) {
    private val pending = ConcurrentHashMap<String, Assembly>()

    init {
        scope.launch {
            while (isActive) {
                delay(5_000)
                reap()
            }
        }
    }

    /**
     * Adds a frame. Returns the reassembled data if all fragments are received and CRC is valid,
     * or null if more fragments are needed.
     * Throws [IllegalStateException] on CRC mismatch.
     */
    fun addFrame(frame: Frame): ByteArray? {
        val key = frame.packetId.joinToString("") { "%02x".format(it) }
        val count = frame.fragmentCount.toInt() and 0xFF
        val idx = frame.fragmentIndex.toInt() and 0xFF

        val assembly = pending.getOrPut(key) {
            Assembly(count = count, crc = frame.payloadCrc32)
        }

        synchronized(assembly) {
            assembly.lastSeen = System.currentTimeMillis()
            if (assembly.frags.containsKey(idx)) {
                return null // duplicate fragment
            }
            assembly.frags[idx] = frame.data

            if (assembly.frags.size < assembly.count) {
                return null // still waiting
            }

            // Reassemble
            val totalSize = (0 until assembly.count).sumOf { assembly.frags[it]!!.size }
            val buf = ByteArray(totalSize)
            var offset = 0
            for (i in 0 until assembly.count) {
                val chunk = assembly.frags[i] ?: return null
                chunk.copyInto(buf, offset)
                offset += chunk.size
            }

            pending.remove(key)

            val actualCrc = CRC32().also { it.update(buf) }.value.toInt()
            check(actualCrc == assembly.crc) {
                "CRC mismatch on packet $key: expected 0x${assembly.crc.toString(16)} got 0x${actualCrc.toString(16)}"
            }
            return buf
        }
    }

    private fun reap() {
        val now = System.currentTimeMillis()
        val stale = pending.entries.filter { now - it.value.lastSeen > timeoutMs }
        stale.forEach { pending.remove(it.key) }
    }
}
