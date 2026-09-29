package com.cbkres.visavole.ui.theme

import android.content.Context

/**
 * The user's theme choice, persisted in [SharedPreferences]. This is a *setting*, not user data,
 * so it lives outside the state backup file (backups carry documents/trips, never the look).
 * Defaults match the previous hardcoded behaviour: follow the system theme with dynamic color on.
 */
object ThemePrefs {
    enum class Mode { SYSTEM, LIGHT, DARK }

    private const val FILE = "visavole_prefs"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_DYNAMIC = "dynamic_color"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): Mode =
        prefs(ctx).getString(KEY_MODE, null)
            ?.let { runCatching { Mode.valueOf(it) }.getOrDefault(Mode.SYSTEM) }
            ?: Mode.SYSTEM

    fun setMode(ctx: Context, mode: Mode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getDynamic(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DYNAMIC, true)

    fun setDynamic(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DYNAMIC, on).apply()
    }
}
