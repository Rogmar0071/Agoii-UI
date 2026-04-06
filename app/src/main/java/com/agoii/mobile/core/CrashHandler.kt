package com.agoii.mobile.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashHandler {

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->

            Log.e(
                "AGOII_FATAL",
                throwable.stackTraceToString()
            )

            try {
                val stackTrace = StringWriter()
                throwable.printStackTrace(PrintWriter(stackTrace))

                val crashLog = """
THREAD: ${thread.name}
TIME: ${System.currentTimeMillis()}

$stackTrace
                """.trimIndent()

                val file = File(context.filesDir, "crash_log.txt")
                file.writeText(crashLog)

            } catch (_: Exception) {
                // ignore safely
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
