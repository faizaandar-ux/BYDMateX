package com.bydmate.app.service

object DiPlusWatchdog {
    /**
     * True when polling has hit the failure threshold and the cool-down since
     * the last relaunch attempt has elapsed. lastRelaunchTs == 0 means "never
     * tried in this outage" — fire immediately on first eligible cycle.
     */
    fun shouldRelaunch(
        failuresCount: Int,
        threshold: Int,
        nowMs: Long,
        lastRelaunchTs: Long,
        cooldownMs: Long,
    ): Boolean {
        if (failuresCount < threshold) return false
        if (lastRelaunchTs == 0L) return true
        return (nowMs - lastRelaunchTs) >= cooldownMs
    }
}
