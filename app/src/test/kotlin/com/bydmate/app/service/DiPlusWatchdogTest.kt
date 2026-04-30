package com.bydmate.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiPlusWatchdogTest {

    private val threshold = 5
    private val cooldown = 5L * 60_000L

    @Test
    fun `below threshold — never relaunch`() {
        assertFalse(
            DiPlusWatchdog.shouldRelaunch(
                failuresCount = 4,
                threshold = threshold,
                nowMs = 1_000_000,
                lastRelaunchTs = 0L,
                cooldownMs = cooldown,
            )
        )
    }

    @Test
    fun `at threshold with no prior attempt — relaunch immediately`() {
        assertTrue(
            DiPlusWatchdog.shouldRelaunch(
                failuresCount = 5,
                threshold = threshold,
                nowMs = 1_000_000,
                lastRelaunchTs = 0L,
                cooldownMs = cooldown,
            )
        )
    }

    @Test
    fun `above threshold within cooldown — do not relaunch`() {
        // Last attempt 2 minutes ago, cooldown is 5 minutes.
        assertFalse(
            DiPlusWatchdog.shouldRelaunch(
                failuresCount = 20,
                threshold = threshold,
                nowMs = 1_000_000 + 2L * 60_000L,
                lastRelaunchTs = 1_000_000,
                cooldownMs = cooldown,
            )
        )
    }

    @Test
    fun `above threshold after cooldown — relaunch again`() {
        // Last attempt 5 minutes + 1 ms ago.
        assertTrue(
            DiPlusWatchdog.shouldRelaunch(
                failuresCount = 50,
                threshold = threshold,
                nowMs = 1_000_000 + cooldown + 1,
                lastRelaunchTs = 1_000_000,
                cooldownMs = cooldown,
            )
        )
    }

    @Test
    fun `exact cooldown elapsed — relaunch (inclusive)`() {
        assertTrue(
            DiPlusWatchdog.shouldRelaunch(
                failuresCount = 50,
                threshold = threshold,
                nowMs = 1_000_000 + cooldown,
                lastRelaunchTs = 1_000_000,
                cooldownMs = cooldown,
            )
        )
    }
}
