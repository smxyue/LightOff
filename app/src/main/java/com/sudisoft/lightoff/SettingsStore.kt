package com.sudisoft.lightoff

import android.content.Context

/** 游戏设置持久化 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("lightsout_settings", Context.MODE_PRIVATE)

    var n: Int
        get() = prefs.getInt("n", 8).coerceIn(2, MAX_N)
        set(value) = prefs.edit().putInt("n", value.coerceIn(2, MAX_N)).apply()

    var ratio: Int
        get() = prefs.getInt("ratio", 10).coerceIn(1, 60)
        set(value) = prefs.edit().putInt("ratio", value.coerceIn(1, 60)).apply()

    /** 把「初始亮灯比例」当目标（无解时自动微调） */
    var countTarget: Boolean
        get() = prefs.getBoolean("countTarget", true)
        set(value) = prefs.edit().putBoolean("countTarget", value).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) = prefs.edit().putBoolean("sound", value).apply()

    /** 自动求解每步间隔（毫秒，40–1000） */
    var autoStepMs: Int
        get() = prefs.getInt("autoStepMs", 220).coerceIn(40, 1000)
        set(value) = prefs.edit().putInt("autoStepMs", value.coerceIn(40, 1000)).apply()
}
