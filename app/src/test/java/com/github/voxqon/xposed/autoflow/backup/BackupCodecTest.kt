package com.github.voxqon.xposed.autoflow.backup

import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.MAX_ACTION_DELAY_SECONDS
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {
    private val rule = Rule("test", "Camera", true,
        listOf(Condition(ConditionType.APP_FOREGROUND, "com.example.camera", appName = "Camera")),
        listOf(Action(ActionType.WIFI, "off", delaySeconds = 15)), listOf(Condition(ConditionType.SCREEN_OFF)),
        emptyList(), 1, 2, allowedDaysMask = 0b0011111)

    @Test
    fun roundTripPreservesRulesAndRetentionWithoutCredentialsOrPrivilegedFlags() {
        val data = BackupData(listOf(rule), 500)
        val json = BackupCodec.encode(data)
        assertEquals(data, BackupCodec.decode(json))
        assertFalse(json.contains("password"))
        assertFalse(json.contains("allow_shell"))
        assertFalse(json.contains("credentials"))
    }

    @Test
    fun rejectUnknownVersionsDuplicateRulesAndUnrecognizedActionsInsteadOfPartialRestore() {
        fun json() = JSONObject(BackupCodec.encode(BackupData(listOf(rule), 200)))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(json().put("version", 99).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val value = json()
            value.getJSONArray("rules").put(value.getJSONArray("rules").getJSONObject(0))
            BackupCodec.decode(value.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val value = json()
            value.getJSONArray("rules").getJSONObject(0).getJSONArray("actions")
                .getJSONObject(0).put("type", "future-unknown")
            BackupCodec.decode(value.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(json().put("logLimit", -1).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val value = json()
            value.getJSONArray("rules").getJSONObject(0).put("allowedDaysMask", 0)
            BackupCodec.decode(value.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val value = json()
            value.getJSONArray("rules").getJSONObject(0).getJSONArray("actions")
                .getJSONObject(0).put("delaySeconds", MAX_ACTION_DELAY_SECONDS + 1)
            BackupCodec.decode(value.toString())
        }
    }

    @Test
    fun groupsRoundTripAndUnknownChildrenCannotBePartiallyRestored() {
        val grouped = rule.copy(conditions = listOf(Condition(ConditionType.GROUP, children = rule.conditions)))
        val data = BackupData(listOf(grouped), 200)
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
        val json = JSONObject(BackupCodec.encode(data))
        json.getJSONArray("rules").getJSONObject(0).getJSONArray("conditions").getJSONObject(0)
            .getJSONArray("children").put(JSONObject().put("type", "future"))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.toString()) }
    }

    @Test
    fun explicitlyEmptyRulesAreAValidBackup() {
        val data = BackupData(emptyList(), 0)
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
    }

    @Test
    fun malformedNumericFieldsAreRejectedInsteadOfBeingTruncatedOrDefaulted() {
        fun json() = JSONObject(BackupCodec.encode(BackupData(listOf(rule), 200)))
        for (value in listOf("200", 2.5, JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupCodec.decode(json().put("logLimit", value).toString())
            }
        }
        for (key in listOf("cooldownSeconds", "allowedDaysMask")) {
            val value = json()
            value.getJSONArray("rules").getJSONObject(0).put(key, "broken")
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(value.toString()) }
        }
        val value = json()
        value.getJSONArray("rules").getJSONObject(0).getJSONArray("actions")
            .getJSONObject(0).put("delaySeconds", 1.9)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(value.toString()) }
    }
}
