package com.eggwatch.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * Append-only JSONL record of what the watcher saw: Egged screens, QR codes
 * appearing and disappearing, sends to the watch, screenshot failures.
 * One ride of this answers "does the code rotate?" and "can we see it at all?".
 *
 * Pull it with:  adb shell run-as com.eggwatch.app cat files/ride-log.jsonl
 */
object RideLog {

    const val FILE_NAME = "ride-log.jsonl"
    private const val TAG = "RideLog"
    private const val KEEP_IN_MEMORY = 100
    private const val MAX_FILE_BYTES = 2_000_000L

    private var file: File? = null
    private val _entries = MutableStateFlow<List<JSONObject>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<JSONObject>> = _entries

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        if (f.exists()) {
            _entries.value = f.readLines()
                .takeLast(KEEP_IN_MEMORY)
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .reversed()
        }
    }

    @Synchronized
    fun add(type: String, fill: JSONObject.() -> Unit = {}) {
        val entry = JSONObject().put("t", System.currentTimeMillis()).put("type", type).apply(fill)
        file?.let { f ->
            if (f.length() > MAX_FILE_BYTES) f.renameTo(File(f.parentFile, "$FILE_NAME.old"))
            f.appendText(entry.toString() + "\n")
        }
        _entries.value = (listOf(entry) + _entries.value).take(KEEP_IN_MEMORY)
        Log.i(TAG, entry.toString())
    }

    @Synchronized
    fun clear() {
        file?.delete()
        _entries.value = emptyList()
    }
}
