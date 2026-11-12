package com.github.voxqon.xposed.autoflow.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerStateResolverTest {
    private class ModernPower(var mWakefulnessRaw: Int)
    private class LegacyPower(var mWakefulness: Int)
    private class BothFields(val mWakefulnessRaw: Int, val mWakefulness: Int)

    @Test
    fun awakeAndDreamingRemainInteractive() {
        assertEquals(true, PowerStateResolver.isInteractive(ModernPower(1)))
        assertEquals(true, PowerStateResolver.isInteractive(ModernPower(2)))
    }

    @Test
    fun asleepAndDozingAreNoninteractive() {
        assertEquals(false, PowerStateResolver.isInteractive(ModernPower(0)))
        assertEquals(false, PowerStateResolver.isInteractive(ModernPower(3)))
    }

    @Test
    fun legacyFieldsWorkAndModernFieldsTakePriority() {
        assertEquals(true, PowerStateResolver.isInteractive(LegacyPower(1)))
        assertEquals(false, PowerStateResolver.isInteractive(LegacyPower(0)))
        assertEquals(false, PowerStateResolver.isInteractive(BothFields(3, 1)))
    }

    @Test
    fun unknownStatesDoNotInventAScreenTransition() {
        assertNull(PowerStateResolver.isInteractive(ModernPower(-1)))
        assertNull(PowerStateResolver.isInteractive(Any()))
        assertNull(PowerStateResolver.isInteractive(null))
    }
}
