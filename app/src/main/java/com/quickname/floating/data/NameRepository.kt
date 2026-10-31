package com.quickname.floating.data

import android.content.Context
import android.content.SharedPreferences

data class GeneratedName(
    val firstName: String,
    val lastName: String
) {
    val fullName: String get() = "$firstName $lastName".trim()
}

class PreferencesManager(context: Context) {
    val prefs: SharedPreferences =
        context.getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TOTAL_GENERATED = "total_generated"
        private const val KEY_QUEUE = "single_use_name_queue"
    }

    var buttonSizeDp: Int = 60
    var buttonColorHex: Long = 0xFF0066FF

    var totalGeneratedCount: Int
        get() = prefs.getInt(KEY_TOTAL_GENERATED, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_GENERATED, value).apply()

    fun incrementTotalGenerated() {
        totalGeneratedCount = totalGeneratedCount + 1
    }

    // Single-use names queue
    fun getImportedQueue(): MutableList<GeneratedName> {
        val raw = prefs.getString(KEY_QUEUE, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("|||").mapNotNull { entry ->
            val parts = entry.split(":::")
            if (parts.isNotEmpty()) {
                GeneratedName(parts[0], parts.getOrElse(1) { "" })
            } else null
        }.toMutableList()
    }

    fun setImportedQueue(list: List<GeneratedName>) {
        val raw = list.joinToString("|||") { "${it.firstName}:::${it.lastName}" }
        prefs.edit().putString(KEY_QUEUE, raw).apply()
    }

    fun clearQueue() {
        prefs.edit().remove(KEY_QUEUE).apply()
    }
}

class NameRepository(private val prefs: PreferencesManager) {

    // Removes and returns the next single-use name (Each name used only 1 time!)
    fun getNextSingleUseName(): Pair<GeneratedName?, Int> {
        val queue = prefs.getImportedQueue()
        if (queue.isEmpty()) {
            return Pair(null, 0)
        }
        val nextName = queue.removeAt(0)
        prefs.setImportedQueue(queue)
        prefs.incrementTotalGenerated()
        return Pair(nextName, queue.size)
    }

    fun addBulkNames(rawText: String): Int {
        val lines = rawText.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
        val newEntries = lines.map { fullLine ->
            val parts = fullLine.split(" ", limit = 2)
            GeneratedName(parts[0], parts.getOrElse(1) { "" })
        }
        val currentQueue = prefs.getImportedQueue()
        currentQueue.addAll(newEntries)
        prefs.setImportedQueue(currentQueue)
        return newEntries.size
    }

    fun getRemainingCount(): Int {
        return prefs.getImportedQueue().size
    }
}
