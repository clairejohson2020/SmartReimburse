package com.smartreimburse.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class SyncCredentialsStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "smart_reimburse_sync_credentials",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID()
            .toString()
            .replace("-", "_")
            .also { preferences.edit().putString(KEY_DEVICE_ID, it).apply() }

    var accessToken: String?
        get() = preferences.getString(KEY_ACCESS_TOKEN, null)
        set(value) = preferences.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var pairingId: String?
        get() = preferences.getString(KEY_PAIRING_ID, null)
        set(value) = preferences.edit().putString(KEY_PAIRING_ID, value).apply()

    var pairingSecret: String?
        get() = preferences.getString(KEY_PAIRING_SECRET, null)
        set(value) = preferences.edit().putString(KEY_PAIRING_SECRET, value).apply()

    var accountUserId: String?
        get() = preferences.getString(KEY_USER_ID, null)
        set(value) = preferences.edit().putString(KEY_USER_ID, value).apply()

    fun cursor(entity: String): Long = preferences.getLong("cursor_$entity", 0L)

    fun setCursor(entity: String, value: Long) {
        preferences.edit().putLong("cursor_$entity", value).apply()
    }

    fun clearSession() {
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_PAIRING_ID)
            .remove(KEY_PAIRING_SECRET)
            .remove(KEY_USER_ID)
            .remove("cursor_projects")
            .remove("cursor_expenses")
            .remove("cursor_attachments")
            .remove("cursor_tombstones")
            .apply()
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_PAIRING_ID = "pairing_id"
        private const val KEY_PAIRING_SECRET = "pairing_secret"
        private const val KEY_USER_ID = "user_id"
    }
}
