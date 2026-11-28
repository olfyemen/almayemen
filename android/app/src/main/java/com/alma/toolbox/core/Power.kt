package com.alma.toolbox.core

import android.content.Context

/** Battery-saving switch. Live screens refresh less often when it is on. */
object Power {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("power", 0)
    fun saver(ctx: Context) = prefs(ctx).getBoolean("saver", false)
    fun setSaver(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("saver", v).apply()
    /** refresh interval in ms: [normal] or, in saver mode, [saver] */
    fun every(ctx: Context, normal: Long, saver: Long): Long = if (saver(ctx)) saver else normal
}
