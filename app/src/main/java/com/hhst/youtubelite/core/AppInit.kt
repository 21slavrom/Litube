package com.hhst.youtubelite.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Async initialization helpers that move blocking startup work off the main thread.
 * Java callers use [startLoggingAsync] to launch logcat capture on [Dispatchers.IO]
 * without blocking [android.app.Application.onCreate].
 */
object AppInit {

    private const val TAG = "App"

    /**
     * Launches logcat capture on [Dispatchers.IO] via [AppScope], so the
     * [Runtime.getRuntime].exec call does not block the main thread during app
     * startup. Fire-and-forget: the logcat process runs independently and is
     * terminated when the app process dies.
     */
    @JvmStatic
    fun startLoggingAsync(logFile: File) {
        val command = arrayOf(
            "logcat",
            "-v", "threadtime",
            "*:E",
            "-f", logFile.absolutePath,
            "-n", "1",
            "-r", "1024"
        )
        AppScope.scope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(command)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start logging", e)
            }
        }
    }
}
