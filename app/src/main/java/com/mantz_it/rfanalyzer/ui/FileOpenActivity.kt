package com.mantz_it.rfanalyzer.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * <h1>RF Analyzer - File Open Activity</h1>
 *
 * Module:      FileOpenActivity.kt
 * Description: A helper activity that receives incoming file-open intents and forwards them to
 *              the MainActivity. A separate Activity is necessary so that the MainActivity can be
 *              started with NEW_TASK and CLEAR_TOP (opening the file in the app's own task, so no
 *              duplicate of MainActivity shows up in the Android task overview).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
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