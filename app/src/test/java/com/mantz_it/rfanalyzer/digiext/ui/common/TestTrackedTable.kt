package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Tracked Table Tests</h1>
 *
 * Module:      TestTrackedTable.kt
 * Description: Tests the logic of the Overview table: ordering by a metric, which columns fit,
 *              where the history divider goes, when the table follows new arrivals, and the frozen
 *              message list.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestTrackedTable {

    private data class Row(val name: String, val count: Int, val lastSeenMs: Long)

    private val lastHeard = TrackedMetric<Row>("Last heard", "Age", { _, _ -> "" }, { it.lastSeenMs }, isRecency = true)
    private val packets = TrackedMetric<Row>("Packets", "Packets", { r, _ -> r.count.toString() }, { it.count })
    private val name = TrackedMetric<Row>("Name", "Age", { _, _ -> "" }, { it.name }, ascending = true)

    private val rows = listOf(Row("bravo", 5, 3_000), Row("alpha", 9, 1_000), Row("delta", 1, 2_000))

    @Test
    fun lastHeardSortsNewestFirst() {
        assertEquals(listOf("bravo", "delta", "alpha"), sortTrackedItems(rows, lastHeard, ascending = false).map { it.name })
    }

    @Test
    fun aMetricStartsInItsOwnDirectionAndCanBeReversed() {
        assertEquals(listOf(9, 5, 1), sortTrackedItems(rows, packets, TrackedSortState(1).ascending(packets)).map { it.count })
        assertEquals(listOf("alpha", "bravo", "delta"), sortTrackedItems(rows, name, TrackedSortState(2).ascending(name)).map { it.name })
        assertEquals(listOf("delta", "bravo", "alpha"), sortTrackedItems(rows, name, TrackedSortState(2, ascending = false).ascending(name)).map { it.name })
    }

    /** A capcode is sorted by its number: 1023669 after 999, not before it. */
    @Test
    fun numericKeysSortNumerically() {
        val capcode = TrackedMetric<String>("Capcode", "Age", { _, _ -> "" }, { it.toLong() }, ascending = true)
        assertEquals(listOf("999", "1023669"), sortTrackedItems(listOf("1023669", "999"), capcode, ascending = true))
    }

    @Test
    fun sortIsStableForEqualKeys() {
        val tied = listOf(Row("x", 1, 5), Row("y", 1, 5), Row("z", 1, 5))
        assertEquals(listOf("x", "y", "z"), sortTrackedItems(tied, lastHeard, ascending = false).map { it.name })
    }

    private val columns = listOf(
        TrackedColumn<Row>("Kurs", 34.dp, { "" }, priority = 1),
        TrackedColumn<Row>("Abw.", 50.dp, { "" }, priority = 2),
        TrackedColumn<Row>("MP", 92.dp, { "" }, priority = 1),
        TrackedColumn<Row>("Ziel", 36.dp, { "" }, priority = 3),
    )

    /** Columns are given up by priority as the screen narrows, and keep their order. */
    @Test
    fun columnsAreDroppedByPriority() {
        assertEquals(listOf("Kurs", "Abw.", "MP", "Ziel"), visibleColumns(columns, 411.dp, 66.dp, 46.dp, hasPayload = false).map { it.label })
        assertEquals(listOf("Kurs", "Abw.", "MP"), visibleColumns(columns, 360.dp, 58.dp, 46.dp, hasPayload = false).map { it.label })
        assertEquals(listOf("Kurs", "MP"), visibleColumns(columns, 320.dp, 58.dp, 46.dp, hasPayload = false).map { it.label })
        // A less important column that fits is shown where a more important one does not.
        assertEquals(listOf("Kurs", "Abw."), visibleColumns(columns, 270.dp, 58.dp, 46.dp, hasPayload = false).map { it.label })
    }

    /** A row with text keeps 42 % of its width for it. */
    @Test
    fun aPayloadKeepsItsShareOfTheRow() {
        assertEquals(listOf("Kurs"), visibleColumns(columns, 411.dp, 96.dp, 46.dp, hasPayload = true).map { it.label })
    }

    @Test
    fun theDividerSeparatesThisSessionFromBefore() {
        val sessionStart = 2_500L
        val sorted = sortTrackedItems(rows, lastHeard, ascending = false)
        assertEquals(1, historyDividerIndex(sorted) { it.lastSeenMs < sessionStart })
        // Nothing heard this session: nothing to separate.
        assertEquals(-1, historyDividerIndex(sorted) { true })
        assertEquals(-1, historyDividerIndex(sorted) { false })
    }

    /** Newest first, rows heard after the session (in real time, while a recording replays) go
     *  below the session's rows, not on top; pinned rows stay first. */
    @Test
    fun thisSessionsRowsComeFirst() {
        val sorted = sortTrackedItems(rows + Row("echo", 2, 9_000), lastHeard, ascending = false)
        val outside = { r: Row -> r.lastSeenMs !in 1_500L..3_500L }
        assertEquals(listOf("bravo", "delta", "echo", "alpha"), sessionFirst(sorted, emptySet(), { it.name }, outside).map { it.name })
        assertEquals(listOf("alpha", "bravo", "delta", "echo"), sessionFirst(sorted, setOf("alpha"), { it.name }, outside).map { it.name })
    }

    @Test
    fun followingNeedsIntentNewestFirstAndAnIdleClosedList() {
        assertTrue(shouldFollowTop(follow = true, newestFirst = true, rowExpanded = false, scrollInProgress = false))
        assertFalse(shouldFollowTop(follow = false, newestFirst = true, rowExpanded = false, scrollInProgress = false))
        assertFalse(shouldFollowTop(follow = true, newestFirst = false, rowExpanded = false, scrollInProgress = false))
        assertFalse(shouldFollowTop(follow = true, newestFirst = true, rowExpanded = true, scrollInProgress = false))
        assertFalse(shouldFollowTop(follow = true, newestFirst = true, rowExpanded = false, scrollInProgress = true))
    }

    private val rowKey: (Row) -> String = { it.name }

    /** A frozen message list keeps its rows and their order; a row still listed shows its current
     *  content, one that dropped out keeps its frozen copy. */
    @Test
    fun frozenRowsKeepOrderAndPickUpLiveContent() {
        val frozen = listOf(Row("a", 1, 10), Row("b", 1, 20), Row("c", 1, 30))
        val live = listOf(Row("new", 1, 99), Row("c", 5, 31), Row("a", 2, 11))
        assertEquals(listOf(Row("a", 2, 11), Row("b", 1, 20), Row("c", 5, 31)), frozenRows(frozen, live, rowKey))
    }

    @Test
    fun onlyRowsUnknownAtFreezeTimeCountAsNew() {
        val frozen = listOf(Row("a", 1, 10), Row("b", 1, 20))
        assertEquals(0, newRowCount(frozen, frozen, rowKey))
        assertEquals(0, newRowCount(frozen, listOf(Row("b", 9, 21)), rowKey))
        assertEquals(2, newRowCount(frozen, listOf(Row("y", 1, 40), Row("x", 1, 30), Row("a", 1, 10)), rowKey))
    }
}
