package com.audiosleep

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Applies the saved theme before any activity is created, so the very first frame is
 * already in the right mode (no flash of the wrong background).
 */
class App : Application() {

    companion object {
        const val PREF_UI = "ui"
        const val KEY_NIGHT = "night_mode"
    }

    override fun onCreate() {
        super.onCreate()
        val night = getSharedPreferences(PREF_UI, MODE_PRIVATE).getBoolean(KEY_NIGHT, true)
        AppCompatDelegate.setDefaultNightMode(
            if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
