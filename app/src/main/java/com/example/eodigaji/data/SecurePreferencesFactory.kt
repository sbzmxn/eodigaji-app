package com.example.eodigaji.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePreferencesFactory {
    private const val TAG = "SecurePreferences"
    private const val SECURE_PREFS_NAME = "eodigaji_secure_prefs"
    private const val LEGACY_PREFS_NAME = "eodigaji_prefs"

    @Volatile
    private var instance: SharedPreferences? = null

    fun getEncryptedSharedPreferences(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createSecurePrefs(context.applicationContext).also { instance = it }
        }
    }

    private fun createSecurePrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val securePrefs = EncryptedSharedPreferences.create(
                appContext,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            migrateFromLegacyIfNeeded(appContext, securePrefs)
            securePrefs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences; falling back to private prefs", e)
            appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun migrateFromLegacyIfNeeded(context: Context, securePrefs: SharedPreferences) {
        try {
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val allEntries = legacyPrefs.all
            if (allEntries.isNotEmpty()) {
                val editor = securePrefs.edit()
                for ((key, value) in allEntries) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(key, value as? Set<String>)
                        }
                    }
                }
                editor.apply()
                // 평문 저장소 비우기
                legacyPrefs.edit().clear().apply()
                Log.d(TAG, "Migrated ${allEntries.size} entries from legacy plaintext prefs to EncryptedSharedPreferences")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during migration from legacy prefs", e)
        }
    }
}
