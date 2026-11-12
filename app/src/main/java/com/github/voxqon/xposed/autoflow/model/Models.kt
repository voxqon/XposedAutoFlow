package com.github.voxqon.xposed.autoflow.model

/**
 * 条件类型枚举。code 用于 JSON 持久化，label 用于 UI 展示。
 * paramHint：UI 录入参数时的提示；null 表示该条件无需参数。
 * 事件归属进程：
 *  - 应用生命周期类条件由 android(system_server) 进程监听（权威来源）；
 *    com.miui.home 进程的启动器 Hook 作为冗余信号通过事件桥转发。
 *  - 亮/熄屏由 android(system_server) PowerManagerService Hook 产生；
 *    com.android.systemui 进程只转发锁屏状态事件。
 *  - Wi-Fi/蓝牙/定位/勿扰/方向锁定/充电/电量由 system_server 内的
 *    运行时 ContentObserver / BroadcastReceiver / NMS Hook 产生。
 *  - 定时与持续时长由 system_server 引擎内的调度 Tick 产生。
 */
enum class ConditionType(val code: String, val label: String, val paramHint: String?) {
    APP_FOREGROUND("app_fg", "应用进入前台（启动）", "包名，如 com.android.camera"),
    APP_LEFT("app_left", "离开应用（退到后台/桌面）", "包名"),
    APP_COLD_START("app_cold", "冷启动应用（进程创建）", "包名"),
    APP_KILLED("app_killed", "应用被杀死（进程销毁）", "包名"),
    SCREEN_ON("screen_on", "亮屏", null),
    SCREEN_OFF("screen_off", "息屏", null),
    SCREEN_ON_FOR("screen_on_for", "亮屏持续时长（分钟）", "分钟数，如 30"),
    SCREEN_OFF_FOR("screen_off_for", "息屏持续时长（分钟）", "分钟数，如 480"),
    LOCATION_ON("location_on", "定位开关", "on / off / any"),
    WIFI_ON("wifi_on", "Wi-Fi 开关", "on / off / any"),
    BT_ON("bt_on", "蓝牙开关", "on / off / any"),
    ROTATION_LOCKED("rotation_locked", "方向锁定", "locked / unlocked / any"),
    DND_ON("dnd_on", "勿扰模式", "on / off / any"),
    CHARGER_CONNECTED("charger_on", "充电器连接", null),
    CHARGER_DISCONNECTED("charger_off", "充电器断开", null),
    BATTERY_BELOW("battery_below", "电量低于阈值（%）", "0-100"),
    BATTERY_ABOVE("battery_above", "电量高于阈值（%）", "0-100"),
    WIFI_SSID("wifi_ssid", "指定 Wi-Fi 连接状态", "Wi-Fi 名称（SSID）"),
    BT_DEVICE("bt_device", "指定蓝牙设备连接状态", "蓝牙设备名称或 MAC 地址"),
    TIMER("timer", "定时触发", "选择时间和日期"),
    GEOFENCE("geofence", "地理围栏（进入/离开/在围栏内外）", "选择地点和范围"),
    TIME_RANGE("time_range", "时间范围", "开始时间、结束时间和范围内/外"),
    DEVICE_LOCKED("device_locked", "设备已锁屏", null),
    DEVICE_UNLOCKED("device_unlocked", "设备已解锁", null),
    POWER_SAVE_ON("power_save_on", "省电模式", "on / off / any"),
    NIGHT_MODE("night_mode", "夜间模式", "on / off / any"),
    HEADSET_CONNECTED("headset_connected", "有线耳机已连接", null),
    HEADSET_DISCONNECTED("headset_disconnected", "有线耳机已断开", null),
    AIRPLANE_MODE("airplane_mode", "飞行模式", "on / off / any"),
    HOTSPOT_ON("hotspot_on", "个人热点开关", "on / off / any"),
    WIFI_CONNECTED("wifi_connected", "已连接任意 Wi-Fi", null),
    WIFI_DISCONNECTED("wifi_disconnected", "未连接 Wi-Fi", null),
    BT_CONNECTED("bt_connected", "已连接任意蓝牙设备", null),
    BT_DISCONNECTED("bt_disconnected", "未连接蓝牙设备", null),
    APP_NOT_FOREGROUND("app_not_fg", "指定应用不在前台", "包名"),
    GROUP("group", "条件组（任一满足）", null);

    val isAppCondition: Boolean
        get() = this == APP_FOREGROUND || this == APP_LEFT || this == APP_COLD_START ||
            this == APP_KILLED || this == APP_NOT_FOREGROUND

    val isSwitchCondition: Boolean
        get() = this == LOCATION_ON || this == WIFI_ON || this == BT_ON ||
            this == ROTATION_LOCKED || this == DND_ON || this == POWER_SAVE_ON ||
            this == NIGHT_MODE || this == AIRPLANE_MODE || this == HOTSPOT_ON

