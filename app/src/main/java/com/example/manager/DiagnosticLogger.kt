package com.example.manager

import android.util.Log
import com.example.model.LogLevel
import com.example.model.SystemLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private const val MAX_LOGS = 300
    private val buffer = mutableListOf<SystemLogEntry>()
    private val _logsFlow = MutableStateFlow<List<SystemLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<SystemLogEntry>> = _logsFlow.asStateFlow()

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String) {
        val entry = SystemLogEntry(
            tag = tag,
            message = message,
            level = level
        )
        buffer.add(0, entry)
        if (buffer.size > MAX_LOGS) {
            buffer.removeAt(buffer.size - 1)
        }
        _logsFlow.value = buffer.toList()

        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun exportFormatted(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return buffer.reversed().joinToString("\n") { entry ->
            val date = sdf.format(Date(entry.timestamp))
            "[$date] [${entry.level}] [${entry.tag}]: ${entry.message}"
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }
}
