package com.mantz_it.rfanalyzer.digiext.lora.meshcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Random

/**
 * <h1>RF Analyzer - Ed25519 Tests</h1>
 *
 * Module:      TestEd25519.kt
 * Description: Tests for [Ed25519.verify], the signature check of MeshCore adverts: the RFC 8032
 *              test vectors, agreement with the JDK's own Ed25519 signatures, and rejection of
 *              malformed keys and signatures.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestEd25519 {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** RFC 8032 §7.1, tests 1-3. */
    @Test
    fun rfc8032Vectors() {
        val vectors = listOf(
            Triple("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            Triple("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
            Triple("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
        )
        for ((key, message, signature) in vectors) {
            assertTrue(Ed25519.verify(hex(key), hex(message), hex(signature)))
            assertFalse(Ed25519.verify(hex(key), hex(message) + byteArrayOf(0), hex(signature)))
        }
    }

    /** Signatures made by the JDK's own Ed25519 verify; a changed message or signature does not. */
    @Test
    fun agreesWithTheJdk() {
        val rnd = Random(1)
        val generator = KeyPairGenerator.getInstance("Ed25519")
        repeat(20) { n ->
            val pair = generator.generateKeyPair()
            // the encoded public key is X.509: the raw 32 bytes are its last 32
            val publicKey = pair.public.encoded.takeLast(32).toByteArray()
            val message = ByteArray(rnd.nextInt(200)).also(rnd::nextBytes)
            val signature = Signature.getInstance("Ed25519").run { initSign(pair.private); update(message); sign() }
            assertTrue("pair $n", Ed25519.verify(publicKey, message, signature))
            val bad = signature.copyOf().also { it[rnd.nextInt(64)] = (it[rnd.nextInt(64)].toInt() xor 0x10).toByte() }
            if (!bad.contentEquals(signature)) assertFalse("pair $n, changed signature", Ed25519.verify(publicKey, message, bad))
            if (message.isNotEmpty()) {
                val changed = message.copyOf().also { it[0] = (it[0] + 1).toByte() }
                assertFalse("pair $n, changed message", Ed25519.verify(publicKey, changed, signature))
            }
        }
    }

    @Test
    fun rejectsMalformedInput() {
        assertFalse(Ed25519.verify(ByteArray(31), ByteArray(0), ByteArray(64)))
        assertFalse(Ed25519.verify(ByteArray(32), ByteArray(0), ByteArray(63)))
        // not a point on the curve
        assertFalse(Ed25519.verify(ByteArray(32) { 0xFF.toByte() }, ByteArray(0), ByteArray(64)))
    }
}
