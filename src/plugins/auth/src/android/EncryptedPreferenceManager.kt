package com.foxdebug.acode.rk.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.IOException
import java.security.GeneralSecurityException

class EncryptedPreferenceManager(context: Context, prefName: String) {

    private val sharedPreferences: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        EncryptedSharedPreferences.create(
            prefName,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        when (e) {
            is GeneralSecurityException, is IOException -> {
                context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            }
            else -> throw e
        }
    }

    // --- Reusable Methods ---

    fun setString(key: String, value: String?) {
        sharedPreferences.edit { putString(key, value) }
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return sharedPreferences.getString(key, defaultValue)
    }

    fun setInt(key: String, value: Int) {
        sharedPreferences.edit { putInt(key, value) }
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    fun setBoolean(key: String, value: Boolean) {
        sharedPreferences.edit { putBoolean(key, value) }
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    fun remove(key: String) {
        sharedPreferences.edit { remove(key) }
    }

    fun exists(key: String): Boolean {
        return sharedPreferences.contains(key)
    }

    fun clear() {
        sharedPreferences.edit { clear() }
    }
}
