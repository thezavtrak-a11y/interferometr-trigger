package com.example.arduhud.tutorial

import android.content.Context

object TutorialPrefs {
    private const val PREFS = "arduhud_tutorial"
    private const val KEY_SEEN = "overlay_tutorial_seen"

    fun hasSeen(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEEN, false)
    }

    fun markSeen(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEN, true)
            .apply()
    }
}
