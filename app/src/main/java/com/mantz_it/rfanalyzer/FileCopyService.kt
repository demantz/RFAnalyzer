package com.mantz_it.rfanalyzer

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.max

/**
 * <h1>RF Analyzer - File Copy Service</h1>
 *
 * Module:      FileCopyService.kt
 * Description: A foreground service that exports a file to a destination URI the user picked
 *              (MainActivity uses it for files larger than 10 MB), with a progress notification
 *              that can cancel the copy and a final notification when the copy completed, was
 *              cancelled or failed.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class FileCopyService : Service() {

    companion object {
        const val TAG = "FileCopyService"
        const val ACTION_START = "filecopy.START"
        const val ACTION_CANCEL = "filecopy.CANCEL"

        const val EXTRA_SRC_PATH = "src_path"
        const val EXTRA_DEST_URI = "dest_uri"

        const val CHANNEL_ID = "file_copy"
        const val NOTIFICATION_ID = 1001

        fun start(
            context: Context,
            srcFile: File,
            destUri: Uri
        ) {
            val intent = Intent(context, FileCopyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SRC_PATH, srcFile.absolutePath)
                putExtra(EXTRA_DEST_URI, destUri)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, FileCopyService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        object FileCopyState {
            val isRunning = MutableStateFlow(false)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var copyJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCopy(intent)
            ACTION_CANCEL -> cancelCopy()
        }
        return START_NOT_STICKY
    }

    private fun startCopy(intent: Intent) {
        if (copyJob?.isActive == true) { // Already copying: ignore
            Log.w(TAG, "startCopy: Copy already in progress, ignoring start request")
            return
        }
        val srcPath = intent.getStringExtra(EXTRA_SRC_PATH) ?: return
        val destUri = intent.getParcelableExtra<Uri>(EXTRA_DEST_URI) ?: return
        val srcFile = File(srcPath)
        Log.d(TAG, "startCopy: Copying file from $srcPath to $destUri")

        startForeground(NOTIFICATION_ID, buildNotification(0, srcFile.name, true))

        copyJob = serviceScope.launch {
            try {
                FileCopyState.isRunning.value = true
                copyFileWithProgress(srcFile, destUri)
                stopForeground(STOP_FOREGROUND_DETACH)
                showCompletedNotification()
                Log.d(TAG, "startCopy: Copy completed")
            } catch (e: CancellationException) {
                Log.d(TAG, "startCopy: Copy cancelled")
                stopForeground(STOP_FOREGROUND_DETACH)
                showCancelledNotification()
            } catch (e: Exception) {
                stopForeground(STOP_FOREGROUND_DETACH)
                showErrorNotification(e.message ?: "Unknown error")
                Log.e(TAG, "startCopy: Error during copy: ${e.message}")
            } finally {
                FileCopyState.isRunning.value = false
                stopSelf()
            }
        }
    }

    private fun cancelCopy() {
        Log.d(TAG, "cancelCopy: Cancel job...")
        copyJob?.cancel()
    }

    private suspend fun copyFileWithProgress(srcFile: File, destUri: Uri) {
        val totalBytes = max(1L, srcFile.length())
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var lastProgressUpdate = Long.MIN_VALUE
        val progressStep = max(totalBytes / 100, 50*1024*1024) // every 1 %, but at most every 50 MB (files below 5 GB)

        contentResolver.openOutputStream(destUri)?.use { output ->
            srcFile.inputStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read

                    if (copied > lastProgressUpdate + progressStep) {
                        val progress = ((copied * 100) / totalBytes).toInt()
                        showNotification(buildNotification(progress, srcFile.name, true))
                        Log.d(TAG, "copyFileWithProgress: Updating progress to $progress%")
                        lastProgressUpdate = copied
                    }
                }
                showCompletedNotification()
            }
        }
    }

    private fun showNotification(notification: Notification) {
        val notificationAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else
            true
        if (notificationAllowed)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(progress: Int, fileName: String, ongoing: Boolean): Notification {
        val cancelIntent = Intent(this, FileCopyService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RF Analyzer: Exporting file ($progress%)")
            .setContentText("File: $fileName")
            .setProgress(100, progress, false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder
                .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } else {
            builder.build()
        }
    }

    private fun showCompletedNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("RF Analyzer: File saved")
            .setContentText("Copy completed")
            .setAutoCancel(true)
            .build()
        showNotification(notification)
    }

    private fun showCancelledNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle("RF Analyzer: Copy cancelled")
            .setContentText("Operation aborted")
            .setAutoCancel(true)
            .build()
        showNotification(notification)
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("RF Analyzer: Copy failed")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        showNotification(notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File copy",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows progress while exporting files"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
