package com.mantz_it.rfanalyzer.digiext.k2k

import com.mantz_it.rfanalyzer.digiext.callsign.parseK2kCallsigns
import com.mantz_it.rfanalyzer.digiext.db.PacketSpec
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.K2kSymbol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - K2K Data</h1>
 *
 * Module:      K2kData.kt
 * Description: The data layer of the keyboard-to-keyboard modes RTTY and PSK31: the stored
 *              transmission ([K2kTransmission] with its [K2kPhy] and [K2kText]), the two modes'
 *              packet specs, and [K2kAggregator], which assembles a mode's symbol stream into
 *              transmissions - finalized after a silence timeout, stored in parts when one does not
 *              end.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The RTTY signal's parameters (detected ones in auto mode; [reversed] = mark is the lower tone);
 *  all null for PSK31, which has none. */
@Serializable
data class K2kPhy(val baudRate: Float? = null, val frequencyShift: Float? = null, val reversed: Boolean? = null)

/**
 * The text of one keyboard-to-keyboard transmission, with the callsigns guessed from it once it has
 * ended ([parseK2kCallsigns]) - null while it is still being received.
 */
@Serializable
data class K2kText(
    val text: String,
    val fromCallsign: String? = null,
    val toCallsign: String? = null,
    /** 0 for a transmission stored whole; 1, 2, 3, ... for the consecutive parts of one too long to
     *  be stored whole (a weather broadcast that sends for hours), each part stored like a
     *  transmission of its own - see [K2kAggregator]. The live transmission carries the number of
     *  the part being received. */
    val part: Int = 0,
)

/**
 * One RTTY or PSK31 transmission: [StoredPacket.timestampMs] is its first character,
 * [StoredPacket.endTimestampMs] its last - null while it is still being received.
 */
typealias K2kTransmission = StoredPacket<K2kPhy, K2kText>

val K2kTransmission.ongoing: Boolean get() = endTimestampMs == null

/** Keyboard-to-keyboard modes deliver characters, not packets: no raw bits, no tracked objects. */
internal class K2kSpec(mode: DigitalMode) : PacketSpec<K2kPhy, K2kText>(mode, K2kPhy.serializer(), K2kText.serializer())

internal val RttySpec = K2kSpec(DigitalMode.RTTY)
internal val Psk31Spec = K2kSpec(DigitalMode.PSK31)

// Keyboard-to-keyboard finalization timeouts - see K2kAggregator.
internal const val K2K_SWEEP_INTERVAL_MS = 1000L
private const val K2K_SILENCE_TIMEOUT_MS = 8000L
private const val K2K_UNLOCK_GRACE_MS = 1500L

/** A finished transmission shorter than this is almost certainly noise (a false squelch lock, a
 *  partial callsign fragment), not a real QSO, and is not stored. */
private const val K2K_MIN_PERSISTED_LENGTH = 5

// When a transmission is cut into parts - see K2kAggregator.
private const val K2K_PART_MS = 60_000L
private const val K2K_PART_CHARS = 1500
private const val K2K_PART_HARD_MS = 180_000L
private const val K2K_PART_HARD_CHARS = 4000
/** How far back a hard cut looks for a space to cut after. */
private const val K2K_PART_HARD_SPACE_WINDOW = 200

private fun Char.isLineBreak() = this == '\r' || this == '\n'

/** How much of a transmission's already stored parts the ticker keeps showing above the part being
 *  received - as much as the open ticker lays out. */
private const val K2K_EARLIER_TAIL_CHARS = 3000

private class OngoingTransmission(val frequencyHz: Long, val phy: K2kPhy) {
    val text = StringBuilder()

    /** The time of each character of [text]. */
    private var charTimesMs = LongArray(256)

    /** The time of the first character of the part being received. */
    val startMs: Long get() = charTimesMs[0]
    var lastUpdateMs = 0L
        private set

    /** 0 while the transmission is in one piece, else the number of the part being received. */
    var part = 0

    // Set once DemodStatus reports the lock lost while this is the most recently updated
    // transmission; cleared by the next symbol (which only arrives while the demodulator considers
    // itself locked again) - see K2K_UNLOCK_GRACE_MS above.
    var lockLost = false

