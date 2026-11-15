package com.example.eodigaji.data

import android.content.Context
import android.content.SharedPreferences
import com.example.eodigaji.util.RecentBlindNames
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ArchiveItem(
    val number: Int,
    val contentId: String,
    val blindName: String,
    val realName: String,
    val address: String,
    val overviewShort: String?,
    val imageUrl: String?,
    val theme: String?,
    val lat: Double,
    val lng: Double,
    val selectedMethod: String,
    val selectedEmotion: String,
    val date: String,
)

class ArchiveRepository(context: Context) {
    private val prefs: SharedPreferences =
        SecurePreferencesFactory.getEncryptedSharedPreferences(context)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "eodigaji_prefs"
        private const val KEY_ARCHIVE_ITEMS = "archive_items_json"
        private const val KEY_PENDING_RATING_NUMBER = "pending_rating_archive_number"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted_v2"
        private const val KEY_TERMS_ACCEPTED_AT = "terms_accepted_at_v2"
        private const val KEY_LAST_DRIVE_MINUTES = "last_drive_minutes"
        private const val KEY_THEME_PREF = "theme_preference"
        private const val KEY_FONT_SCALE_LEVEL = "font_scale_level"
        private const val MAX_ARCHIVE_ITEMS = 50
        const val EMOTION_UNRATED = "미평가"
    }

    fun getPrefs(): SharedPreferences = prefs

    fun getThemePreference(): String =
        prefs.getString(KEY_THEME_PREF, "auto") ?: "auto"

    fun saveThemePreference(mode: String) {
        prefs.edit().putString(KEY_THEME_PREF, mode).apply()
    }

    fun getFontScaleLevel(): Int =
        prefs.getInt(KEY_FONT_SCALE_LEVEL, 1).coerceIn(0, 3)

    fun saveFontScaleLevel(level: Int) {
        prefs.edit().putInt(KEY_FONT_SCALE_LEVEL, level.coerceIn(0, 3)).apply()
    }

    fun getLastDriveMinutes(defaultMinutes: Int = 30): Int =
        prefs.getInt(KEY_LAST_DRIVE_MINUTES, defaultMinutes).coerceIn(15, 120)

    fun saveLastDriveMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_LAST_DRIVE_MINUTES, minutes).apply()
    }

    fun isTermsAccepted(): Boolean = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)

    fun getTermsAcceptedAt(): String? = prefs.getString(KEY_TERMS_ACCEPTED_AT, null)

    fun setTermsAccepted(accepted: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, accepted)
        if (accepted) {
            val timestamp = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date())
            editor.putString(KEY_TERMS_ACCEPTED_AT, timestamp)
        } else {
            editor.remove(KEY_TERMS_ACCEPTED_AT)
        }
        editor.apply()
    }

    fun getAll(): List<ArchiveItem> {
        val raw = prefs.getString(KEY_ARCHIVE_ITEMS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ArchiveItem>>() {}.type
            gson.fromJson<List<ArchiveItem>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun nextIndex(): Int = (getAll().maxOfOrNull { it.number } ?: 0) + 1

    fun saveItem(item: ArchiveItem) {
        val current = getAll().toMutableList()
        current.add(0, item)
        if (current.size > MAX_ARCHIVE_ITEMS) {
            current.subList(MAX_ARCHIVE_ITEMS, current.size).clear()
        }
        prefs.edit().putString(KEY_ARCHIVE_ITEMS, gson.toJson(current)).apply()
        RecentBlindNames.save(prefs, item.blindName)
    }

    fun isUnratedEmotion(emotion: String?): Boolean =
        emotion.isNullOrBlank() || emotion == EMOTION_UNRATED

    fun getPendingRatingNumber(): Int =
        prefs.getInt(KEY_PENDING_RATING_NUMBER, -1)

    fun setPendingRatingNumber(number: Int?) {
        val editor = prefs.edit()
        if (number == null || number < 0) {
            editor.remove(KEY_PENDING_RATING_NUMBER)
        } else {
            editor.putInt(KEY_PENDING_RATING_NUMBER, number)
        }
        editor.apply()
    }

    fun getPendingRatingItem(): ArchiveItem? {
        val targetNumber = getPendingRatingNumber()
        if (targetNumber < 0) return null
        val item = getAll().firstOrNull { it.number == targetNumber }
        if (item == null || !isUnratedEmotion(item.selectedEmotion)) {
            setPendingRatingNumber(null)
            return null
        }
        return item
    }

    fun updateItemEmotion(number: Int, emotion: String): Boolean {
        val current = getAll()
        var updated = false
        val next = current.map { item ->
            if (item.number == number) {
                updated = true
                item.copy(selectedEmotion = emotion)
            } else {
                item
            }
        }
        if (updated) {
            prefs.edit().putString(KEY_ARCHIVE_ITEMS, gson.toJson(next)).apply()
            if (getPendingRatingNumber() == number) {
                setPendingRatingNumber(null)
            }
        }
        return updated
    }

    fun deleteItem(number: Int) {
        val updated = getAll().filterNot { it.number == number }
        val editor = prefs.edit().putString(KEY_ARCHIVE_ITEMS, gson.toJson(updated))
        if (getPendingRatingNumber() == number) {
            editor.remove(KEY_PENDING_RATING_NUMBER)
        }
        editor.apply()
    }

    fun clearAll() {
        prefs.edit()
            .remove(KEY_ARCHIVE_ITEMS)
            .remove(KEY_PENDING_RATING_NUMBER)
            .apply()
    }

    fun todayDateString(): String =
        SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date())
}
