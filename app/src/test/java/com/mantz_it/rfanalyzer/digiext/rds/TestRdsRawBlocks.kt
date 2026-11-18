package com.mantz_it.rfanalyzer.digiext.rds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - RDS Raw Block Tests</h1>
 *
 * Module:      TestRdsRawBlocks.kt
 * Description: The fixed-position fields of an RDS group, read from its raw blocks rather than
 *              stored apart.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestRdsRawBlocks {

    @Test
    fun blocksAreBigEndianWords() {
        val group = rdsGroup(pi = 0xD3A1, ts = 0).copy(raw = rdsBlocks(0xD3A1, 0x0421, 0xFFFF, 0x0000))
        assertEquals(0xD3A1, group.block(0))
        assertEquals(0x0421, group.block(1))
        assertEquals(0xFFFF, group.block(2))
        assertEquals(0x0000, group.block(3))
    }

    @Test
    fun piGroupTypeVersionAndPtyComeFromBlocksAAndB() {
        val group = rdsGroup(pi = 0xD3A1, ts = 0, groupType = 14, versionA = false, pty = 10)
        assertEquals(0xD3A1, group.pi)
        assertEquals(14, group.groupType)
        assertFalse(group.versionA)
        assertEquals(10, group.pty)
        assertEquals("D3A1", group.objectKey)
        assertTrue(rdsGroup(pi = 1, ts = 0).versionA)
    }

    @Test
    fun ptyNamesFollowTheRegion() {
        assertEquals("Pop Music", rdsPtyName(10, us = false))
        assertEquals("Country", rdsPtyName(10, us = true))
        assertEquals("Unknown", rdsPtyName(40, us = false))
    }

    /** The GLOB the database reads a rare group type's newest sample with matches exactly that type
     *  in the raw bytes' hex (SQLite: substr(hex(raw), 5) GLOB …). */
    @Test
    fun typeGlobMatchesOnlyItsTypeInTheRawHex() {
        fun globRegex(glob: String) = Regex(glob.replace("*", ".*"))
        for (type in 0..15) for (versionA in listOf(true, false)) {
            val group = rdsGroup(pi = 0xD3A1, ts = 0, groupType = type, versionA = versionA, pty = 31)
            val hex = group.raw!!.toByteArray().joinToString("") { "%02X".format(it) }.substring(RDS_TYPE_HEX_OFFSET)
            for (other in 0..15) for (otherA in listOf(true, false)) {
                val glob = rdsTypeHexGlob("$other${if (otherA) "A" else "B"}")!!
                assertEquals("$type/$versionA vs $other/$otherA", type == other && versionA == otherA, globRegex(glob).matches(hex))
            }
        }
        assertEquals(null, rdsTypeHexGlob("16A"))
    }
}
