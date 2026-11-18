package com.mantz_it.rfanalyzer.digiext.db

import android.content.Context
import androidx.room.Room

/**
 * <h1>RF Analyzer - Test Database</h1>
 *
 * Module:      TestDatabase.kt
 * Description: The in-memory digital extension database the instrumented database tests run
 *              against.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** An in-memory [DigitalExtensionDatabase]. */
fun testDigitalExtensionDatabase(context: Context): DigitalExtensionDatabase =
    Room.inMemoryDatabaseBuilder(context, DigitalExtensionDatabase::class.java).build()
