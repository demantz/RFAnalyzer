package com.mantz_it.rfanalyzer.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * <h1>RF Analyzer - Golden Outputs</h1>
 *
 * Module:      Golden.kt
 * Description: Golden outputs: float arrays a test compares bit for bit with a file under
 *              src/test/resources/golden/ - for code that must keep its results when it is
 *              rewritten (the files were recorded before the Java → Kotlin migration).
 *              `RECORD_GOLDEN=1 ./gradlew testPlayDebugUnitTest --tests …` writes the files from
 *              the code as it is; without it a missing file fails the test.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object Golden {
    private val dir = File("src/test/resources/golden")

    fun check(name: String, values: FloatArray) {
        val file = File(dir, "$name.f32")
        if (System.getenv("RECORD_GOLDEN") == "1") {
            dir.mkdirs()
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buffer.putFloat(it) }
            file.writeBytes(buffer.array())
            return
        }
        assertTrue("golden file $file missing - record it from the reference code (RECORD_GOLDEN=1)", file.exists())
        val expected = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals("$name: number of values", expected.remaining(), values.size)
        for (i in values.indices) {
            val e = expected.get(i)
            if (e.toRawBits() != values[i].toRawBits())
                throw AssertionError("$name[$i]: expected $e, got ${values[i]}")
        }
    }
}
