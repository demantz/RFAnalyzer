package com.mantz_it.rfanalyzer.digiext.lora.meshcore

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * <h1>RF Analyzer - MeshCore Channel Registry</h1>
 *
 * Module:      MeshCoreChannelRegistry.kt
 * Description: The MeshCore group channels this app holds a key for: [MeshCoreChannel], one
 *              channel's key, `channel_hash` byte and HMAC secret, and [MeshCoreChannelRegistry],
 *              which holds the Public channel, the built-in and user-added hashtag channels and an
 *              optional bundled candidate dictionary, and decrypts a group envelope whose MAC
 *              matches one of them.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * One MeshCore group channel we hold a key for.
 *
 * MeshCore group channels are symmetric: anyone who knows the channel name (or raw PSK) can
 * read every message, by design — they are the protocol's *public* broadcast channels, not
 * confidential ones (see docs.meshcore.io; the EastMesh wiki spells out explicitly that
 * hashtag channels are unsuitable where confidentiality matters). So "decrypting" one here is
 * just labeling received public broadcast traffic, the same thing the official MeshCore app
 * does when you join `#test`.
 *
 *  - [key16]  = the 16-byte channel secret. For a hashtag channel this is `SHA256("#"+name)[:16]`;
 *               for the Public channel it is the firmware's hardcoded PSK.
 *  - [hashByte]= `SHA256(key16)[0]` — the 1-byte `channel_hash` on the wire (a fast pre-filter
 *               only; collisions are expected, hence the per-candidate MAC check in [tryDecrypt]).
 *  - [secret32]= [key16] zero-padded to 32 bytes — the HMAC key (firmware zero-fills its
 *               PUB_KEY_SIZE=32 secret buffer and copies in only the 16 key bytes).
 */
class MeshCoreChannel(val name: String, val key16: ByteArray, val hashByte: Byte) {
    val secret32: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) { key16.copyOf(32) }

    companion object {
        /** Hashtag channel: `secret = SHA256("#"+name)[:16]`. The leading '#' is part of the
         *  hashed string; [name] is normalized to carry it so display and hashing agree. */
        fun fromHashtag(rawName: String): MeshCoreChannel {
            val name = if (rawName.startsWith("#")) rawName else "#$rawName"
            val key16 = sha256(name.toByteArray(Charsets.UTF_8)).copyOf(16)
            return MeshCoreChannel(name, key16, sha256(key16)[0])
        }

        /** Channel from a raw 16-byte PSK (the Public channel). [displayName] is cosmetic. */
        fun fromPsk(displayName: String, psk16: ByteArray): MeshCoreChannel {
            val key16 = psk16.copyOf(16)
            return MeshCoreChannel(displayName, key16, sha256(key16)[0])
        }

        private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    }
}

/** Result of a successful channel match: which channel, the recovered plaintext, and whether the
 *  match came from the speculative [MeshCoreChannelRegistry] dictionary (which needs a stricter
 *  plaintext sanity check — see [MeshCoreParser]) rather than the trusted default/user table. */
class MeshCoreDecryptMatch(val channel: MeshCoreChannel, val plaintext: ByteArray, val fromDictionary: Boolean)

/**
 * Holds every MeshCore group channel this app can decode and matches an encrypted envelope
 * against them.
 *
 * Three tiers, all bucketed by [MeshCoreChannel.hashByte] (256 buckets) so a message only ever
 * compares against the handful of channels sharing its `channel_hash`:
 *   1. **Public** — firmware's well-known hardcoded PSK.
 *   2. **Built-in + user** hashtags — [DEFAULT_HASHTAGS] plus whatever the user added, rebuilt by
 *      [updateCustomChannels]. Together the "user table"; a MAC hit here is trusted.
 *   3. **Dictionary** — an optional large bundled candidate list ([init] loads it off-thread).
 *      A MAC hit here is *speculative* (with ~N/256 candidates per bucket a 2-byte MAC collides
 *      roughly once every few hundred undecrypted messages), so callers additionally sanity-check
 *      the recovered plaintext before trusting it.
 *
 * Crypto (as in github.com/meshcore-dev/MeshCore, Utils::encryptThenMAC / MACThenDecrypt):
 * MAC = truncated `HMAC-SHA256(secret32, ciphertext)[:2]`, ciphertext is AES-128-ECB
 * (key = [MeshCoreChannel.key16], plaintext zero-padded to a 16-byte block), both from the JCE.
 *
 * Thread-safety: the two bucket maps are immutable snapshots swapped behind `@Volatile`, so the
 * decode thread always sees a consistent table while the UI thread updates custom channels.
 */
object MeshCoreChannelRegistry {
    private const val TAG = "MeshCoreChannelRegistry"
    private const val PUBLIC_CHANNEL_NAME = "Public"
    private const val PUBLIC_GROUP_PSK_BASE64 = "izOH6cXN6mrJ5e26oRXNcg=="
    private const val DICTIONARY_ASSET = "meshcore/channel_dictionary.txt"

    /** MeshCore MAX_CHANNEL_NAME is 32 bytes; names are hashed as UTF-8. */
    const val MAX_CHANNEL_NAME_BYTES = 32

    /** Preloaded hashtag channels: firmware/community defaults plus a few well-documented ones.
     *  Stored without the leading '#' (added by [MeshCoreChannel.fromHashtag]). */
    private val DEFAULT_HASHTAGS = listOf(
        "queencitycon", "okimesh", "test", "echo", "offband",
        "ping", "hamradio", "weather", "emergency", "emcomm",
        "mesh", "meshcore", "news", "testing", "rhein-neckar",
    )

