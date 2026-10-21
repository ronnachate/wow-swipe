package com.wow.wswipe.ime

import android.content.Context
import android.content.SharedPreferences

/** User settings, shared by the keyboard service and the settings screen. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("wswipe", Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString(KEY_LANGUAGE, "th") ?: "th"
        set(v) = sp.edit().putString(KEY_LANGUAGE, v).apply()

    /** Hold time before a key switches to its second character (ms). */
    var longPressDelay: Int
        get() = sp.getInt(KEY_LONG_PRESS, 250)
        set(v) = sp.edit().putInt(KEY_LONG_PRESS, v).apply()

    /** Keyboard height, percent of default. */
    var heightPercent: Int
        get() = sp.getInt(KEY_HEIGHT, 100)
        set(v) = sp.edit().putInt(KEY_HEIGHT, v).apply()

    var haptics: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(v) = sp.edit().putBoolean(KEY_HAPTICS, v).apply()

    var showTrail: Boolean
        get() = sp.getBoolean(KEY_TRAIL, true)
        set(v) = sp.edit().putBoolean(KEY_TRAIL, v).apply()

    var swipeEnabled: Boolean
        get() = sp.getBoolean(KEY_SWIPE, true)
        set(v) = sp.edit().putBoolean(KEY_SWIPE, v).apply()

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_LONG_PRESS = "long_press_delay"
        const val KEY_HEIGHT = "height_percent"
        const val KEY_HAPTICS = "haptics"
        const val KEY_TRAIL = "trail"
        const val KEY_SWIPE = "swipe"
    }
}