    /** The part is due to be cut and is inside a line break: it ends where the line break does. */
    var cutAfterLineBreak = false

    /** The end of the parts already stored, so a cut doesn't empty the ticker. */
    var earlierTail = ""

    fun append(char: Char, timeMs: Long) {
        if (text.length == charTimesMs.size) charTimesMs = charTimesMs.copyOf(2 * charTimesMs.size)
        charTimesMs[text.length] = timeMs
        text.append(char)
        lastUpdateMs = timeMs
    }

    fun timeOfMs(index: Int) = charTimesMs[index]

    /** How long the part being received has run when a character arrives at [timeMs]. */
    fun partDurationMs(timeMs: Long) = if (text.isEmpty()) 0L else timeMs - startMs

    /** Removes the first [count] characters - a part that has just been finalized. */
    fun dropFirst(count: Int) {
        charTimesMs.copyInto(charTimesMs, destinationOffset = 0, startIndex = count, endIndex = text.length)
        text.delete(0, count)
    }
}

/**
 * Assembles the symbol stream of one keyboard-to-keyboard mode (RTTY or PSK31) into transmissions.
 * A transmission in progress exists only here, as [current]; it is handed to [persist] once, when it
 * is finalized. One still in progress when the app dies is lost.
 *
 * A transmission is finalized once its silence timeout expires - normally [K2K_SILENCE_TIMEOUT_MS]
 * of no new symbols, since "over"s vary in length and a brief gap (word gap, momentary fade) must
 * not cut a message short. Once the demodulator reports its lock lost ([onLockLost]), that is a much
 * stronger "this transmission just ended" signal, so it only needs the shorter
 * [K2K_UNLOCK_GRACE_MS] of continued silence - still timeout-based (a single lock reading is never
 * trusted outright), just faster.
 *
 * A transmission that does not end - a broadcast sends for hours - is stored in parts
 * ([K2kText.part]), so that it gets stored at all and the text held here stays bounded. Once a part
 * has run [K2K_PART_MS] or holds [K2K_PART_CHARS] characters, it ends after the next line break
 * (the whole run of CR/LF characters forming it), so that a list of the parts reads like the text.
 * A text without line breaks is cut at [K2K_PART_HARD_MS] or [K2K_PART_HARD_CHARS] characters,
 * after the last space within the last [K2K_PART_HARD_SPACE_WINDOW] characters, else where it is.
 * The next part starts with the next character, at that character's time.
 */
internal class K2kAggregator(private val spec: K2kSpec, private val persist: (K2kTransmission) -> Unit) {
    // Keyed by frequency. Guarded by its own monitor: symbols arrive on the Binder callback thread,
    // sweep() runs on the repository's timer.
    private val ongoing = mutableMapOf<Long, OngoingTransmission>()
    private val _current = MutableStateFlow<K2kTransmission?>(null)

    /** The transmission being received right now - only one frequency demodulates at a time, so
     *  that is the most recently updated ongoing one. Null once it is finalized. */
    val current: StateFlow<K2kTransmission?> = _current

    private val _currentEarlierText = MutableStateFlow("")

    /** The end of [current]'s parts that are already stored ("" while it is in one piece) - the
     *  ticker shows it above the part being received. */
    val currentEarlierText: StateFlow<String> = _currentEarlierText

    /** [frequencyHz] is the symbol's frequency, or the tuned one when it carries none. The signal
     *  parameters changing mid-stream (RTTY's rate/shift/polarity, PSK's rate - settings, or what
     *  auto mode detects) end the current transmission like a silence timeout would, so two
     *  parameter regimes are never spliced together. */
    fun onSymbol(symbol: K2kSymbol, frequencyHz: Long) {
        val phy = K2kPhy(symbol.baudRate, symbol.frequencyShift, symbol.reversed)
        _current.value = synchronized(ongoing) {
            // Also catches this very frequency's own entry being the stale one (the channel reused
            // long after its previous transmission), so the lookup below starts a fresh one.
            finalizeStale(symbol.timestampMs)
            val existing = ongoing[frequencyHz]
            if (existing != null && existing.phy != phy) {
                ongoing.remove(frequencyHz)
                finalize(existing)
            }
            val transmission = ongoing.getOrPut(frequencyHz) { OngoingTransmission(frequencyHz, phy) }
            cutIfDue(transmission, symbol.char, symbol.timestampMs)
            transmission.append(symbol.char, symbol.timestampMs)
            transmission.lockLost = false
            latest()
        }
        publishEarlierText()
    }

