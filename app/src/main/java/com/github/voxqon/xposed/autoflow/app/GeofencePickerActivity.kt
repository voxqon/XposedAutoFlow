package com.github.voxqon.xposed.autoflow.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.GeofenceSpec
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.CustomZoomButtonsController
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class GeofencePickerActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var boundary: Polygon
    private lateinit var marker: Marker
    private lateinit var radiusEdit: EditText
    private lateinit var radiusLayout: TextInputLayout
    private lateinit var slider: Slider
    private lateinit var searchEdit: EditText
    private lateinit var searchLayout: TextInputLayout
    private lateinit var status: TextView
    private lateinit var locationButton: ImageButton
    private lateinit var direction: RadioGroup
    private lateinit var mapSourceButton: MaterialButton
    private lateinit var mapStatus: TextView
    private lateinit var mapRetry: View
    private var mapSource = GeofenceMapSource.HUMANITARIAN
    private val attemptedSources = mutableSetOf<GeofenceMapSource>()
    private var tileFailures = 0
    private var tileGeneration = 0
    private var mapLoadTimeout: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private var searchTask: Future<*>? = null
    private var searchGeneration = 0
    private var searchTimeout: Runnable? = null
    private var locationTimeout: Runnable? = null
    private var selectedPoint: GeoPoint? = null
    private var selectedName: String? = null
    private var radiusMeters = 200.0
    private var updatingRadius = false
    private var draggingRadius = false
    private var displayedRadius = 200.0
    private var radiusAnimator: ValueAnimator? = null
    private val radiusPreview = Runnable { fitBoundary() }
    private var locating = false

    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!locating || !isUsableLocation(location)) return
            selectPoint(GeoPoint(location.latitude, location.longitude), getString(R.string.geofence_current_place))
            showStatus(getString(R.string.geofence_location_accuracy, location.accuracy.roundToInt()))
        }

        override fun onProviderDisabled(provider: String) = Unit
        override fun onProviderEnabled(provider: String) = Unit

        @Deprecated("Required on older Android versions")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) locate() else showStatus(getString(R.string.geofence_location_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureMapCache()
        setContentView(R.layout.activity_geofence_picker)
        map = findViewById(R.id.geofenceMap)
        radiusEdit = findViewById(R.id.editGeofenceRadius)
        radiusLayout = findViewById(R.id.layoutGeofenceRadius)
        slider = findViewById(R.id.sliderGeofenceRadius)
        searchEdit = findViewById(R.id.editPlaceSearch)
        searchLayout = findViewById(R.id.layoutPlaceSearch)
        status = findViewById(R.id.textGeofenceStatus)
        locationButton = findViewById(R.id.btnCurrentPlace)
        direction = findViewById(R.id.radioGeofenceDirection)
        mapSourceButton = findViewById(R.id.btnMapSource)
        mapStatus = findViewById(R.id.textMapStatus)
        mapRetry = findViewById(R.id.btnMapRetry)
        mapSource = GeofenceMapSource.fromName(
            savedInstanceState?.getString(STATE_MAP_SOURCE)
                ?: getPreferences(MODE_PRIVATE).getString(PREF_MAP_SOURCE, null),
        )

        val initial = GeofenceSpec.parse(
            if (savedInstanceState != null) savedInstanceState.getString(STATE_FENCE)
            else intent.getStringExtra(EXTRA_FENCE),
        )
        selectedPoint = initial?.let { GeoPoint(it.latitude, it.longitude) }
        selectedName = if (savedInstanceState != null) savedInstanceState.getString(STATE_NAME)
        else intent.getStringExtra(EXTRA_NAME)
        radiusMeters = initial?.radiusMeters ?: savedInstanceState?.getDouble(STATE_RADIUS, 200.0) ?: 200.0
        displayedRadius = radiusMeters
        val transition = if (savedInstanceState != null) savedInstanceState.getString(STATE_DIRECTION)
        else intent.getStringExtra(EXTRA_DIRECTION)
        direction.check(when (transition?.lowercase(Locale.ROOT)) {
            "enter" -> R.id.radioGeofenceEnter
            "exit" -> R.id.radioGeofenceExit
            "inside" -> R.id.radioGeofenceInside
            "outside" -> R.id.radioGeofenceOutside
            else -> R.id.radioGeofenceAny
        })
        setupMap(savedInstanceState)
        setupRadius()
        updateSelection()

        findViewById<View>(R.id.btnGeofenceBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnGeofenceSave).setOnClickListener { confirmSelection() }
        findViewById<View>(R.id.btnEnterCoordinates).setOnClickListener { enterCoordinates() }
        mapSourceButton.setOnClickListener { chooseMapSource() }
        mapRetry.setOnClickListener {
            attemptedSources.clear()
            applyMapSource(mapSource)
        }
        locationButton.setOnClickListener {
            cancelSearch()
            if (hasLocationPermission()) locate()
            else locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
        searchLayout.setEndIconOnClickListener { search() }
        searchEdit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }
        searchEdit.doAfterTextChanged {
            cancelSearch()
            searchLayout.error = null
        }
        findViewById<TextView>(R.id.textMapAttribution).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapSource.attributionUrl))) }
        }
        if (getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
            showStatus(getString(R.string.geofence_offline))
        }
    }

    private fun configureMapCache() {
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("map_config", MODE_PRIVATE))
            userAgentValue = "AutoTaskLite/${packageManager.getPackageInfo(packageName, 0).versionName} ($packageName; Android ${Build.VERSION.RELEASE})"
            osmdroidBasePath = File(cacheDir, "maps").apply { mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "tiles").apply { mkdirs() }
            tileDownloadThreads = 2
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap(state: Bundle?) {
        applyMapSource(mapSource)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        // Let the map consume drags inside the surrounding scroll view.
        map.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                cancelRadiusAnimation()
                displayedRadius = radiusMeters
                updateBoundary()
            }
            view.parent.requestDisallowInterceptTouchEvent(
                event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL,
            )
            false
        }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                selectPoint(point, null, moveCamera = false)
                return true
            }

            override fun longPressHelper(point: GeoPoint): Boolean =
                singleTapConfirmedHelper(point)
        }))
        boundary = Polygon(map).apply {
            fillPaint.color = 0x242563EB
            outlinePaint.color = getColor(R.color.primary)
            outlinePaint.strokeWidth = 2 * resources.displayMetrics.density
            setOnClickListener { _, _, point ->
                selectPoint(point, null, moveCamera = false)
                true
            }
        }
        marker = Marker(map).apply {
            icon = ContextCompat.getDrawable(this@GeofencePickerActivity, R.drawable.ic_geofence_pin)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ -> true }
        }
        map.overlays.add(boundary)
        map.overlays.add(marker)
        val camera = if (state != null) {
            GeoPoint(state.getDouble(STATE_MAP_LAT), state.getDouble(STATE_MAP_LON))
        } else selectedPoint ?: GeoPoint(35.0, 105.0)
        map.controller.setZoom(state?.getDouble(STATE_MAP_ZOOM) ?: if (selectedPoint == null) 4.0 else 16.0)
        map.controller.setCenter(camera)
        if (state == null && selectedPoint != null) map.post { fitBoundary(animate = false) }
    }

    private fun applyMapSource(source: GeofenceMapSource, fallback: Boolean = false) {
        mapLoadTimeout?.let(handler::removeCallbacks)
        cancelRadiusAnimation()
        if (::boundary.isInitialized) {
            displayedRadius = radiusMeters
            updateBoundary()
        }
        mapSource = source
        attemptedSources.add(source)
        tileFailures = 0
        val generation = ++tileGeneration
        val useNetwork = map.tileProvider.useDataConnection()
        val provider = MapTileProviderBasic(this, source.tileSource()).apply {
            setUseDataConnection(useNetwork)
            tileRequestCompleteHandlers.add(Handler(Looper.getMainLooper()) { message ->
                if (generation == tileGeneration && !isFinishing && !isDestroyed) {
                    when (message.what) {
                        MapTileProviderBase.MAPTILE_SUCCESS_ID -> {
                            mapLoadTimeout?.let(handler::removeCallbacks)
                            mapLoadTimeout = null
                            tileFailures = 0
                            showMapStatus(null)
                        }
                        MapTileProviderBase.MAPTILE_FAIL_ID -> onTileFailure()
                    }
                }
                true
            })
        }
        // Replace the provider so pending responses from the old source cannot pollute the new one.
        map.setTileProvider(provider)
        map.setMinZoomLevel(2.0)
        map.setMaxZoomLevel(source.maximumZoom.toDouble())
        if (map.zoomLevelDouble > source.maximumZoom) {
            map.controller.setZoom(source.maximumZoom.toDouble())
        }
        mapSourceButton.setText(source.label)
        findViewById<TextView>(R.id.textMapAttribution).text = source.attribution
        showMapStatus(if (fallback) getString(R.string.geofence_map_fallback, getString(source.label)) else null)
        mapLoadTimeout = Runnable {
            if (generation == tileGeneration && !isFinishing && !isDestroyed) tryNextMapSource()
        }.also { handler.postDelayed(it, 12_000L) }
        map.invalidate()
    }

    private fun onTileFailure() {
        tileFailures++
        if (tileFailures < 4) return
        tryNextMapSource()
    }

    private fun tryNextMapSource() {
        mapLoadTimeout?.let(handler::removeCallbacks)
        mapLoadTimeout = null
        if (getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
            showMapStatus(getString(R.string.geofence_offline), retry = true)
            return
        }
        val next = GeofenceMapSource.entries.firstOrNull { it !in attemptedSources }
        if (next != null) applyMapSource(next, fallback = true)
        else showMapStatus(getString(R.string.geofence_map_failed), retry = true)
    }

    private fun showMapStatus(message: String?, retry: Boolean = false) {
        mapStatus.text = message
        findViewById<View>(R.id.layoutMapStatus).visibility = if (message == null) View.GONE else View.VISIBLE
        mapRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun chooseMapSource() {
        PopupMenu(this, mapSourceButton).apply {
            GeofenceMapSource.entries.forEach { source ->
                menu.add(0, source.ordinal, source.ordinal, source.label).apply {
                    isCheckable = true
                    isChecked = source == mapSource
                }
            }
            setOnMenuItemClickListener { item ->
                val source = GeofenceMapSource.entries[item.itemId]
                attemptedSources.clear()
                getPreferences(MODE_PRIVATE).edit().putString(PREF_MAP_SOURCE, source.name).apply()
                applyMapSource(source)
                true
            }
            show()
        }
    }

    private fun setupRadius() {
        setRadius(radiusMeters)
        radiusEdit.doAfterTextChanged {
            if (updatingRadius) return@doAfterTextChanged
            val value = validRadius()
            radiusLayout.error = if (value == null) getString(R.string.geofence_radius_invalid) else null
            if (value != null) {
                radiusMeters = value
                updatingRadius = true
                slider.value = radiusToProgress(value)
                updatingRadius = false
                updateRadiusPresets()
                handler.removeCallbacks(radiusPreview)
                handler.postDelayed(radiusPreview, 180L)
            }
        }
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !updatingRadius) {
                setRadius(10.0.pow(value / 200.0).roundToLong().coerceIn(1L, 100_000L).toDouble())
                if (!draggingRadius) fitBoundary()
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                draggingRadius = true
                cancelRadiusAnimation()
            }

            override fun onStopTrackingTouch(slider: Slider) {
                draggingRadius = false
                fitBoundary()
            }
        })
        listOf(
            R.id.chipRadius100 to 100.0,
            R.id.chipRadius200 to 200.0,
            R.id.chipRadius500 to 500.0,
            R.id.chipRadius1000 to 1000.0,
        ).forEach { (id, radius) ->
            findViewById<View>(id).setOnClickListener {
                setRadius(radius, updatePreview = false)
                fitBoundary()
            }
        }
    }

    private fun validRadius(): Double? = radiusEdit.text.toString().trim().toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 && it <= GeofenceSpec.MAX_RADIUS_METERS }

    private fun radiusToProgress(radius: Double): Float =
        (log10(radius.coerceAtLeast(1.0)) * 200.0).toFloat().coerceIn(slider.valueFrom, slider.valueTo).roundToInt().toFloat()

    private fun setRadius(value: Double, updatePreview: Boolean = true) {
        updatingRadius = true
        radiusMeters = value
        radiusEdit.setText(java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
        radiusLayout.error = null
        slider.value = radiusToProgress(value)
        updatingRadius = false
        updateRadiusPresets()
        if (updatePreview) {
            cancelRadiusAnimation()
            displayedRadius = value
            updateBoundary()
        }
    }

    private fun updateRadiusPresets() {
        listOf(
            R.id.chipRadius100 to 100.0,
            R.id.chipRadius200 to 200.0,
            R.id.chipRadius500 to 500.0,
            R.id.chipRadius1000 to 1000.0,
        ).forEach { (id, value) ->
            findViewById<Chip>(id).isChecked = radiusMeters == value
        }
    }

    private fun selectPoint(point: GeoPoint, name: String?, moveCamera: Boolean = true) {
        cancelRadiusAnimation()
        displayedRadius = radiusMeters
        cancelSearch()
        stopLocating()
        selectedPoint = point
        selectedName = name?.takeIf { it.isNotBlank() }
        showStatus(null)
        updateSelection()
        if (moveCamera) fitBoundary()
    }

    private fun updateSelection() {
        val point = selectedPoint
        findViewById<TextView>(R.id.textSelectedPlace).text = selectedName
            ?: getString(if (point == null) R.string.geofence_no_place else R.string.geofence_map_place)
        findViewById<TextView>(R.id.textSelectedCoordinates).apply {
            text = point?.let { String.format(Locale.ROOT, "%.6f, %.6f", it.latitude, it.longitude) }.orEmpty()
            visibility = if (point == null) View.GONE else View.VISIBLE
        }
        marker.isEnabled = point != null
        point?.let { marker.position = it }
        updateBoundary()
    }

    private fun updateBoundary() {
        boundary.isEnabled = selectedPoint != null
        selectedPoint?.let { boundary.points = Polygon.pointsAsCircle(it, displayedRadius) }
        map.invalidate()
    }

    private fun fitBoundary(animate: Boolean = true) {
        val point = selectedPoint ?: return
        cancelRadiusAnimation()
        if (map.width == 0 || map.height == 0) {
            displayedRadius = radiusMeters
            updateBoundary()
            return
        }
        // Account for latitude and the map's shorter edge so the entire circle fits.
        val pixels = minOf(map.width, map.height) * 0.65
        val metersPerPixel = radiusMeters * 2.0 / pixels
        val latitudeFactor = kotlin.math.cos(Math.toRadians(point.latitude)).coerceAtLeast(0.01)
        val zoom = (kotlin.math.ln(156543.03392 * latitudeFactor / metersPerPixel) / kotlin.math.ln(2.0))
            .coerceIn(2.0, mapSource.maximumZoom.toDouble())
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            displayedRadius = radiusMeters
            updateBoundary()
            map.controller.setZoom(zoom)
            map.controller.setCenter(point)
            return
        }
        val startZoom = map.zoomLevelDouble
        val startCenter = GeoPoint(map.mapCenter)
        val startRadius = ln(displayedRadius)
        val endRadius = ln(radiusMeters)
        val longitudeDelta = ((point.longitude - startCenter.longitude + 540.0) % 360.0) - 180.0
        // Use one animation for the circle and camera; a new target starts at the current frame.
        radiusAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = (it.animatedValue as Float).toDouble()
                displayedRadius = exp(startRadius + (endRadius - startRadius) * fraction)
                map.controller.setZoom(startZoom + (zoom - startZoom) * fraction)
                map.controller.setCenter(GeoPoint(
                    startCenter.latitude + (point.latitude - startCenter.latitude) * fraction,
                    ((startCenter.longitude + longitudeDelta * fraction + 540.0) % 360.0) - 180.0,
                ))
                updateBoundary()
            }
            start()
        }
    }

    private fun cancelRadiusAnimation() {
        handler.removeCallbacks(radiusPreview)
        radiusAnimator?.cancel()
        radiusAnimator = null
    }

    private fun enterCoordinates() {
        val form = layoutInflater.inflate(R.layout.dialog_geofence_coordinates, null)
        val latitude = form.findViewById<EditText>(R.id.editLatitude)
        val longitude = form.findViewById<EditText>(R.id.editLongitude)
        val latitudeLayout = form.findViewById<TextInputLayout>(R.id.layoutLatitude)
        val longitudeLayout = form.findViewById<TextInputLayout>(R.id.layoutLongitude)
        latitude.setText(selectedPoint?.latitude?.toString().orEmpty())
        longitude.setText(selectedPoint?.longitude?.toString().orEmpty())
        latitude.doAfterTextChanged { latitudeLayout.error = null }
        longitude.doAfterTextChanged { longitudeLayout.error = null }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.geofence_coordinates)
            .setView(form)
            .setPositiveButton(R.string.geofence_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lat = latitude.text.toString().trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
                val lon = longitude.text.toString().trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
                latitudeLayout.error = if (lat == null) getString(R.string.geofence_latitude_invalid) else null
                longitudeLayout.error = if (lon == null) getString(R.string.geofence_longitude_invalid) else null
                when {
                    lat == null -> latitude.requestFocus()
                    lon == null -> longitude.requestFocus()
                    else -> {
                        selectPoint(GeoPoint(lat, lon), null)
                        dialog.dismiss()
                    }
                }
            }
            longitude.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
                    true
                } else false
            }
        }
        dialog.showResponsive()
    }

    private fun search() {
        val query = searchEdit.text.toString().trim()
        if (query.isEmpty()) {
            searchLayout.error = getString(R.string.geofence_search_empty)
            return
        }
        if (!Geocoder.isPresent()) {
            showStatus(getString(R.string.geofence_search_unavailable))
            return
        }
        stopLocating()
        cancelSearch()
        val generation = searchGeneration
        searchLayout.error = null
        showStatus(getString(R.string.geofence_searching))
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchEdit.windowToken, 0)
        searchEdit.clearFocus()
        searchTimeout = Runnable {
            if (generation == searchGeneration) {
                cancelSearch()
                showStatus(getString(R.string.geofence_search_failed))
            }
        }.also { handler.postDelayed(it, 20_000L) }
        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                geocoder.getFromLocationName(query, 6, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        handler.post { showSearchResults(generation, addresses) }
                    }

                    override fun onError(errorMessage: String?) {
                        handler.post { showSearchResults(generation, null) }
                    }
                })
            }.onFailure { showSearchResults(generation, null) }
        } else {
            searchTask = searchExecutor.submit {
                @Suppress("DEPRECATION")
                val results = runCatching { geocoder.getFromLocationName(query, 6) }.getOrNull()
                handler.post { showSearchResults(generation, results) }
            }
        }
    }

    private fun showSearchResults(generation: Int, addresses: List<Address>?) {
        if (generation != searchGeneration || isFinishing || isDestroyed) return
        cancelSearch()
        if (addresses == null) {
            showStatus(getString(R.string.geofence_search_failed))
            return
        }
        val valid = addresses.filter {
            it.hasLatitude() && it.hasLongitude() &&
                GeofenceSpec.parse("${it.latitude},${it.longitude},$radiusMeters") != null
        }
        if (valid.isEmpty()) {
            showStatus(getString(R.string.geofence_search_no_results))
            return
        }
        showStatus(null)
        val labels = valid.map { address ->
            address.getAddressLine(0)?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(address.featureName, address.locality, address.adminArea)
                    .distinct().joinToString(" ").ifBlank {
                        String.format(Locale.ROOT, "%.6f, %.6f", address.latitude, address.longitude)
                    }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.geofence_search_results)
            .setItems(labels.toTypedArray()) { _, index ->
                val address = valid[index]
                selectPoint(GeoPoint(address.latitude, address.longitude), labels[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showResponsive()
    }

    private fun cancelSearch() {
        searchGeneration++
        if (searchTimeout != null) showStatus(null)
        searchTimeout?.let(handler::removeCallbacks)
        searchTimeout = null
        searchTask?.cancel(true)
        searchTask = null
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun locate() {
        if (!hasLocationPermission()) return
        stopLocating()
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.geofence_location_disabled)
                .setPositiveButton(R.string.geofence_open_settings) { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showResponsive()
            return
        }
        locating = true
        locationButton.isEnabled = false
        showStatus(getString(R.string.geofence_locating))
        val cached = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { isUsableLocation(it) }
            .minByOrNull { it.accuracy }
        if (cached != null) {
            locationListener.onLocationChanged(cached)
            return
        }
        var registered = false
        providers.forEach { provider ->
            runCatching { locationManager.requestLocationUpdates(provider, 0L, 0f, locationListener, Looper.getMainLooper()) }
                .onSuccess { registered = true }
        }
        if (!registered) {
            stopLocating()
            showStatus(getString(R.string.geofence_location_failed))
            return
        }
        locationTimeout = Runnable {
            stopLocating()
            showStatus(getString(R.string.geofence_location_failed))
        }.also { handler.postDelayed(it, 20_000L) }
    }

    private fun isUsableLocation(location: Location): Boolean {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return age in 0L..120_000_000_000L &&
            location.hasAccuracy() && location.accuracy.isFinite() &&
            GeofenceSpec.parse("${location.latitude},${location.longitude},$radiusMeters") != null
    }

    private fun stopLocating() {
        locationTimeout?.let(handler::removeCallbacks)
        locationTimeout = null
        if (locating) runCatching { locationManager.removeUpdates(locationListener) }
        locating = false
        locationButton.isEnabled = true
    }

    private fun showStatus(message: String?) {
        status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun selectedDirection(): String = when (direction.checkedRadioButtonId) {
        R.id.radioGeofenceEnter -> "enter"
        R.id.radioGeofenceExit -> "exit"
        R.id.radioGeofenceInside -> "inside"
        R.id.radioGeofenceOutside -> "outside"
        else -> "any"
    }

    private fun confirmSelection() {
        val point = selectedPoint
        if (point == null) {
            Toast.makeText(this, R.string.geofence_select_required, Toast.LENGTH_SHORT).show()
            return
        }
        val radius = validRadius()
        if (radius == null) {
            radiusLayout.error = getString(R.string.geofence_radius_invalid)
            radiusEdit.requestFocus()
            return
        }
        val fence = GeofenceSpec.parse("${point.latitude},${point.longitude},$radius") ?: return
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_FENCE, fence.id)
            putExtra(EXTRA_DIRECTION, selectedDirection())
            putExtra(EXTRA_NAME, selectedName)
        })
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedPoint?.let {
            outState.putString(STATE_FENCE, GeofenceSpec(it.latitude, it.longitude, radiusMeters).id)
        }
        outState.putString(STATE_NAME, selectedName)
        outState.putDouble(STATE_RADIUS, radiusMeters)
        outState.putString(STATE_DIRECTION, selectedDirection())
        outState.putDouble(STATE_MAP_LAT, map.mapCenter.latitude)
        outState.putDouble(STATE_MAP_LON, map.mapCenter.longitude)
        outState.putDouble(STATE_MAP_ZOOM, map.zoomLevelDouble)
        outState.putString(STATE_MAP_SOURCE, mapSource.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        cancelRadiusAnimation()
        displayedRadius = radiusMeters
        updateBoundary()
        map.onPause()
        super.onPause()
    }

    override fun onStop() {
        cancelSearch()
        stopLocating()
        showStatus(null)
        super.onStop()
    }

    override fun onDestroy() {
        tileGeneration++
        searchExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        map.onDetach()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FENCE = "geofence_spec"
        const val EXTRA_DIRECTION = "geofence_direction"
        const val EXTRA_NAME = "geofence_place_name"
        private const val STATE_FENCE = "selected_fence"
        private const val STATE_NAME = "selected_name"
        private const val STATE_RADIUS = "selected_radius"
        private const val STATE_DIRECTION = "selected_direction"
        private const val STATE_MAP_LAT = "map_latitude"
        private const val STATE_MAP_LON = "map_longitude"
        private const val STATE_MAP_ZOOM = "map_zoom"
        private const val STATE_MAP_SOURCE = "map_source"
        private const val PREF_MAP_SOURCE = "preferred_map_source"
    }
}
