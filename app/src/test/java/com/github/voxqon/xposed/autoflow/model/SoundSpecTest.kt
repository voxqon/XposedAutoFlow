package com.github.voxqon.xposed.autoflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundSpecTest {
    @Test fun validatesSoundKindsDurationBoundsAndOldDefaultDuration() {
        assertEquals(SoundSpec(SoundKind.NOTIFICATION, 5), SoundSpec.parse("notification", null))
        assertEquals(SoundSpec(SoundKind.ALARM, 30), SoundSpec.parse("alarm", "30"))
        listOf("0", "-1", "31", "1.5", "NaN").forEach { assertNull(SoundSpec.parse("alarm", it)) }
        assertNull(SoundSpec.parse("content://external/arbitrary", "5"))
    }

    @Test fun newConditionsAndActionsSurviveRuleJsonRoundTrip() {
        val rule = Rule("new", "New", true, listOf(
            Condition(ConditionType.GEOFENCE, "31,121,200", "inside"),
            Condition(ConditionType.NIGHT_MODE, "on"), Condition(ConditionType.HEADSET_CONNECTED)),
            listOf(Action(ActionType.PLAY_SOUND, "alarm", "10"), Action(ActionType.VIBRATE, "500"),
                Action(ActionType.MEDIA_VOLUME, "40")),
            listOf(Condition(ConditionType.GEOFENCE, "31,121,200", "outside")),
            emptyList(), 0, 0)
        assertEquals(listOf(rule), RuleJson.rulesFromJson(RuleJson.rulesToJson(listOf(rule))))
    }
}