    private fun publishEarlierText() {
        _currentEarlierText.value = synchronized(ongoing) { ongoing.values.maxByOrNull { it.lastUpdateMs }?.earlierTail.orEmpty() }
    }

    /** Only ever shortens the timeout, never finalizes outright, so it stays safe even if a mode's
     *  lock signal turns out to be unreliable. */
    fun onLockLost() {
        synchronized(ongoing) { ongoing.values.maxByOrNull { it.lastUpdateMs }?.lockLost = true }
    }

    /** Finalizes every transmission that has gone silent past its timeout. */
    fun sweep(now: Long) {
        _current.value = synchronized(ongoing) {
            if (!finalizeStale(now)) return
            latest()
        }
        publishEarlierText()
    }

    /** Caller must hold the lock. Returns whether anything was finalized. */
    private fun finalizeStale(now: Long): Boolean {
        val stale = ongoing.values.filter { now - it.lastUpdateMs > (if (it.lockLost) K2K_UNLOCK_GRACE_MS else K2K_SILENCE_TIMEOUT_MS) }
        stale.forEach {
            ongoing.remove(it.frequencyHz)
            finalize(it)
        }
        return stale.isNotEmpty()
    }

    /** Caller must hold the lock. Ends the part being received before [char], arriving at
     *  [timeMs], is appended to it - when the part is due to end (see the class comment). */
    private fun cutIfDue(transmission: OngoingTransmission, char: Char, timeMs: Long) {
        val length = transmission.text.length
        val durationMs = transmission.partDurationMs(timeMs)
        when {
            // a text of nothing but line breaks is still cut, so that the text held here stays bounded
            char.isLineBreak() && length >= K2K_PART_HARD_CHARS -> cut(transmission, length)
            char.isLineBreak() -> if (length + 1 >= K2K_PART_CHARS || durationMs >= K2K_PART_MS) transmission.cutAfterLineBreak = true
            transmission.cutAfterLineBreak -> cut(transmission, length)
            length >= K2K_PART_HARD_CHARS || durationMs >= K2K_PART_HARD_MS -> {
                val space = transmission.text.lastIndexOf(" ")
                cut(transmission, if (space >= 0 && space >= length - K2K_PART_HARD_SPACE_WINDOW) space + 1 else length)
            }
        }
    }

    /** Caller must hold the lock. Finalizes the first [length] characters as a part; the rest
     *  stays as the beginning of the next part. */
    private fun cut(transmission: OngoingTransmission, length: Int) {
        val part = maxOf(transmission.part, 1)
        finalize(transmission, length, part)
        transmission.earlierTail = (transmission.earlierTail + transmission.text.substring(0, length)).takeLast(K2K_EARLIER_TAIL_CHARS)
        transmission.dropFirst(length)
        transmission.part = part + 1
        transmission.cutAfterLineBreak = false
    }

    /** Stores the first [length] characters of [transmission] as part [part]. */
    private fun finalize(transmission: OngoingTransmission, length: Int = transmission.text.length, part: Int = transmission.part) {
        if (length < K2K_MIN_PERSISTED_LENGTH) return
        val text = transmission.text.substring(0, length)
        // Parsed once the stream has stopped - a transmission still being received may end mid-word.
        val (fromCallsign, toCallsign) = parseK2kCallsigns(text)
        persist(transmission.toPacket(K2kText(text, fromCallsign, toCallsign, part), endMs = transmission.timeOfMs(length - 1)))
    }

    private fun latest(): K2kTransmission? = ongoing.values.maxByOrNull { it.lastUpdateMs }
        ?.let { it.toPacket(K2kText(it.text.toString(), part = it.part), endMs = null) }

    private fun OngoingTransmission.toPacket(text: K2kText, endMs: Long?) =
        spec.packet(timestampMs = startMs, frequencyHz = frequencyHz, raw = null, phy = phy, data = text, endTimestampMs = endMs)
}
