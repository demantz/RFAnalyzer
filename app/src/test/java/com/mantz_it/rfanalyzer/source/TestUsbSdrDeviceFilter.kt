package com.mantz_it.rfanalyzer.source

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * <h1>RF Analyzer - USB SDR Device Filter Tests</h1>
 *
 * Module:      TestUsbSdrDeviceFilter.kt
 * Description: res/xml/usb_sdr_devices.xml (USB_DEVICE_ATTACHED filter) must list exactly the ids
 *              of [UsbSdrType].
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestUsbSdrDeviceFilter {

    @Test
    fun deviceFilterListsExactlyTheSupportedSdrs() {
        val xml = File("src/main/res/xml/usb_sdr_devices.xml").readText()
        val filter = Regex("""<usb-device vendor-id="(\d+)" product-id="(\d+)" />""").findAll(xml)
            .map { Pair(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            .toList()
        assertEquals("duplicate entries", filter.toSet().size, filter.size)
        assertEquals(UsbSdrType.entries.flatMap { it.ids }.toSet(), filter.toSet())
    }
}
