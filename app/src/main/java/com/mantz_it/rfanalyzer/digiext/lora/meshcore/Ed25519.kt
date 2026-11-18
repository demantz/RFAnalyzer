package com.mantz_it.rfanalyzer.digiext.lora.meshcore

import java.security.MessageDigest

/**
 * <h1>RF Analyzer - Ed25519 Signatures</h1>
 *
 * Module:      Ed25519.kt
 * Description: Ed25519 signature verification (RFC 8032), for MeshCore adverts - Android has no
 *              Ed25519 before API 33 and the app no crypto library.
 *
 * Provenance: a Kotlin port of the verification path of TweetNaCl (Bernstein, van Gastel, Janssen,
 * Lange, Schwabe, Smetsers; public domain): field elements as 16 limbs of 16 bits in Longs, the
 * curve constants, point addition in extended coordinates, the scalar ladder and the reduction
 * modulo the group order. Not constant time, which verification of public data doesn't need. A
 * verification takes a few milliseconds.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
internal object Ed25519 {

    /** True if [signature] (64 bytes) is [publicKey]'s (32 bytes) signature of [message]. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val q = Array(4) { LongArray(16) }
        if (!unpackNegated(q, publicKey)) return false

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(signature, 0, 32)
        digest.update(publicKey)
        digest.update(message)
        val h = digest.digest()
        reduce(h)

        val p = Array(4) { LongArray(16) }
        scalarMult(p, q, h) // h·(-A)
        val sb = Array(4) { LongArray(16) }
        scalarBase(sb, signature.copyOfRange(32, 64)) // S·B
        add(p, sb)
        val r = ByteArray(32)
        pack(r, p)
        for (i in 0 until 32) if (r[i] != signature[i]) return false
        return true
    }

    // ── field arithmetic mod 2^255 - 19 ────────────────────────────────────

    private fun gf(vararg limbs: Long) = LongArray(16).also { limbs.copyInto(it) }

    private val GF0 = LongArray(16)
    private val GF1 = gf(1)
    private val D = gf(0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203)
    private val D2 = gf(0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406)
    private val X = gf(0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169)
    private val Y = gf(0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666)
    private val I = gf(0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83)

    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    /** Swaps [p] and [q] if [b] is 1. */
    private fun select(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack25519(o: ByteArray, n: LongArray) {
        val t = n.copyOf()
        val m = LongArray(16)
        carry(t); carry(t); carry(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            select(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun notEqual(a: LongArray, b: LongArray): Boolean {
        val c = ByteArray(32)
        val d = ByteArray(32)
        pack25519(c, a)
        pack25519(d, b)
        return !c.contentEquals(d)
    }

    private fun parity(a: LongArray): Int {
        val d = ByteArray(32)
        pack25519(d, a)
        return d[0].toInt() and 1
    }

    private fun unpack25519(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }
    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        carry(o)
        carry(o)
    }

    private fun square(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun invert(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            square(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }

    /** i^((p-5)/8), for the square root. */
    private fun pow2523(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 250 downTo 0) {
            square(c, c)
            if (a != 1) mul(c, c, i)
        }
        c.copyInto(o)
    }

    // ── points (extended coordinates X, Y, Z, T) ───────────────────────────

    private fun add(p: Array<LongArray>, q: Array<LongArray>) {
        val a = LongArray(16); val b = LongArray(16); val c = LongArray(16); val d = LongArray(16)
        val t = LongArray(16); val e = LongArray(16); val f = LongArray(16); val g = LongArray(16); val h = LongArray(16)
        sub(a, p[1], p[0]); sub(t, q[1], q[0]); mul(a, a, t)
        add(b, p[0], p[1]); add(t, q[0], q[1]); mul(b, b, t)
        mul(c, p[3], q[3]); mul(c, c, D2)
        mul(d, p[2], q[2]); add(d, d, d)
        sub(e, b, a); sub(f, d, c); add(g, d, c); add(h, b, a)
        mul(p[0], e, f); mul(p[1], h, g); mul(p[2], g, f); mul(p[3], e, h)
    }

    private fun swap(p: Array<LongArray>, q: Array<LongArray>, b: Int) {
        for (i in 0 until 4) select(p[i], q[i], b)
    }

    private fun pack(r: ByteArray, p: Array<LongArray>) {
        val tx = LongArray(16); val ty = LongArray(16); val zi = LongArray(16)
        invert(zi, p[2])
        mul(tx, p[0], zi)
        mul(ty, p[1], zi)
        pack25519(r, ty)
        r[31] = (r[31].toInt() xor (parity(tx) shl 7)).toByte()
    }

    /** p = s·q (s: 32 bytes little endian); q is clobbered. */
    private fun scalarMult(p: Array<LongArray>, q: Array<LongArray>, s: ByteArray) {
        GF0.copyInto(p[0]); GF1.copyInto(p[1]); GF1.copyInto(p[2]); GF0.copyInto(p[3])
        for (i in 255 downTo 0) {
            val b = (s[i / 8].toInt() shr (i and 7)) and 1
            swap(p, q, b)
            add(q, p)
            add(p, p)
            swap(p, q, b)
        }
    }

    private fun scalarBase(p: Array<LongArray>, s: ByteArray) {
        val q = Array(4) { LongArray(16) }
        X.copyInto(q[0]); Y.copyInto(q[1]); GF1.copyInto(q[2]); mul(q[3], X, Y)
        scalarMult(p, q, s)
    }

    /** The negated point of an encoded public key; false if it isn't on the curve. */
    private fun unpackNegated(r: Array<LongArray>, p: ByteArray): Boolean {
        val t = LongArray(16); val chk = LongArray(16); val num = LongArray(16); val den = LongArray(16)
        val den2 = LongArray(16); val den4 = LongArray(16); val den6 = LongArray(16)
        GF1.copyInto(r[2])
        unpack25519(r[1], p)
        square(num, r[1])
        mul(den, num, D)
        sub(num, num, r[2])
        add(den, r[2], den)

        square(den2, den)
        square(den4, den2)
        mul(den6, den4, den2)
        mul(t, den6, num)
        mul(t, t, den)

        pow2523(t, t)
        mul(t, t, num)
        mul(t, t, den)
        mul(t, t, den)
        mul(r[0], t, den)

        square(chk, r[0])
        mul(chk, chk, den)
        if (notEqual(chk, num)) mul(r[0], r[0], I)

        square(chk, r[0])
        mul(chk, chk, den)
        if (notEqual(chk, num)) return false

        if (parity(r[0]) == (p[31].toInt() and 0xff) shr 7) sub(r[0], GF0, r[0])
        mul(r[3], r[0], r[1])
        return true
    }

    // ── scalars mod the group order L ──────────────────────────────────────

    private val L = longArrayOf(
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
    )

    private fun modL(r: ByteArray, x: LongArray) {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[i] = (x[i] and 255).toByte()
        }
    }

    /** Reduces the 64-byte [r] modulo L into its first 32 bytes. */
    private fun reduce(r: ByteArray) {
        val x = LongArray(64) { r[it].toLong() and 0xff }
        r.fill(0)
        modL(r, x)
    }
}
