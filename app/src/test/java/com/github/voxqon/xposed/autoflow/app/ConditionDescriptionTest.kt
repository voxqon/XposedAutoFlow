package com.github.voxqon.xposed.autoflow.app

import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ConditionDescriptionTest {
    @Test
    fun notConnectedConditionsShowTheirTargetAndState() {
        assertEquals("未连接 Wi-Fi (Home)",
            Condition(ConditionType.WIFI_SSID, "Home", "not_connected").description())
        assertEquals("未连接 蓝牙 (AA:BB:CC:DD:EE:FF)",
            Condition(ConditionType.BT_DEVICE, "AA:BB:CC:DD:EE:FF", "NOT_CONNECTED").description())
    }

    @Test
    fun appActionsShowBothTheSelectedNameAndThePackage() {
        assertEquals("打开应用：Camera (com.example.camera)",
            Action(ActionType.OPEN_APP, "com.example.camera", appName = "Camera").description())
        assertEquals("结束后台进程：Camera (com.example.camera)",
            Action(ActionType.KILL_BACKGROUND_APP, "com.example.camera", appName = "Camera").description())
        assertEquals("强制停止应用：com.example.camera",
            Action(ActionType.CLOSE_APP, "com.example.camera").description())
    }

    @Test
    fun delayedActionsShowTheirWaitTime() {
        assertEquals("Wi-Fi：开启（延迟 30 秒）",
            Action(ActionType.WIFI, "on", delaySeconds = 30).description())
    }

    @Test
    fun appsAndOvernightWindowsHaveReadableDescriptions() {
        assertEquals("应用进入前台（启动）：Camera (com.example.camera)",
            Condition(ConditionType.APP_FOREGROUND, "com.example.camera", appName = "Camera").description())
        assertEquals("时间范围 22:00 - 次日 07:00（范围外）",
            Condition(ConditionType.TIME_RANGE, "22:00-07:00", "outside").description())
    }

    @Test
    fun namedFenceShowsPlaceRadiusAndReadableDirection() {
        val condition = Condition(ConditionType.GEOFENCE, "31,121,200", "exit", "Home")

        assertEquals("离开 Home（半径 200 米）", condition.description())
    }

    @Test
    fun legacyFenceShowsItsCoordinatesAndDefaultsToEitherDirection() {
        val condition = Condition(ConditionType.GEOFENCE, "-33.8688,151.2093,750")

        assertEquals("进入或离开 -33.868800, 151.209300（半径 750 米）", condition.description())
    }
}
