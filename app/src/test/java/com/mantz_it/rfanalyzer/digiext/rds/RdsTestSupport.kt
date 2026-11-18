package com.mantz_it.rfanalyzer.digiext.rds

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.RdsEvent

/**
 * <h1>RF Analyzer - RDS Test Support</h1>
 *
 * Module:      RdsTestSupport.kt
 * Description: Helpers shared by the RDS tests: the four blocks of a group as the raw bytes the
 *              extension sends, and a stored RDS group of a station built from its PI, group type,
 *              version, PTY and the extension's events.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The four blocks of a group, big-endian, as the extension sends them. */
internal fun rdsBlocks(a: Int, b: Int, c: Int = 0, d: Int = 0): Bytes =
    Bytes(intArrayOf(a, b, c, d).flatMap { listOf((it ushr 8).toByte(), it.toByte()) }.toByteArray())

/** A group of station [pi] carrying the extension's [events]; block B holds type, version and PTY. */
internal fun rdsGroup(
    pi: Int, ts: Long, events: List<RdsEvent> = emptyList(),
    groupType: Int = 0, versionA: Boolean = true, pty: Int = 0, frequencyHz: Long = 98_000_000L,
): RdsGroup = RdsSpec.packet(
    timestampMs = ts, frequencyHz = frequencyHz, phy = Unit, data = RdsGroupContent(events),
    raw = rdsBlocks(pi, (groupType shl 12) or ((if (versionA) 0 else 1) shl 11) or (pty shl 5)),
)
