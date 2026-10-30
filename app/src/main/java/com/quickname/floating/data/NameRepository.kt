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
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENABLE_FIRST_NAME = "enable_first_name"
        private const val KEY_ENABLE_LAST_NAME = "enable_last_name"
        private const val KEY_BUTTON_SIZE = "button_size"
        private const val KEY_BUTTON_COLOR_HEX = "button_color_hex"
        private const val KEY_TOTAL_GENERATED = "total_generated"
        private const val KEY_HISTORY = "recent_history"
        private const val KEY_FAVORITES = "favorite_names"
        private const val KEY_CUSTOM_FIRST_NAMES = "custom_first_names"
        private const val KEY_CUSTOM_LAST_NAMES = "custom_last_names"
    }

    var enableFirstName: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_FIRST_NAME, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_FIRST_NAME, value).apply()

    var enableLastName: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_LAST_NAME, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_LAST_NAME, value).apply()

    var buttonSizeDp: Int
        get() = prefs.getInt(KEY_BUTTON_SIZE, 60)
        set(value) = prefs.edit().putInt(KEY_BUTTON_SIZE, value).apply()

    var buttonColorHex: Long
        get() = prefs.getLong(KEY_BUTTON_COLOR_HEX, 0xFF0066FF)
        set(value) = prefs.edit().putLong(KEY_BUTTON_COLOR_HEX, value).apply()

    var totalGeneratedCount: Int
        get() = prefs.getInt(KEY_TOTAL_GENERATED, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_GENERATED, value).apply()

    fun incrementTotalGenerated() {
        totalGeneratedCount = totalGeneratedCount + 1
    }

    fun getHistory(): List<String> {
        val raw = prefs.getString(KEY_HISTORY, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(";;;")
    }

    fun addHistory(entry: String) {
        val current = getHistory().toMutableList()
        current.add(0, entry)
        if (current.size > 50) current.removeAt(current.size - 1)
        prefs.edit().putString(KEY_HISTORY, current.joinToString(";;;")).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun getFavorites(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
    }

    fun toggleFavorite(fullName: String) {
        val favs = getFavorites().toMutableSet()
        if (favs.contains(fullName)) favs.remove(fullName) else favs.add(fullName)
        prefs.edit().putStringSet(KEY_FAVORITES, favs).apply()
    }

    fun getCustomFirstNames(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_FIRST_NAMES, emptySet()) ?: emptySet()
    }

    fun addCustomFirstName(name: String) {
        val current = getCustomFirstNames().toMutableSet()
        current.add(name.trim())
        prefs.edit().putStringSet(KEY_CUSTOM_FIRST_NAMES, current).apply()
    }

    fun getCustomLastNames(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_LAST_NAMES, emptySet()) ?: emptySet()
    }

    fun addCustomLastName(name: String) {
        val current = getCustomLastNames().toMutableSet()
        current.add(name.trim())
        prefs.edit().putStringSet(KEY_CUSTOM_LAST_NAMES, current).apply()
    }
}

class NameRepository(private val prefs: PreferencesManager) {

    private val defaultFirstNames = listOf(
        "Liam", "Noah", "Oliver", "James", "Elijah", "William", "Henry", "Lucas", "Benjamin", "Theodore",
        "Mateo", "Levi", "Sebastian", "Daniel", "Jack", "Michael", "Alexander", "Owen", "Asher", "Samuel",
        "Ethan", "John", "David", "Joseph", "Jackson", "Julian", "Wyatt", "Carter", "Luke", "Grayson",
        "Isaac", "Jayden", "Dylan", "Anthony", "Thomas", "Charles", "Christopher", "Jaxon", "Maverick", "Josiah"
    )

    private val defaultLastNames = listOf(
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez",
        "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
        "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson"
    )

    private var lastGenerated: GeneratedName? = null

    fun generateName(): GeneratedName {
        val allFirst = defaultFirstNames + prefs.getCustomFirstNames()
        val allLast = defaultLastNames + prefs.getCustomLastNames()

        var attempts = 0
        var candidate: GeneratedName

        do {
            val first = if (prefs.enableFirstName && allFirst.isNotEmpty()) {
                allFirst[Random.nextInt(allFirst.size)]
            } else ""

            val last = if (prefs.enableLastName && allLast.isNotEmpty()) {
                allLast[Random.nextInt(allLast.size)]
            } else ""

            candidate = GeneratedName(first, last)
            attempts++
        } while (candidate == lastGenerated && attempts < 10)

        lastGenerated = candidate
        prefs.incrementTotalGenerated()
        prefs.addHistory(candidate.fullName)
        return candidate
    }
}
