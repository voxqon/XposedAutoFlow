package com.github.voxqon.xposed.autoflow.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleJsonTest {

    @Test
    fun systemAlarmActionSurvivesSerializationWithItsDelay() {
        val action = Action(ActionType.SYSTEM_ALARM, delaySeconds = 15)
        assertEquals(action, RuleJson.actionFromJson(RuleJson.actionToJson(action)))
        assertEquals(ActionType.SYSTEM_ALARM, ActionType.fromCode("system_alarm"))
    }

    @Test
    fun appNamesAndTimeWindowsSurviveSerialization() {
        val conditions = listOf(
            Condition(ConditionType.APP_FOREGROUND, "com.example.camera", appName = "Camera"),
            Condition(ConditionType.TIME_RANGE, "22:00-07:00", "outside"),
        )
        conditions.forEach { assertEquals(it, RuleJson.conditionFromJson(RuleJson.conditionToJson(it))) }
        assertEquals(Condition(ConditionType.APP_LEFT, "com.example.camera"),
            RuleJson.conditionFromJson(JSONObject("""{"type":"app_left","param":"com.example.camera"}""")))
    }

    @Test
    fun appActionNamesSurviveSerializationAndLegacyPackagesRemainReadable() {
        val action = Action(ActionType.OPEN_APP, "com.example.camera", appName = "Camera", delaySeconds = 45L)
        assertEquals(action, RuleJson.actionFromJson(RuleJson.actionToJson(action)))
        val killAction = Action(ActionType.KILL_BACKGROUND_APP, "com.example.camera", appName = "Camera")
        assertEquals(killAction, RuleJson.actionFromJson(RuleJson.actionToJson(killAction)))
        assertEquals(Action(ActionType.CLOSE_APP, "com.example.camera"),
            RuleJson.actionFromJson(JSONObject("""{"type":"close_app","param":"com.example.camera"}""")))
    }

    @Test
    fun removedVpnActionsAreSkippedWhileOtherRuleItemsSurvive() {
        val rule = RuleJson.ruleFromJson(JSONObject(
            """{
                "id":"legacy-vpn",
                "conditions":[{"type":"screen_on"}],
                "actions":[
                    {"type":"vpn","param":"on","param2":"com.example.vpn"},
                    {"type":"wifi","param":"off","delaySeconds":3}
                ],
                "exitConditions":[{"type":"screen_off"}],
                "restoreActions":[
                    {"type":"vpn","param":"off","param2":"com.example.vpn"},
                    {"type":"bluetooth","param":"on"}
                ]
            }""",
        ))!!

        assertNull(ActionType.fromCode("vpn"))
        assertNull(RuleJson.actionFromJson(JSONObject("""{"type":"vpn","param":"toggle"}""")))
        assertEquals(listOf(Condition(ConditionType.SCREEN_ON)), rule.conditions)
        assertEquals(listOf(Action(ActionType.WIFI, "off", delaySeconds = 3)), rule.actions)
        assertEquals(listOf(Condition(ConditionType.SCREEN_OFF)), rule.exitConditions)
        assertEquals(listOf(Action(ActionType.BLUETOOTH, "on")), rule.restoreActions)
        assertEquals(rule, RuleJson.ruleFromJson(RuleJson.ruleToJson(rule)))
    }

    @Test
    fun legacyRulesKeepAllConditionsAndNoCooldown() {
        val legacy = JSONObject(
            """{"id":"old","name":"legacy","enabled":true,"conditions":[],"actions":[],"exitConditions":[],"restoreActions":[],"createdAt":1,"updatedAt":2}"""
        )

        val rule = RuleJson.ruleFromJson(legacy)

        assertNotNull(rule)
        assertEquals(TriggerMode.ALL, rule?.triggerMode)
        assertEquals(0L, rule?.cooldownSeconds)
        assertEquals(ALL_DAYS_MASK, rule?.allowedDaysMask)
    }

    @Test
    fun optionsSurviveSerializationAndCooldownIsBounded() {
        val rule = Rule(
            id = "new",
            name = "options",
            enabled = true,
            conditions = emptyList(),
            actions = emptyList(),
            exitConditions = emptyList(),
            restoreActions = emptyList(),
            createdAt = 1L,
            updatedAt = 2L,
            triggerMode = TriggerMode.ANY,
            cooldownSeconds = MAX_RULE_COOLDOWN_SECONDS + 100L,
            allowedDaysMask = 0b0101010,
            scheduleMode = ScheduleMode.CUSTOM_DAYS,
        )

        val decoded = RuleJson.ruleFromJson(RuleJson.ruleToJson(rule))

        assertEquals(TriggerMode.ANY, decoded?.triggerMode)
        assertEquals(MAX_RULE_COOLDOWN_SECONDS, decoded?.cooldownSeconds)
        assertEquals(0b0101010, decoded?.allowedDaysMask)
        assertEquals(ScheduleMode.CUSTOM_DAYS, decoded?.scheduleMode)
    }

    @Test
    fun legacyRulesInferScheduleModeFromAllowedDaysMask() {
        val legacy = RuleJson.ruleFromJson(JSONObject(
            """{"id":"old","name":"legacy","enabled":true,"conditions":[],"actions":[],"exitConditions":[],"restoreActions":[],"createdAt":1,"updatedAt":2,"allowedDaysMask":31}"""
        ))
        assertEquals(ScheduleMode.WEEKDAYS, legacy?.scheduleMode)
        val weekend = RuleJson.ruleFromJson(JSONObject(
            """{"id":"old2","name":"legacy","enabled":true,"conditions":[],"actions":[],"exitConditions":[],"restoreActions":[],"createdAt":1,"updatedAt":2,"allowedDaysMask":96}"""
        ))
        assertEquals(ScheduleMode.WEEKEND, weekend?.scheduleMode)
    }

    @Test
    fun unknownTriggerModeFallsBackToCompatibleDefault() {
        val json = JSONObject(
            """{"id":"future","name":"future","triggerMode":"unsupported","cooldownSeconds":-4}"""
        )

        val rule = RuleJson.ruleFromJson(json)

        assertEquals(TriggerMode.ALL, rule?.triggerMode)
        assertEquals(0L, rule?.cooldownSeconds)
    }

    @Test
    fun geofenceConditionSurvivesRuleSerialization() {
        val rule = Rule(
            id = "geo",
            name = "Home",
            enabled = true,
            conditions = listOf(Condition(ConditionType.GEOFENCE, "31.23,121.47,200", "enter", "Home")),
            actions = emptyList(),
            exitConditions = emptyList(),
            restoreActions = emptyList(),
            createdAt = 1L,
            updatedAt = 2L,
        )

        val decoded = RuleJson.ruleFromJson(RuleJson.ruleToJson(rule))

        assertEquals(rule.conditions, decoded?.conditions)
    }

    @Test
    fun legacyGeofenceWithoutPlaceNameKeepsItsCoordinatesAndDirection() {
        val condition = RuleJson.conditionFromJson(JSONObject(
            """{"type":"geofence","param":"31.23,121.47,200","param2":"exit"}""",
        ))

        assertEquals(Condition(ConditionType.GEOFENCE, "31.23,121.47,200", "exit"), condition)
    }

    @Test
    fun namedExitGeofenceSurvivesRuleSerialization() {
        val rule = Rule(
            id = "geo-exit",
            name = "Office",
            enabled = true,
            conditions = listOf(Condition(ConditionType.SCREEN_ON)),
            actions = emptyList(),
            exitConditions = listOf(Condition(ConditionType.GEOFENCE, "31,121,500", "exit", "Office")),
            restoreActions = emptyList(),
            createdAt = 1L,
            updatedAt = 2L,
        )

        val decoded = RuleJson.ruleFromJson(RuleJson.ruleToJson(rule))

        assertEquals(rule.exitConditions, decoded?.exitConditions)
    }

    @Test
    fun malformedEntriesAreSkippedWithoutDroppingDuplicateRecordsFromUserData() {
        val rules = RuleJson.rulesFromJson(
            """[{"id":"same","name":"first","conditions":[7,{"type":"wifi_on","param":"on"}]},{"id":"same","name":"duplicate"},{"id":"","name":"invalid"}]"""
        )

        assertEquals(2, rules.size)
        assertEquals("first", rules.first().name)
        assertEquals(1, rules.first().conditions.size)
        assertEquals("duplicate", rules.last().name)
    }
}
