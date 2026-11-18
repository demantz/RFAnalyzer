package com.mantz_it.rfanalyzer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * <h1>RF Analyzer - Logcat Logger</h1>
 *
 * Module:      LogcatLogger.kt
 * Description: A thread which writes the logcat log to a file.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


object LogcatLogger {
    private var loggingThread: Thread? = null
    var isLogging = false
        private set
    val logfileName = "rfanalyzer.log"

    fun startLogging(context: Context) {
        if (isLogging) return // Already running

        isLogging = true
        val logFile = File(context.filesDir, logfileName)

        loggingThread = Thread {
            try {
                Log.i("LogcatLogger", "Start Logging to ${logFile.absoluteFile}")
                val process = Runtime.getRuntime().exec("logcat -v time *:V") // Captures all logs
                val reader = process.inputStream.bufferedReader()
                val overwriteFile = logFile.length() >= 100*1024*1024
                FileOutputStream(logFile,!overwriteFile).use { outputStream ->
                    reader.useLines { lines ->
                        lines.forEach { line ->
                            if (!isLogging) {
                                return@Thread
                            }
                            outputStream.write("$line\n".toByteArray())
                            outputStream.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LogcatLogger", "Error logging Logcat", e)
            } finally {
                Log.i("LogcatLogger", "Logging Stopped!")
            }
        }.apply { start() }
    }

    fun stopLogging() {
        isLogging = false
        loggingThread?.interrupt()
        loggingThread = null
        Log.d("LogcatLogger", "stopLogging: Logging Thread interrupted!")
    }
}
