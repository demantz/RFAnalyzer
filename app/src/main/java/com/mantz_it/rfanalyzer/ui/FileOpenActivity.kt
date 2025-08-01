package com.mantz_it.rfanalyzer.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * <h1>RF Analyzer - File Open Activity</h1>
 *
 * Module:      FileOpenActivity.kt
 * Description: A helper activity that receives incoming file-open intents and forwards it to
 *              the MainActivity. Having a separate Activity for this is necessary so that the
 *              MainActivity can be called with NEW_TASK and CLEAR_TOP (effectively opening the
 *              file in a separate 'window' and making sure no duplicates of MainActivity show
 *              up in the Android task overview.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

class FileOpenActivity: ComponentActivity() {
    companion object {
        private const val TAG = "FileOpenActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        Log.i(TAG, "onCreate: uri=$uri")

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION  // Preserve read permission for the MainActivity
        }

        startActivity(mainIntent)
        finish()
    }

}