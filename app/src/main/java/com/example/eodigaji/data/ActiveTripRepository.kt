package com.example.eodigaji.data

import android.content.Context
import android.content.SharedPreferences
import com.example.eodigaji.network.RecommendResponse
import com.google.gson.Gson

enum class ActiveTripPhase {
    DRIVING,
    ARRIVED
}

data class ActiveTripState(
    val phase: ActiveTripPhase,
    val recommendation: RecommendResponse,
    val blindName: String,
    val selectedMethod: String,
    val selectedRoute: String,
    val driveMinutes: Int,
    val initialEtaSeconds: Int,
    val initialDistanceMeters: Int,
    val observedEtaSeconds: Int,
    val observedDistanceMeters: Int,
    val originLabel: String,
    val isArrived: Boolean,
    val isCurrentArrivalSaved: Boolean,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
)

class ActiveTripRepository(context: Context) {
    private val prefs: SharedPreferences =
        SecurePreferencesFactory.getEncryptedSharedPreferences(context)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "eodigaji_prefs"
        private const val KEY_ACTIVE_TRIP = "active_trip_state_json"
        private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }

    fun save(state: ActiveTripState, immediate: Boolean = true) {
        val json = gson.toJson(state)
        val editor = prefs.edit().putString(KEY_ACTIVE_TRIP, json)
        if (immediate) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun load(): ActiveTripState? {
        val raw = prefs.getString(KEY_ACTIVE_TRIP, null) ?: return null
        val state = runCatching {
            gson.fromJson(raw, ActiveTripState::class.java)
        }.getOrNull() ?: return null

        val age = System.currentTimeMillis() - state.updatedAtMillis
        if (age < 0L || age > MAX_AGE_MILLIS) {
            clear()
            return null
        }
        return state
    }

    fun hasActiveTrip(): Boolean = load() != null

    fun clear() {
        prefs.edit().remove(KEY_ACTIVE_TRIP).commit()
    }
}
