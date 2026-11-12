package com.github.voxqon.xposed.autoflow.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToggleResolverTest {
    @Test
    fun explicitCommandsDoNotDependOnReadingTheCurrentState() {
        assertEquals(true, ToggleResolver.resolve("ON") { error("State read should be skipped") })
        assertEquals(false, ToggleResolver.resolve("off") { error("State read should be skipped") })
    }

    @Test
    fun toggleAndDefaultInvertTheActualState() {
        assertEquals(false, ToggleResolver.resolve("toggle") { true })
        assertEquals(true, ToggleResolver.resolve("toggle") { false })
        assertEquals(false, ToggleResolver.resolve(null) { true })
    }

    @Test
    fun unknownStateOrInvalidCommandCannotBlindlyToggle() {
        assertNull(ToggleResolver.resolve("toggle") { null })
        assertNull(ToggleResolver.resolve("typo") { error("Invalid command must not read state") })
    }
}
