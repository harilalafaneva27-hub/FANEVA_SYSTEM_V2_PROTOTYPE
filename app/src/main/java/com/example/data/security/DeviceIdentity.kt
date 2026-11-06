package com.example.data.security

import android.content.Context
import java.util.UUID

/**
 * Persistent, app-scoped installation identifier.
 * This is an identifier, not an authentication credential.
 */
object DeviceIdentity {
    private const val PREFS_NAME = "faneva_installation"
    private const val KEY_DEVICE_ID = "device_id"

    fun getOrCreate(context: Context): String {
        val appContext = context.applicationContext
        return synchronized(appContext) {
            val preferences = appContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val existing = preferences.getString(KEY_DEVICE_ID, null)
            val validExisting = existing?.let { stored ->
                runCatching {
                    UUID.fromString(stored).toString()
                        .takeIf { canonical -> canonical == stored }
                }.getOrNull()
            }

            if (validExisting != null) {
                validExisting
            } else {
                val generated = UUID.randomUUID().toString()
                check(preferences.edit().putString(KEY_DEVICE_ID, generated).commit()) {
                    "Tsy voatahiry maharitra ny installation device ID."
                }
                generated
            }
        }
    }
}
