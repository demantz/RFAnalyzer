package com.mantz_it.rfanalyzer.digiext.pocsag

import com.mantz_it.rfanalyzer.digiext.db.liveObject
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - POCSAG Aggregate Tests</h1>
 *
 * Module:      TestPocsagAggregate.kt
 * Description: A pager is its most recent page, and its row text follows the same reading
 *              precedence as that page's own ([primaryReading]) - a capcode whose row text
 *              disagreed with its expanded message list would be worse than either rule alone. The
 *              fixtures are synthetic pages.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestPocsagAggregate {

    private fun page(
        address: Long, function: Int, alpha: String?, numeric: String?, encoding: PocsagAlphaEncoding,
        skyper: String? = null, tag: String? = null,
    ) = pocsagPage(function, alpha, numeric, encoding, skyper, tag, address = address, timestampMs = 1_000)

    private fun pagerAfter(page: PocsagPage): PocsagPager = runBlocking {
        val store = memoryObjectStore(PocsagPagerFamily)
        applyPocsagPage(store, page)
        store.liveObject(pocsagObjectKey(page.phy.address)).state
    }

    @Test
    fun aSkyperPageShowsItsDecodedTextAndKeepsItsTag() {
        val pager = pagerAfter(
            page(
                4520, 3, ";\"OFXT;!Dmvc!ofu!upojhiu!31;11!mpdbm", "U31-3-885.]]244 6U-7 1.7U9U]1..3 [-.647.U]1.95 671U858.-072U8U-",
                PocsagAlphaEncoding.SKYPER, skyper = "Club net tonight 20:00 local", tag = "NEWS",
            ),
        )
        assertEquals("Club net tonight 20:00 local", pager.primaryText)
        assertEquals("NEWS", pager.message.skyperTag)
    }

    /** A capcode that never carried a payload must still produce a labelable row, not a crash. */
    @Test
    fun aTonePagerHasNoRowText() {
        assertNull(pagerAfter(page(1000, 1, null, null, PocsagAlphaEncoding.UNKNOWN)).primaryText)
    }

    /** fn0 prefers the numeric reading (capcode 2504's time broadcast). */
    @Test
    fun function0PrefersNumeric() {
        val pager = pagerAfter(page(2504, 0, "! \u0001`L#A\u0011", "120500   150326", PocsagAlphaEncoding.BINARY))
        assertEquals("120500   150326", pager.primaryText)
    }

    /** A PLAIN alpha reading outranks fn0, so the row shows the text rather than numeric mojibake
     *  (a sender that transmits its texts as fn0). */
    @Test
    fun function0WithPlainAlphaPrefersAlpha() {
        val pager = pagerAfter(
            page(1234567, 0, "Club net tonight 20:00 local", "3467-5 027799]02.[U--9]33.9]0291 47038188-[]1789-", PocsagAlphaEncoding.PLAIN),
        )
        assertEquals("Club net tonight 20:00 local", pager.primaryText)
    }
}
