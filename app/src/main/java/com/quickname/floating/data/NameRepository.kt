package com.quickname.floating.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

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
        private const val KEY_CUSTOM_FIRST = "custom_first_names"
        private const val KEY_CUSTOM_LAST = "custom_last_names"
        private const val KEY_ENABLE_FIRST = "enable_first_name"
        private const val KEY_ENABLE_LAST = "enable_last_name"
    }

    var enableFirstName: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_FIRST, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_FIRST, value).apply()

    var enableLastName: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_LAST, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_LAST, value).apply()

    var buttonSizeDp: Int = 60
    var buttonColorHex: Long = 0xFF0066FF

    var totalGeneratedCount: Int
        get() = prefs.getInt(KEY_TOTAL_GENERATED, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_GENERATED, value).apply()

    fun incrementTotalGenerated() {
        totalGeneratedCount = totalGeneratedCount + 1
    }

    // Custom Names Compatibility
    fun getCustomFirstNames(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_FIRST, emptySet()) ?: emptySet()
    }

    fun addCustomFirstName(name: String) {
        val current = getCustomFirstNames().toMutableSet()
        current.add(name.trim())
        prefs.edit().putStringSet(KEY_CUSTOM_FIRST, current).apply()
    }

    fun getCustomLastNames(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_LAST, emptySet()) ?: emptySet()
    }

    fun addCustomLastName(name: String) {
        val current = getCustomLastNames().toMutableSet()
        current.add(name.trim())
        prefs.edit().putStringSet(KEY_CUSTOM_LAST, current).apply()
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

    private val defaultFirstNames = listOf(
        "Liam", "Noah", "Oliver", "James", "Elijah", "William", "Henry", "Lucas", "Benjamin", "Theodore",
        "Mateo", "Levi", "Sebastian", "Daniel", "Jack", "Michael", "Alexander", "Owen", "Asher", "Samuel",
        "Ethan", "John", "David", "Joseph", "Jackson", "Julian", "Wyatt", "Carter", "Luke", "Grayson"
    )

    private val defaultLastNames = listOf(
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez",
        "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin"
    )

    // Generates from single-use queue first; if empty, falls back to realistic random US names
    fun generateName(): GeneratedName {
        val queue = prefs.getImportedQueue()
        if (queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            prefs.setImportedQueue(queue)
            prefs.incrementTotalGenerated()
            return next
        }

        val allFirst = defaultFirstNames + prefs.getCustomFirstNames()
        val allLast = defaultLastNames + prefs.getCustomLastNames()

        val first = if (allFirst.isNotEmpty()) allFirst[Random.nextInt(allFirst.size)] else "John"
        val last = if (allLast.isNotEmpty()) allLast[Random.nextInt(allLast.size)] else "Smith"

        prefs.incrementTotalGenerated()
        return GeneratedName(first, last)
    }

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