    private val publicChannel: MeshCoreChannel =
        MeshCoreChannel.fromPsk(PUBLIC_CHANNEL_NAME, Base64.getDecoder().decode(PUBLIC_GROUP_PSK_BASE64))

    @Volatile private var userBuckets: Map<Int, List<MeshCoreChannel>> = bucket(defaultChannels())
    @Volatile private var dictionaryBuckets: Map<Int, List<MeshCoreChannel>> = emptyMap()

    private val dictionaryLoaded = CompletableDeferred<Unit>()
    private val customChannelsApplied = CompletableDeferred<Unit>()

    /**
     * Suspends until every key this app holds is in place: the dictionary loaded (or failed to) and
     * the user's channels applied once. Stored packets are re-parsed only after that - a pass
     * without the keys would mark its packets as parsed by the current parser without having
     * decrypted them, and nothing would ever re-parse them again.
     */
    suspend fun awaitKeys() {
        dictionaryLoaded.await()
        customChannelsApplied.await()
    }

    /** The Public channel's `channel_hash` byte (for tests). */
    val publicChannelHash: Byte get() = publicChannel.hashByte

    private fun defaultChannels(): List<MeshCoreChannel> =
        listOf(publicChannel) + DEFAULT_HASHTAGS.map { MeshCoreChannel.fromHashtag(it) }

    private fun bucket(channels: List<MeshCoreChannel>): Map<Int, List<MeshCoreChannel>> =
        channels.groupBy { it.hashByte.toInt() and 0xFF }

    /** Rebuilds the user table from [customNames] (bare or '#'-prefixed; blanks/oversized dropped).
     *  Default channels are always included and need no user entry. */
    fun updateCustomChannels(customNames: List<String>) {
        val customs = customNames
            .map { it.trim() }
            .filter { it.isNotEmpty() && normalizedHashtag(it).toByteArray(Charsets.UTF_8).size <= MAX_CHANNEL_NAME_BYTES }
            .map { MeshCoreChannel.fromHashtag(it) }
        userBuckets = bucket(defaultChannels() + customs)
        customChannelsApplied.complete(Unit)
    }

    /** Normalizes a user-entered channel name to its on-air '#'-prefixed, lowercased form. */
    fun normalizedHashtag(rawName: String): String {
        val n = rawName.trim().lowercase()
        return if (n.startsWith("#")) n else "#$n"
    }

    /** Loads the bundled candidate dictionary off the main thread. Safe to call more than once
     *  (later calls just reload). Missing/corrupt asset → dictionary stays empty, logged. */
    fun init(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channels = ArrayList<MeshCoreChannel>()
                appContext.assets.open(DICTIONARY_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val name = line.trim()
                        if (name.isNotEmpty() && !name.startsWith(";")) channels.add(MeshCoreChannel.fromHashtag(name))
                    }
                }
                dictionaryBuckets = bucket(channels)
                Log.i(TAG, "Loaded ${channels.size} MeshCore dictionary channels into ${dictionaryBuckets.size} buckets")
            } catch (e: Exception) {
                Log.w(TAG, "MeshCore channel dictionary not loaded ($DICTIONARY_ASSET): ${e.message}")
            } finally {
                dictionaryLoaded.complete(Unit)
            }
        }
    }

    /**
     * Tries to decrypt a group envelope. Returns the first channel whose truncated HMAC matches
     * [mac] (user table first, then dictionary), with the AES-decrypted plaintext — or null if no
     * held channel matches (unknown/private channel → caller keeps the opaque envelope).
     *
     * A MAC match is cryptographic corroboration independent of the LoRa PHY CRC, so a user-table
     * hit is trusted outright. A dictionary hit is flagged [MeshCoreDecryptMatch.fromDictionary]
     * so the caller can additionally sanity-check the plaintext against false positives.
     */
    fun tryDecrypt(channelHashByte: Byte, mac: ByteArray, ciphertext: ByteArray): MeshCoreDecryptMatch? {
        if (mac.size < 2 || ciphertext.isEmpty() || ciphertext.size % 16 != 0) return null
        val bucketKey = channelHashByte.toInt() and 0xFF

        userBuckets[bucketKey]?.let { candidates ->
            matchAndDecrypt(candidates, mac, ciphertext)?.let { return MeshCoreDecryptMatch(it.first, it.second, fromDictionary = false) }
        }
        dictionaryBuckets[bucketKey]?.let { candidates ->
            matchAndDecrypt(candidates, mac, ciphertext)?.let { return MeshCoreDecryptMatch(it.first, it.second, fromDictionary = true) }
        }
        return null
    }

    /** Test-only: populate the dictionary tier directly (JVM unit tests can't load the asset,
     *  which needs an Android [Context]). Pass an empty list to clear it. */
    internal fun setDictionaryForTest(names: List<String>) {
        dictionaryBuckets = bucket(names.map { MeshCoreChannel.fromHashtag(it) })
    }

    /** Test-only: reset the user table back to just the built-in defaults. */
    internal fun resetCustomChannelsForTest() {
        userBuckets = bucket(defaultChannels())
    }

    private fun matchAndDecrypt(
        candidates: List<MeshCoreChannel>,
        mac: ByteArray,
        ciphertext: ByteArray,
    ): Pair<MeshCoreChannel, ByteArray>? {
        for (channel in candidates) {
            val computed = Mac.getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(channel.secret32, "HmacSHA256")) }
                .doFinal(ciphertext)
            if (computed[0] != mac[0] || computed[1] != mac[1]) continue
            val plain = try {
                Cipher.getInstance("AES/ECB/NoPadding")
                    .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(channel.key16, "AES")) }
                    .doFinal(ciphertext)
            } catch (e: Exception) {
                continue
            }
            return channel to plain
        }
        return null
    }
}
