package com.example.locallore

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DebugLogger {
    private const val TAG = "LocalLoreDebug"
    private const val LOG_FILE_NAME = "app_debug_logs.txt"
    
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(context: Context, message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $message"
        
        // 1. Log to Logcat (for when connected)
        Log.d(TAG, logEntry)
        
        // 2. Log to File (for when testing on the road)
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            file.appendText("$logEntry\n")
            
            // Keep file size reasonable (trim to last 500 lines if it gets too big)
            val lines = file.readLines()
            if (lines.size > 500) {
                file.writeText(lines.takeLast(400).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    fun getLogs(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.readText() else "No logs yet."
        } catch (e: Exception) {
            "Error reading logs"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.delete()
        } catch (e: Exception) {}
    }
}
