package com.arflix.tv.util

import android.os.Build
import com.arflix.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Remote crash logger that sends debug checkpoints and crash reports
 * to Supabase `debug_logs` table. Fire-and-forget — never throws.
 *
 * Usage:
 *   RemoteCrashLogger.checkpoint("HomeScreen", "composing profile overlay")
 *   RemoteCrashLogger.error("HomeScreen", "crash in init", exception)
 *
 * SQL to create table:
 *   See companion object CREATE_TABLE_SQL
 */
object RemoteCrashLogger {

    /**
     * Run this SQL in the Supabase SQL Editor to create the debug_logs table:
     *
     * CREATE TABLE IF NOT EXISTS debug_logs (
     *   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
     *   created_at timestamptz DEFAULT now(),
     *   device_id text,
     *   app_version text,
     *   device_model text,
     *   tag text,
     *   message text,
     *   level text DEFAULT 'info',
     *   stacktrace text
     * );
     *
     * ALTER TABLE debug_logs ENABLE ROW LEVEL SECURITY;
     *
     * CREATE POLICY "Allow anon insert" ON debug_logs
     *   FOR INSERT TO anon WITH CHECK (true);
     *
     * CREATE POLICY "Allow anon select own" ON debug_logs
     *   FOR SELECT TO anon USING (true);
     */

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingLogs = ConcurrentLinkedQueue<LogEntry>()
    @Volatile private var isFlushing = false

    private val supabaseUrl: String get() = Constants.SUPABASE_URL
    private val supabaseKey: String get() = Constants.SUPABASE_ANON_KEY
    private val deviceId: String by lazy {
        "${Build.BRAND}_${Build.MODEL}_${Build.SERIAL.takeLast(6)}"
    }
    private val appVersion: String = BuildConfig.VERSION_NAME
    private val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"

    private data class LogEntry(
        val tag: String,
        val message: String,
        val level: String,
        val stacktrace: String?,
        val timestamp: String
    )

    private fun now(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /**
     * Log a checkpoint — lightweight breadcrumb showing the app reached this point.
     */
    fun checkpoint(tag: String, message: String) {
        enqueue(tag, message, "info", null)
    }

    /**
     * Log an error with optional exception.
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val trace = throwable?.let { t ->
            buildString {
                appendLine("${t.javaClass.name}: ${t.message}")
                t.stackTrace.take(30).forEach { appendLine("  at $it") }
                t.cause?.let { cause ->
                    appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace.take(15).forEach { appendLine("  at $it") }
                }
            }
        }
        enqueue(tag, message, "error", trace)
    }

    /**
     * Log a fatal crash — blocks briefly to ensure the log is sent before the process dies.
     */
    fun fatal(tag: String, message: String, throwable: Throwable) {
        val trace = buildString {
            appendLine("${throwable.javaClass.name}: ${throwable.message}")
            throwable.stackTrace.forEach { appendLine("  at $it") }
            throwable.cause?.let { cause ->
                appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause.stackTrace.forEach { appendLine("  at $it") }
            }
        }
        // Send synchronously on a new thread — we have ~2s before the process is killed
        try {
            val thread = Thread {
                sendToSupabase(LogEntry(tag, message, "fatal", trace, now()))
            }
            thread.start()
            thread.join(3000) // Wait max 3 seconds
        } catch (_: Exception) { }
    }

    private fun enqueue(tag: String, message: String, level: String, stacktrace: String?) {
        pendingLogs.add(LogEntry(tag, message, level, stacktrace, now()))
        flush()
    }

    private fun flush() {
        if (isFlushing) return
        isFlushing = true
        scope.launch {
            try {
                while (true) {
                    val entry = pendingLogs.poll() ?: break
                    sendToSupabase(entry)
                }
            } catch (_: Exception) {
            } finally {
                isFlushing = false
                // If more items were added during flush, trigger another flush
                if (pendingLogs.isNotEmpty()) flush()
            }
        }
    }

    private fun sendToSupabase(entry: LogEntry) {
        try {
            val url = URL("$supabaseUrl/rest/v1/debug_logs")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", supabaseKey)
            conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true

            // Manual JSON construction to avoid Gson dependency in util package
            val json = buildString {
                append("{")
                append("\"device_id\":${jsonString(deviceId)},")
                append("\"app_version\":${jsonString(appVersion)},")
                append("\"device_model\":${jsonString(deviceModel)},")
                append("\"tag\":${jsonString(entry.tag)},")
                append("\"message\":${jsonString(entry.message)},")
                append("\"level\":${jsonString(entry.level)}")
                if (entry.stacktrace != null) {
                    append(",\"stacktrace\":${jsonString(entry.stacktrace)}")
                }
                append("}")
            }

            OutputStreamWriter(conn.outputStream).use { it.write(json) }
            conn.responseCode // Force send
            conn.disconnect()
        } catch (_: Exception) {
            // Fire and forget — never crash the app from logging
        }
    }

    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
