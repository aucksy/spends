package com.spends.app.core

import android.content.Context
import android.os.Build
import com.spends.app.BuildConfig
import java.io.File

/**
 * Remembers the stack trace of the last crash so it can be read on the next launch.
 *
 * **Why this exists.** v1.63.0 shipped a diagnostic screen that closed the app the moment it was
 * opened. Every cloud check was green — the release APK built, Hilt/KSP codegen ran, 195 logic
 * assertions passed, and a Robolectric pass later composed the same screen without complaint — because
 * the fault only appears in the real app on a real device. The owner has no developer tools and no
 * cable, so there was no way to find out *what* threw. A diagnostic app that dies silently is the one
 * thing it must never be.
 *
 * This is deliberately tiny and dependency-free: an uncaught-exception handler that writes the trace to
 * a file and then hands the crash straight on to whatever handler was already installed, so the app
 * still dies exactly as Android intends. Nothing is swallowed and no behaviour changes.
 *
 * **Privacy.** A Java stack trace is class, method and line names from this app and the framework. It
 * carries no transactions, no message text and no personal data — unlike the capture logs, this is
 * safe to paste as-is. The only identifying values are the device model and Android version, both
 * added on purpose because an OEM-specific fault is exactly what this is for.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /** Enough for a deep Compose/Hilt trace; a runaway "Caused by" chain is cut rather than unbounded. */
    private const val MAX_CHARS = 12_000

    /**
     * Install the handler. Safe to call more than once, and safe to call before anything else is ready
     * — it touches only [Context.getFilesDir].
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // A failure to RECORD a crash must never replace the crash itself: that would hide the real
            // fault behind a second, meaningless one.
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val text = buildString {
            appendLine("SPENDS — LAST CRASH")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(error.stackTraceToString())
        }
        File(context.filesDir, FILE_NAME).writeText(text.take(MAX_CHARS))
    }

    /** The last recorded crash, or null if the app has not crashed since this was last cleared. */
    fun read(context: Context): String? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
