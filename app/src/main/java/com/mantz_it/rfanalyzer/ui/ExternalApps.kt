package com.mantz_it.rfanalyzer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * <h1>RF Analyzer - External Apps</h1>
 *
 * Module:      ExternalApps.kt
 * Description: Opens [intent] in another app (a link, a map location, an e-mail address), or says
 *              that no installed app can open it - a device without a browser or maps app would
 *              otherwise crash.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
fun Context.startExternalActivity(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("ExternalApps", "startExternalActivity: no app for ${intent.action} ${intent.scheme}")
        Toast.makeText(this, "No installed app can open this", Toast.LENGTH_SHORT).show()
    }
}
