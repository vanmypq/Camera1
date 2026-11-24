package com.vanmypq.cameralophoc

import android.content.Context

object Cfg {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun front(c: Context) = p(c).getBoolean("front", false)
    fun setFront(c: Context, v: Boolean) = p(c).edit().putBoolean("front", v).apply()

    /** Số lần chạm liên tiếp để chụp: mặc định 3 (có thể chọn 2) */
    fun taps(c: Context) = p(c).getInt("taps", 3)
    fun setTaps(c: Context, v: Int) = p(c).edit().putInt("taps", v).apply()

    fun sound(c: Context) = p(c).getBoolean("sound", true)
    fun setSound(c: Context, v: Boolean) = p(c).edit().putBoolean("sound", v).apply()

    fun vibrate(c: Context) = p(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = p(c).edit().putBoolean("vibrate", v).apply()
}
