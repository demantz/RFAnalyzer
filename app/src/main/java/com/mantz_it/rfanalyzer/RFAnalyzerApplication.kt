package com.mantz_it.rfanalyzer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionConnection
import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import dagger.hilt.android.HiltAndroidApp

/**
 * <h1>RF Analyzer - Application Class</h1>
 *
 * Module:      RFAnalyzerApplication.kt
 * Description: The application class of the app (uses Hilt for dependency injection).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


@HiltAndroidApp
class RFAnalyzerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // before anything is shown: whether the digital features are shown (DigitalFeatures)
        DigitalFeatures.update(DigitalExtensionConnection.isDigitalExtensionPackageInstalled(this))
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            "SERVICE_CHANNEL",
            "Foreground Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        // Heads-up: the app was swiped away while a recording runs (see AnalyzerService.onTaskRemoved)
        val recordingChannel = NotificationChannel(
            "RECORDING_CHANNEL",
            "Recording in the background",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(recordingChannel)
    }
}
