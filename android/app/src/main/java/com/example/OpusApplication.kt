package com.example

import android.app.Application
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class OpusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = StringWriter()
                throwable.printStackTrace(PrintWriter(trace))
                File(filesDir, "last_crash.txt").writeText(
                    "thread=${thread.name}\ntime=${System.currentTimeMillis()}\n$trace",
                    Charsets.UTF_8
                )
            }
            Log.e("OpusApplication", "Uncaught application crash", throwable)
            previousHandler?.uncaughtException(thread, throwable)
                ?: Process.killProcess(Process.myPid())
        }
    }
}
