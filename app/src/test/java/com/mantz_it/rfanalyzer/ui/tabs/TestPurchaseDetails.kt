package com.mantz_it.rfanalyzer.ui.tabs

import com.mantz_it.rfanalyzer.billing.OwnedPurchase
import com.mantz_it.rfanalyzer.billing.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Purchase Details Tests</h1>
 *
 * Module:      TestPurchaseDetails.kt
 * Description: Tests for [purchaseDetailsText]: one line per owned purchase with its order number,
 *              and a purchase redeemed with a promo code, which has none.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestPurchaseDetails {

    @Test
    fun onePurchasePerLineWithItsOrderNumber() {
        val lines = purchaseDetailsText(listOf(
            OwnedPurchase(Product.BASE_VERSION, "GPA.1234-5678-9012-34567", 1794482331040),
            OwnedPurchase(Product.FULL_VERSION_UPGRADE, null, 1794484923517),
        )).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Base Version · ") && lines[0].endsWith(" · GPA.1234-5678-9012-34567"))
        // a promo code has no order number
        assertTrue(lines[1], lines[1].startsWith("Full Version Upgrade · ") && lines[1].endsWith(" · promo code (no order number)"))
    }
}
