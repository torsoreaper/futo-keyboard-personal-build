package org.futo.inputmethod.latin.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns debug logging on/off without a rebuild. When enabled, everything written through
 * [DebugFileLogger] is appended to the rotating log files below.
 */
val DebugFileLoggingEnabled = SettingsKey(
    booleanPreferencesKey("__debug_file_logging_enabled"),
    false
)

/**
 * A debug logger that keeps its own history on disk instead of relying on logcat.
 *
 * logcat's ring buffer is only a few megabytes and is wiped when the system is short on memory,
 * which makes it useless for tracking down a problem reported after the fact. This logger writes
 * to the app's private files directory and rotates the file whenever it reaches [CHUNK_BYTES],
 * keeping at most [MAX_CHUNKS] chunks. Once the limit is reached the oldest chunk is overwritten,
 * so the total on-disk size never exceeds [MAX_BYTES] and nothing is ever silently flushed.
 *
 * All writes go through the single thread that owns [lock], so rotation cannot race with a write.
 */
object DebugFileLogger {
    private const val TAG = "DebugFileLogger"

    /** Size at which a log file is rotated to the next chunk. */
    private const val CHUNK_BYTES = 5L * 1024L * 1024L

    /** Number of chunks to keep: 50 * 5 MB = the configured 250 MB ceiling. */
    private const val MAX_CHUNKS = 50

    const val MAX_BYTES = CHUNK_BYTES * MAX_CHUNKS

    private const val DIRECTORY_NAME = "debuglogs"
    private const val FILE_PREFIX = "keyboard-"
    private const val FILE_SUFFIX = ".log"

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var logDirectory: File? = null
    private var currentChunkIndex = 0
    private var currentChunkBytes = 0L
    private var enabled = false
    private var initialized = false

    /**
     * Loads the persisted on/off state. Safe to call repeatedly; only the first call does work.
     */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            logDirectory = File(context.filesDir, DIRECTORY_NAME)
            enabled = runCatching { context.getSetting(DebugFileLoggingEnabled) }
                .getOrDefault(false)
            if (!enabled) return
            openNewestChunk()
        }
    }

    fun isEnabled(): Boolean = synchronized(lock) { enabled }

    /**
     * Enables or disables capture and persists the choice. Enabling continues in the newest chunk
     * so a reboot or a keyboard restart does not lose the surrounding history.
     */
    fun setEnabled(context: Context, value: Boolean) {
        synchronized(lock) {
            enabled = value
            if (value) {
                logDirectory = File(context.filesDir, DIRECTORY_NAME)
                openNewestChunk()
            }
        }
        try {
            context.setSettingBlocking(DebugFileLoggingEnabled.key, value)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist logging state", e)
        }
        Log.i(TAG, "Debug file logging ${if (value) "enabled" else "disabled"}")
    }

    fun log(message: String) {
        synchronized(lock) {
            if (!enabled) return
            val directory = logDirectory ?: return
            try {
                if (!directory.exists()) directory.mkdirs()
                if (currentChunkBytes >= CHUNK_BYTES) rotate()
                val line = timestampFormat.format(Date()) + " " + message + "\n"
                FileOutputStream(currentChunkFile(directory), true).use { stream ->
                    OutputStreamWriter(stream).use { writer -> writer.write(line) }
                }
                currentChunkBytes += line.toByteArray().size
            } catch (e: Exception) {
                // Never let logging break the keyboard.
                Log.w(TAG, "Failed to write debug log", e)
            }
        }
    }

    /** Returns the log files, oldest first, for pulling off the device. */
    fun getLogFiles(context: Context): List<File> {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        return directory.listFiles { file -> file.name.startsWith(FILE_PREFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun clear(context: Context) {
        synchronized(lock) {
            val directory = File(context.filesDir, DIRECTORY_NAME)
            directory.listFiles()?.forEach { it.delete() }
            currentChunkIndex = 0
            currentChunkBytes = 0L
            if (enabled) {
                logDirectory = directory
                directory.mkdirs()
            }
        }
    }

    private fun currentChunkFile(directory: File): File =
        File(directory, chunkFileName(currentChunkIndex))

    private fun chunkFileName(index: Int): String =
        FILE_PREFIX + index.toString().padStart(3, '0') + FILE_SUFFIX

    /**
     * Finds the highest-numbered existing chunk, or falls back to its nearest unused slot, and
     * continues writing there.
     */
    private fun openNewestChunk() {
        val directory = logDirectory ?: return
        try {
            directory.mkdirs()
            val existingIndexes = directory.listFiles { file -> file.name.startsWith(FILE_PREFIX) }
                ?.mapNotNull { nameToIndex(it.name) }
                ?: emptyList()
            currentChunkIndex = if (existingIndexes.isEmpty()) {
                0
            } else {
                val newest = existingIndexes.max()
                val newestFile = File(directory, chunkFileName(newest))
                if (newestFile.length() < CHUNK_BYTES) {
                    newest
                } else {
                    // The newest chunk is full; take the slot after it, wrapping and overwriting
                    // the oldest chunk when we run out of slots.
                    (newest + 1) % MAX_CHUNKS
                }
            }
            currentChunkBytes = currentChunkFile(directory).length()
        } catch (e: Exception) {
            Log.w(TAG, "Could not open newest chunk", e)
            currentChunkIndex = 0
            currentChunkBytes = 0L
        }
    }

    /**
     * Moves to the next chunk slot. Writing truncates the slot, so this is what overwrites the
     * oldest chunk once all [MAX_CHUNKS] slots have been used.
     */
    private fun rotate() {
        val directory = logDirectory ?: return
        currentChunkIndex = (currentChunkIndex + 1) % MAX_CHUNKS
        val next = currentChunkFile(directory)
        if (next.exists()) next.delete()
        currentChunkBytes = 0L
    }

    private fun nameToIndex(name: String): Int? =
        name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toIntOrNull()
}
