package cz.arnal.bleedge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeNameTest {
    // Shared cross-platform vector: seed 00..1f -> this Ed25519 public key. The deterministic
    // bootstrap name MUST match the Go (core/nodename.go) and C++ ports for the same key, so a
    // peer's computed fallback equals the default the owner actually uses. Go prints the same.
    private val sharedPubHex = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"

    @Test
    fun matchesGoForSharedVector() {
        assertEquals("marble-seven-kelp", defaultNodeNameFromHex(sharedPubHex))
        val pub = ByteArray(32) { i -> sharedPubHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        assertEquals("marble-seven-kelp", defaultNodeName(pub))
    }

    @Test
    fun blankForPartialKey() {
        assertEquals("", defaultNodeNameFromHex("abcd"))
        assertEquals("", defaultNodeName(ByteArray(8)))
    }
}
