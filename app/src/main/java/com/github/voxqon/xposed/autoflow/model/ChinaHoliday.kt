package com.github.voxqon.xposed.autoflow.model

import android.content.Context
import java.time.LocalDate
import java.util.zip.ZipFile

/**
 * China holiday data from https://github.com/NateScarlet/holiday-cn,
 * packaged under assets/holiday_cn/{year}.json. Only days affected by
 * official adjustment are listed: isOffDay=true means statutory rest day,
 * isOffDay=false means make-up workday.
 *
 * Data is read from app assets when available (module app process and
 * Robolectric tests). Inside system_server the app assets are not attached
 * to the system context, so the module APK path is used to read the zip
 * entries directly instead.
 */
object ChinaHoliday {
    private val lock = Any()
    private var context: Context? = null
    private var moduleApk: String? = null
    private val cache = HashMap<Int, Map<LocalDate, Boolean>>()
    private var testData: Map<Int, Map<LocalDate, Boolean>>? = null

    fun initialize(ctx: Context) {
        synchronized(lock) {
            context = ctx.applicationContext
        }
    }

    fun setModuleApk(path: String?) {
        synchronized(lock) {
            moduleApk = path
        }
    }

    /** Test seam: supply exact per-year day maps without an Android context. */
    fun setDataForTest(data: Map<Int, Map<LocalDate, Boolean>>?) {
        synchronized(lock) {
            testData = data
            cache.clear()
        }
    }

    fun isWorkday(date: LocalDate): Boolean = !isOffDay(date)

    private fun isOffDay(date: LocalDate): Boolean {
        val calendarOff = date.dayOfWeek.value >= 6
        return lookup(date) ?: calendarOff
    }

    private fun lookup(date: LocalDate): Boolean? {
        synchronized(lock) {
            // Cache both loaded maps and empty results so missing dates and
            // missing years never trigger redundant file IO on subsequent calls.
            val yearData = cache.getOrPut(date.year) {
                testData?.get(date.year) ?: loadYear(date.year) ?: emptyMap()
            }
            return yearData[date]
        }
    }

    private fun loadYear(year: Int): Map<LocalDate, Boolean>? {
        context?.let { ctx -> runCatching { readAssets(ctx, year) }.getOrNull()?.let { return it } }
        moduleApk?.let { path -> runCatching { readZip(path, year) }.getOrNull()?.let { return it } }
        return null
    }

    private fun readAssets(context: Context, year: Int): Map<LocalDate, Boolean> {
        val json = context.assets.open("holiday_cn/$year.json").bufferedReader().use { it.readText() }
        return parse(json)
    }

    private fun readZip(path: String, year: Int): Map<LocalDate, Boolean> {
        ZipFile(path).use { zip ->
            val entry = zip.getEntry("assets/holiday_cn/$year.json") ?: error("holiday data missing")
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            return parse(json)
        }
    }

    private fun parse(json: String): Map<LocalDate, Boolean> {
        val root = org.json.JSONObject(json)
        val array = root.getJSONArray("days")
        val result = HashMap<LocalDate, Boolean>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            result[LocalDate.parse(item.getString("date"))] = item.getBoolean("isOffDay")
        }
        return result
    }
}
