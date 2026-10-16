package com.berkacaki

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

fun Context.getAppPreferences(): SharedPreferences {
    val deviceProtectedContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        this.createDeviceProtectedStorageContext()
    } else {
        this
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            // Migrate existing preferences from credential-encrypted to device protected storage
            deviceProtectedContext.moveSharedPreferencesFrom(this, "berkacaki_prefs")
        } catch (e: Exception) {
            Log.e("PreferencesHelper", "Error migrating shared preferences: ${e.message}", e)
        }
    }
    return deviceProtectedContext.getSharedPreferences("berkacaki_prefs", Context.MODE_PRIVATE)
}