    companion object {
        fun fromCode(code: String): ConditionType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 动作类型枚举。
 * SHELL 为高风险动作：模块 App 内有全局开关（auto_task_config.allow_shell），
 * 默认 false，未开启时 ActionExecutor 直接拒绝执行并记录日志。
 */
enum class ActionType(val code: String, val label: String, val paramHint: String?, val highRisk: Boolean = false) {
    OPEN_APP("open_app", "打开应用", "包名"),
    KILL_BACKGROUND_APP("kill_background_app", "结束后台进程", "包名"),
    CLOSE_APP("close_app", "强制停止应用", "包名"),
    LOCATION("location", "开启/关闭定位", "on / off / toggle"),
    WIFI("wifi", "开启/关闭 Wi-Fi", "on / off / toggle"),
    HOTSPOT("hotspot", "开启/关闭个人热点", "on / off / toggle"),
    BLUETOOTH("bluetooth", "开启/关闭蓝牙", "on / off / toggle"),
    ROTATION_LOCK("rotation_lock", "开启/关闭方向锁定", "lock / unlock / toggle"),
    DND("dnd", "开启/关闭勿扰模式", "on / off / toggle"),
    INCOGNITO("incognito", "开启/关闭隐身模式", "on / off / toggle（系统私有，候选实现）"),
    SHELL("shell", "执行 Shell 命令", "命令，如 settings get secure location_mode", highRisk = true),
    BROADCAST("broadcast", "发送广播", "action，可加 \"k=v,k2=v2\"（第二参数）"),
    START_SERVICE("start_service", "启动服务", "包名/服务类全名"),
    WIFI_CONNECTION("wifi_connection", "连接/断开指定 Wi-Fi", "Wi-Fi 名称（SSID）"),
    BLUETOOTH_CONNECTION("bluetooth_connection", "连接/断开指定蓝牙设备", "蓝牙设备名称或 MAC 地址"),
    PLAY_SOUND("play_sound", "播放声音", "选择声音和播放时长"),
    VIBRATE("vibrate", "振动", "持续毫秒数，1-10000"),
    MEDIA_VOLUME("media_volume", "设置媒体音量", "音量百分比，0-100"),
    SYSTEM_ALARM("system_alarm", "调用系统闹钟（一次性）", null);

    val isSwitchAction: Boolean
        get() = this == LOCATION || this == WIFI || this == BLUETOOTH ||
            this == HOTSPOT || this == ROTATION_LOCK || this == DND || this == INCOGNITO

    val isAppAction: Boolean
        get() = this == OPEN_APP || this == KILL_BACKGROUND_APP || this == CLOSE_APP

    companion object {
        fun fromCode(code: String): ActionType? = entries.firstOrNull { it.code == code }
    }
}

/** How trigger conditions are combined. Existing rules default to ALL for compatibility. */
enum class TriggerMode(val code: String, val label: String) {
    ALL("all", "全部满足（与）"),
    ANY("any", "任一满足（或）");

    companion object {
        fun fromCode(code: String): TriggerMode? = entries.firstOrNull { it.code == code }
    }
}

/** 规则触发日期模式：每天 / 周一到周五 / 周末 / 法定工作日 / 法定休息日 / 自定义星期。 */
enum class ScheduleMode(val code: String, val label: String) {
    EVERYDAY("everyday", "每天"),
    WEEKDAYS("weekdays", "周一到周五"),
    WEEKEND("weekend", "周末（周六、周日）"),
    WORKDAY("workday", "工作日（法定）"),
    HOLIDAY("holiday", "休息日（法定节假日）"),
    CUSTOM_DAYS("custom", "自定义星期");

    companion object {
        fun fromCode(code: String): ScheduleMode? = entries.firstOrNull { it.code == code }
    }
}

/** 单个条件。param/param2 含义见 ConditionType.paramHint。 */
data class Condition(
    val type: ConditionType,
    val param: String? = null,
    val param2: String? = null,
    val placeName: String? = null,
    val appName: String? = null,
    val children: List<Condition> = emptyList(),
) {
    /** Groups occupy one condition slot; their children are ordinary conditions. */
    fun leaves(): List<Condition> = if (type == ConditionType.GROUP) children else listOf(this)
}

/** 单个动作。delaySeconds 是动作开始前等待的时间。 */
data class Action(
    val type: ActionType,
    val param: String? = null,
    val param2: String? = null,
    val appName: String? = null,
    val delaySeconds: Long = 0L,
)

/**
 * 规则结构体：
 * - 时间范围始终限制触发；其余 conditions 按 triggerMode 组合，至少一个必须由本次事件新满足；
 * - exitConditions 之间为“或”关系：任一满足即执行 restoreActions 并回到未触发状态。
 * - cooldownSeconds 限制规则退出后再次触发的最短间隔，0 表示关闭；
 * - allowedDaysMask 使用周一至周日的低 7 位限制触发日期，旧规则默认每天。
 */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val conditions: List<Condition>,
    val actions: List<Action>,
    val exitConditions: List<Condition>,
    val restoreActions: List<Action>,
    val createdAt: Long,
    val updatedAt: Long,
    val triggerMode: TriggerMode = TriggerMode.ALL,
    val cooldownSeconds: Long = 0L,
    val allowedDaysMask: Int = ALL_DAYS_MASK,
    val scheduleMode: ScheduleMode = ScheduleMode.EVERYDAY,
)

const val MAX_RULE_COOLDOWN_SECONDS = 604_800L
const val MAX_ACTION_DELAY_SECONDS = 604_800L
const val ALL_DAYS_MASK = 0b1111111
