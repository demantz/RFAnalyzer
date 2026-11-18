package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadMessage
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticEncrypted
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagAlphaEncoding
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * <h1>RF Analyzer - JSON Encode Benchmark</h1>
 *
 * Module:      PerfBenchmarkJsonEncode.kt
 * Description: Host-JVM microbenchmark of a packet's JSON encode: it runs on the caller's thread
 *              (the Binder thread delivering the IPC event) when the packet becomes its row, before
 *              [DigitalStoreWriter] queues it. Answers "is this encode a meaningful per-packet cost
 *              at realistic packet rates".
 *
 * Two representative message types (POCSAG - flat, no nested payload; LoRa - a Meshtastic packet,
 * one level of nested sealed-class payload) rather than exhaustive per-mode coverage: the goal is
 * an order-of-magnitude answer - message sizes across modes are all similar (small structs, up to
 * a few hundred bytes of string/byte content).
 *
 * Run: ./gradlew testPlayDebugUnitTest --tests "com.mantz_it.rfanalyzer.digiext.db.PerfBenchmarkJsonEncode"
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class PerfBenchmarkJsonEncode {

    companion object {
        private const val WARMUP_ITERATIONS = 2000
        private const val MEASURED_ITERATIONS = 20000
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun samplePocsagMessage() = PocsagMessage(
        alphaMessage = "Club net tonight at 20:00 local time on 145.500 MHz - everyone welcome to check in!!",
        numericMessage = null,
        alphaEncoding = PocsagAlphaEncoding.PLAIN,
    )

    // A stored LoRa row encodes only the parsed message as JSON; the raw payload is a BLOB.
    private fun sampleLoraMessage(): LoraPayloadMessage = MeshtasticPacket(
        dest = 0xFFFFFFFFL, sender = 0x1234L, packetId = 1L, hopLimit = 3, wantAck = false, hopStart = 3, viaMqtt = false,
        channelHash = 8, info = MeshtasticEncrypted(Bytes(ByteArray(64) { it.toByte() })),
    )

    private fun benchmark(label: String, iterations: Int, warmup: Int, encode: () -> String) {
        repeat(warmup) { encode() }
        val start = System.nanoTime()
        var totalChars = 0L
        repeat(iterations) { totalChars += encode().length }
        val elapsedNs = System.nanoTime() - start
        val perCallUs = elapsedNs / 1000.0 / iterations
        println("PerfBenchmarkJsonEncode.$label: %.2fus/call (%d iterations, avg %d chars)".format(
            perCallUs, iterations, totalChars / iterations
        ))
    }

    @Test
    fun benchmarkPocsagEncode() {
        val pkt = samplePocsagMessage()
        benchmark("pocsag", MEASURED_ITERATIONS, WARMUP_ITERATIONS) { json.encodeToString(pkt) }
    }

    @Test
    fun benchmarkLoraEncode() {
        val pkt = sampleLoraMessage()
        benchmark("lora", MEASURED_ITERATIONS, WARMUP_ITERATIONS) { json.encodeToString(pkt) }
    }
}
