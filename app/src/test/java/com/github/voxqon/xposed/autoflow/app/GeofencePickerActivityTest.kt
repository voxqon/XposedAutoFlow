package com.github.voxqon.xposed.autoflow.app

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.GeofenceSpec
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.util.GeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.util.MapTileIndex
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowPopupMenu
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class GeofencePickerActivityTest {
    @Test
    fun insideAndOutsideModesSurviveRecreationAndConfirmation() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java,
            Intent(app, GeofencePickerActivity::class.java)
                .putExtra(GeofencePickerActivity.EXTRA_FENCE, "31,121,200")
                .putExtra(GeofencePickerActivity.EXTRA_DIRECTION, "inside")).setup()
        val activity = controller.get()
        activity.findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        val group = activity.findViewById<RadioGroup>(R.id.radioGeofenceDirection)
        assertEquals(R.id.radioGeofenceInside, group.checkedRadioButtonId)
        group.check(R.id.radioGeofenceOutside)
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop().destroy()
        val restored = Robolectric.buildActivity(GeofencePickerActivity::class.java)
            .create(saved).start().restoreInstanceState(saved).resume()
        restored.get().findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        assertEquals(R.id.radioGeofenceOutside,
            restored.get().findViewById<RadioGroup>(R.id.radioGeofenceDirection).checkedRadioButtonId)
        restored.get().findViewById<View>(R.id.btnGeofenceSave).performClick()
        assertEquals("outside", shadowOf(restored.get()).resultIntent.getStringExtra(GeofencePickerActivity.EXTRA_DIRECTION))
        restored.pause().stop().destroy()
    }

    @Test
    fun radiusAndCameraAnimateTogetherAndRapidChangesFinishAtTheLatestValue() {
        val intent = Intent(RuntimeEnvironment.getApplication(), GeofencePickerActivity::class.java)
            .putExtra(GeofencePickerActivity.EXTRA_FENCE, "31,121,200")
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java, intent).setup()
        val activity = controller.get()
        val map = activity.findViewById<MapView>(R.id.geofenceMap)
        map.setUseDataConnection(false)
        map.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY))
        map.layout(0, 0, 360, 280)
        shadowOf(Looper.getMainLooper()).idle()
        val polygon = map.overlays.filterIsInstance<Polygon>().single()
        fun radius(): Double = GeoPoint(31.0, 121.0).distanceToAsDouble(polygon.actualPoints.first())
        val initialZoom = map.zoomLevelDouble

        activity.findViewById<View>(R.id.chipRadius1000).performClick()
        assertEquals(200.0, radius(), 2.0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(140))
        assertTrue(radius() > 200.0 && radius() < 1000.0)
        assertTrue(map.zoomLevelDouble < initialZoom)

        activity.findViewById<View>(R.id.chipRadius100).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(100.0, radius(), 2.0)
        activity.findViewById<View>(R.id.btnGeofenceSave).performClick()
        assertEquals(100.0, GeofenceSpec.parse(
            shadowOf(activity).resultIntent.getStringExtra(GeofencePickerActivity.EXTRA_FENCE))!!.radiusMeters, 0.0)
        controller.pause().stop().destroy()
    }

    @Test
    fun mapSourcesUseIdentifiableRequestsSeparateCachesAndHttps() {
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        assertTrue(Configuration.getInstance().userAgentValue.startsWith("AutoTaskLite/"))
        val sources = GeofenceMapSource.entries.map { it.tileSource() }
        assertEquals(sources.size, sources.map { it.name() }.distinct().size)
        sources.forEach { source ->
            assertTrue(source.getTileURLString(MapTileIndex.getTileIndex(4, 12, 6)).startsWith("https://"))
            assertFalse(source.tileSourcePolicy.normalizesUserAgent())
            assertFalse(source.tileSourcePolicy.acceptsBulkDownload())
            assertFalse(source.tileSourcePolicy.acceptsPreventive())
            assertEquals(2, source.tileSourcePolicy.maxConcurrent)
            assertTrue(source.tileSourcePolicy.acceptsUserAgent(Configuration.getInstance().userAgentValue))
        }
        controller.pause().stop().destroy()
    }

    @Test
    fun repeatedTileFailuresSwitchOnceAndIgnoreLateResponsesFromTheOldSource() {
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java).setup()
        val activity = controller.get()
        val map = activity.findViewById<MapView>(R.id.geofenceMap)
        map.setUseDataConnection(false)
        shadowOf(activity.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true),
        )
        val original = map.tileProvider
        val oldHandlers = original.tileRequestCompleteHandlers.filterNotNull().toList()

        repeat(4) { oldHandlers.forEach { it.sendEmptyMessage(MapTileProviderBase.MAPTILE_FAIL_ID) } }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(GeofenceMapSource.STANDARD.tileSource().name(), map.tileProvider.tileSource.name())
        assertEquals(19.0, map.maxZoomLevel, 0.0)
        assertTrue(activity.findViewById<android.widget.TextView>(R.id.textMapAttribution).text.contains("OpenStreetMap"))
        val replacement = map.tileProvider
        // A response that was already queued must not reset the replacement's state.
        oldHandlers.forEach { it.sendEmptyMessage(MapTileProviderBase.MAPTILE_SUCCESS_ID) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layoutMapStatus).visibility)

        repeat(4) {
            replacement.tileRequestCompleteHandlers.filterNotNull().forEach { it.sendEmptyMessage(MapTileProviderBase.MAPTILE_FAIL_ID) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(GeofenceMapSource.TOPOGRAPHIC.tileSource().name(), map.tileProvider.tileSource.name())
        val finalProvider = map.tileProvider
        repeat(4) {
            finalProvider.tileRequestCompleteHandlers.filterNotNull().forEach { it.sendEmptyMessage(MapTileProviderBase.MAPTILE_FAIL_ID) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(finalProvider === map.tileProvider)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnMapRetry).visibility)

        activity.findViewById<View>(R.id.btnMapRetry).performClick()
        assertFalse(finalProvider === map.tileProvider)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutMapStatus).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun stalledDownloadsTimeOutAndTryADifferentProvider() {
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java).setup()
        val activity = controller.get()
        val map = activity.findViewById<MapView>(R.id.geofenceMap)
        map.setUseDataConnection(false)
        shadowOf(activity.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        assertEquals(GeofenceMapSource.HUMANITARIAN.tileSource().name(), map.tileProvider.tileSource.name())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
        assertEquals(GeofenceMapSource.STANDARD.tileSource().name(), map.tileProvider.tileSource.name())
        controller.pause().stop().destroy()
    }

    @Test
    fun offlineFailuresKeepTheSourceAndManualSwitchKeepsTheFence() {
        val intent = Intent(RuntimeEnvironment.getApplication(), GeofencePickerActivity::class.java)
            .putExtra(GeofencePickerActivity.EXTRA_FENCE, "31.2304,121.4737,500")
            .putExtra(GeofencePickerActivity.EXTRA_DIRECTION, "exit")
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java, intent).setup()
        val activity = controller.get()
        val map = activity.findViewById<MapView>(R.id.geofenceMap)
        map.setUseDataConnection(false)
        shadowOf(activity.getSystemService(ConnectivityManager::class.java)).setDefaultNetworkActive(false)
        val original = map.tileProvider
        repeat(4) {
            original.tileRequestCompleteHandlers.filterNotNull().forEach { it.sendEmptyMessage(MapTileProviderBase.MAPTILE_FAIL_ID) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(original === map.tileProvider)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnMapRetry).visibility)

        activity.findViewById<View>(R.id.btnMapSource).performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        popup.menu.performIdentifierAction(GeofenceMapSource.TOPOGRAPHIC.ordinal, 0)
        popup.dismiss()
        activity.findViewById<View>(R.id.btnGeofenceSave).performClick()
        val result = shadowOf(activity).resultIntent
        assertEquals(GeofenceSpec(31.2304, 121.4737, 500.0), GeofenceSpec.parse(result.getStringExtra(GeofencePickerActivity.EXTRA_FENCE)))
        assertEquals("exit", result.getStringExtra(GeofencePickerActivity.EXTRA_DIRECTION))
        controller.pause().stop().destroy()
    }

    @Test
    fun coordinateValidationKeepsTheDialogOpenAndMarksTheIncorrectField() {
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        activity.findViewById<View>(R.id.btnEnterCoordinates).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val latitude = dialog.findViewById<EditText>(R.id.editLatitude)!!
        val longitude = dialog.findViewById<EditText>(R.id.editLongitude)!!
        latitude.setText("31")
        longitude.setText("181")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertTrue(dialog.isShowing)
        assertNull(dialog.findViewById<TextInputLayout>(R.id.layoutLatitude)!!.error)
        assertNotNull(dialog.findViewById<TextInputLayout>(R.id.layoutLongitude)!!.error)
        longitude.setText("121")
        assertNull(dialog.findViewById<TextInputLayout>(R.id.layoutLongitude)!!.error)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        activity.findViewById<View>(R.id.btnGeofenceSave).performClick()
        assertEquals(
            GeofenceSpec(31.0, 121.0, 200.0),
            GeofenceSpec.parse(shadowOf(activity).resultIntent.getStringExtra(GeofencePickerActivity.EXTRA_FENCE)),
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun newFenceRequiresAnExplicitPlaceSelection() {
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)

        activity.findViewById<View>(R.id.btnGeofenceSave).performClick()

        assertFalse(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
        controller.pause().stop().destroy()
    }

    @Test
    fun editingKeepsPlaceAndUsesTheSelectedRadiusAndDirection() {
        val intent = Intent(RuntimeEnvironment.getApplication(), GeofencePickerActivity::class.java).apply {
            putExtra(GeofencePickerActivity.EXTRA_FENCE, "31.2304,121.4737,200")
            putExtra(GeofencePickerActivity.EXTRA_NAME, "Home")
            putExtra(GeofencePickerActivity.EXTRA_DIRECTION, "enter")
        }
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)

        activity.findViewById<View>(R.id.chipRadius500).performClick()
        activity.findViewById<RadioGroup>(R.id.radioGeofenceDirection).check(R.id.radioGeofenceExit)
        activity.findViewById<View>(R.id.btnGeofenceSave).performClick()

        val result = shadowOf(activity).resultIntent
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(GeofenceSpec(31.2304, 121.4737, 500.0), GeofenceSpec.parse(result.getStringExtra(GeofencePickerActivity.EXTRA_FENCE)))
        assertEquals("Home", result.getStringExtra(GeofencePickerActivity.EXTRA_NAME))
        assertEquals("exit", result.getStringExtra(GeofencePickerActivity.EXTRA_DIRECTION))
        controller.pause().stop().destroy()
    }

    @Test
    fun invalidRadiusDoesNotCloseThePickerOrOverwriteTheFence() {
        val intent = Intent(RuntimeEnvironment.getApplication(), GeofencePickerActivity::class.java)
            .putExtra(GeofencePickerActivity.EXTRA_FENCE, "31,121,200")
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        val radius = activity.findViewById<EditText>(R.id.editGeofenceRadius)

        listOf("", "0", "100001", "NaN").forEach { input ->
            radius.setText(input)
            activity.findViewById<View>(R.id.btnGeofenceSave).performClick()
            assertFalse(activity.isFinishing)
            assertNotNull(activity.findViewById<TextInputLayout>(R.id.layoutGeofenceRadius).error)
        }
        radius.setText("100000")
        activity.findViewById<View>(R.id.btnGeofenceSave).performClick()
        val result = shadowOf(activity).resultIntent
        assertEquals(100000.0, GeofenceSpec.parse(result.getStringExtra(GeofencePickerActivity.EXTRA_FENCE))!!.radiusMeters, 0.0)
        controller.pause().stop().destroy()
    }

    @Test
    fun manuallySelectedCoordinatesSurviveRecreationAndCanBeConfirmed() {
        val controller = Robolectric.buildActivity(GeofencePickerActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        activity.findViewById<View>(R.id.btnEnterCoordinates).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val fields = mutableListOf<EditText>()
        fun collect(view: View) {
            if (view is EditText) fields.add(view)
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(dialog.window!!.decorView)
        fields[0].setText("-33.8688")
        fields[1].setText("151.2093")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        activity.findViewById<EditText>(R.id.editGeofenceRadius).setText("750")
        activity.findViewById<RadioGroup>(R.id.radioGeofenceDirection).check(R.id.radioGeofenceExit)
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()

        val restoredController = Robolectric.buildActivity(GeofencePickerActivity::class.java).create(state).start().restoreInstanceState(state).resume()
        val restored = restoredController.get()
        restored.findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        restored.findViewById<View>(R.id.btnGeofenceSave).performClick()

        assertTrue(restored.isFinishing)
        val result = shadowOf(restored).resultIntent
        assertEquals(GeofenceSpec(-33.8688, 151.2093, 750.0), GeofenceSpec.parse(result.getStringExtra(GeofencePickerActivity.EXTRA_FENCE)))
        assertEquals("exit", result.getStringExtra(GeofencePickerActivity.EXTRA_DIRECTION))
        restoredController.pause().stop().destroy()
    }
}
