package com.maslul.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.maslul.app.data.GeoPoint
import com.maslul.app.ui.theme.LocalExtra
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class MapLine(val points: List<GeoPoint>, val color: Color, val width: Float = 5f, val dashed: Boolean = false)

enum class MarkerKind { STOP, STOP_SMALL, ORIGIN, DESTINATION }

data class MapMarker(
    val point: GeoPoint,
    val color: Color,
    val kind: MarkerKind = MarkerKind.STOP,
    val id: String? = null,
)

data class MapVehicle(
    val id: String,
    val point: GeoPoint,
    val bearing: Float?,
    val color: Color,
    val label: String,
    /** Drawn translucent, e.g. when its position stopped updating. */
    val faded: Boolean = false,
)

/** Lets a screen move the camera imperatively (e.g. "my location" button). */
class MapController {
    internal var map: MapLibreMap? = null
    fun moveTo(p: GeoPoint, zoom: Double = 15.5) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), zoom), 600)
    }
    val center: GeoPoint? get() = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }
}

private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

@Composable
fun TransitMap(
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    vehicles: List<MapVehicle> = emptyList(),
    user: GeoPoint? = null,
    /** Camera fits these points whenever [fitKey] changes. */
    fitPoints: List<GeoPoint> = emptyList(),
    fitKey: Any? = null,
    initialCenter: GeoPoint? = null,
    initialZoom: Double = 14.0,
    contentPadding: PaddingValues = PaddingValues(),
    controller: MapController? = null,
    onMarkerClick: ((String) -> Unit)? = null,
    onLongPress: ((GeoPoint) -> Unit)? = null,
    onCameraIdle: ((GeoPoint) -> Unit)? = null,
    /** The camera follows this vehicle (by [MapVehicle.id]) as its position refreshes. */
    focusVehicleId: String? = null,
    /** Called when the user drags the map, so the screen can stop following. */
    onUserPan: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val dark = LocalExtra.current.isDark
    val density = LocalDensity.current
    val layoutDir = LocalLayoutDirection.current
    var style by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val markerClick by rememberUpdatedState(onMarkerClick)
    val longPress by rememberUpdatedState(onLongPress)
    val cameraIdle by rememberUpdatedState(onCameraIdle)
    val userPan by rememberUpdatedState(onUserPan)
    val followingNow by rememberUpdatedState(focusVehicleId?.takeIf { id -> vehicles.any { it.id == id } })
    /** Where each vehicle is currently drawn, so refreshed positions glide instead of jumping. */
    val drawn = remember { HashMap<String, GeoPoint>() }

    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            controller?.map = null
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                attributionGravity = Gravity.BOTTOM or Gravity.START
            }
            val c = initialCenter ?: fitPoints.firstOrNull() ?: GeoPoint(32.08, 34.79)
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.lat, c.lon), initialZoom))
            m.addOnMapClickListener { latLng ->
                val cb = markerClick ?: return@addOnMapClickListener false
                val pt = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(pt, "m-markers", "m-vehicles").firstOrNull()
                val id = hit?.getStringProperty("id")
                if (id != null) { cb(id); true } else false
            }
            m.addOnMapLongClickListener { latLng ->
                longPress?.let { it(GeoPoint(latLng.latitude, latLng.longitude)); true } ?: false
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userPan?.invoke()
            }
            m.addOnCameraIdleListener {
                m.cameraPosition.target?.let { t -> cameraIdle?.invoke(GeoPoint(t.latitude, t.longitude)) }
            }
            controller?.map = m
            map = m
        }
    }

    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri(if (dark) DARK_STYLE else LIGHT_STYLE)) { s ->
            setupLayers(s, dark)
            style = s
        }
    }

    // Push data into the map whenever it (or the style) changes.
    LaunchedEffect(style, lines, markers, user) {
        val s = style ?: return@LaunchedEffect
        (s.getSource("m-lines") as? GeoJsonSource)?.setGeoJson(linesFc(lines))
        (s.getSource("m-markers") as? GeoJsonSource)?.setGeoJson(markersFc(markers))
        (s.getSource("m-user") as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(user?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })),
        )
    }

    // Vehicles glide from where they're drawn to their refreshed position.
    LaunchedEffect(style, vehicles, focusVehicleId) {
        val s = style ?: return@LaunchedEffect
        val src = s.getSource("m-vehicles") as? GeoJsonSource ?: return@LaunchedEffect
        ensureVehicleIcons(s, vehicles)
        drawn.keys.retainAll(vehicles.map { it.id }.toSet())
        val from = vehicles.associate { it.id to (drawn[it.id] ?: it.point) }
        if (vehicles.any { from.getValue(it.id) != it.point }) {
            animate(0f, 1f, animationSpec = tween(1400, easing = FastOutSlowInEasing)) { f, _ ->
                val frame = vehicles.map { v -> v.copy(point = lerpPoint(from.getValue(v.id), v.point, f)) }
                frame.forEach { drawn[it.id] = it.point }
                src.setGeoJson(vehiclesFc(frame, focusVehicleId))
            }
        }
        vehicles.forEach { drawn[it.id] = it.point }
        src.setGeoJson(vehiclesFc(vehicles, focusVehicleId))
    }

    // Follow the focused vehicle: zoom in on it once, then pan along as its position refreshes.
    val focused = vehicles.firstOrNull { it.id == focusVehicleId }
    var followed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(map, style != null, focusVehicleId, focused?.point) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val p = focused?.point
        if (focusVehicleId == null || p == null) {
            followed = null
            return@LaunchedEffect
        }
        val first = followed != focusVehicleId
        followed = focusVehicleId
        val pad = with(density) {
            doubleArrayOf(
                contentPadding.calculateStartPadding(layoutDir).toPx().toDouble(),
                contentPadding.calculateTopPadding().toPx().toDouble(),
                contentPadding.calculateEndPadding(layoutDir).toPx().toDouble(),
                contentPadding.calculateBottomPadding().toPx().toDouble(),
            )
        }
        val camera = CameraPosition.Builder()
            .target(LatLng(p.lat, p.lon))
            .zoom(if (first) maxOf(m.cameraPosition.zoom, 15.5) else m.cameraPosition.zoom)
            .padding(pad)
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), if (first) 700 else 1400)
    }

    LaunchedEffect(map, style != null, fitKey) {
        val m = map ?: return@LaunchedEffect
        if (style == null || fitKey == null || fitPoints.isEmpty()) return@LaunchedEffect
        // While following a vehicle, don't yank the camera back to the whole route.
        if (followingNow != null) return@LaunchedEffect
        val px = with(density) {
            intArrayOf(
                (contentPadding.calculateStartPadding(layoutDir) + 36.dp).roundToPx(),
                (contentPadding.calculateTopPadding() + 36.dp).roundToPx(),
                (contentPadding.calculateEndPadding(layoutDir) + 36.dp).roundToPx(),
                (contentPadding.calculateBottomPadding() + 36.dp).roundToPx(),
            )
        }
        val distinct = fitPoints.distinct()
        if (distinct.size == 1) {
            val p = distinct.first()
            // Camera padding keeps the point centred in the visible area above a bottom sheet.
            val edge = 36 * density.density
            val pad = DoubleArray(4) { (px[it] - edge).toDouble() }
            m.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(15.5).padding(pad).build(),
                ),
            )
        } else {
            val b = LatLngBounds.Builder().apply { distinct.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            runCatching {
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, px[0], px[1], px[2], px[3]))
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun setupLayers(s: Style, dark: Boolean) {
    val halo = if (dark) "#171A20" else "#FFFFFF"
    s.addSource(GeoJsonSource("m-lines"))
    s.addSource(GeoJsonSource("m-markers"))
    s.addSource(GeoJsonSource("m-vehicles"))
    s.addSource(GeoJsonSource("m-user"))

    // Casing under solid lines keeps them legible over busy map areas.
    s.addLayer(
        LineLayer("m-lines-casing", "m-lines")
            .withFilter(Expression.eq(Expression.get("dashed"), false))
            .withProperties(
                PropertyFactory.lineColor(halo),
                PropertyFactory.lineWidth(Expression.sum(Expression.get("width"), Expression.literal(3f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
    )
    s.addLayer(
        LineLayer("m-lines-solid", "m-lines")
            .withFilter(Expression.eq(Expression.get("dashed"), false))
            .withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(Expression.get("width")),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
    )
    s.addLayer(
        LineLayer("m-lines-dashed", "m-lines")
            .withFilter(Expression.eq(Expression.get("dashed"), true))
            .withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(Expression.get("width")),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineDasharray(arrayOf(0.2f, 1.6f)),
            ),
    )
    s.addLayer(
        CircleLayer("m-markers", "m-markers").withProperties(
            PropertyFactory.circleRadius(Expression.get("r")),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("fill"))),
            PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("stroke"))),
            PropertyFactory.circleStrokeWidth(Expression.get("sw")),
        ),
    )
    s.addLayer(
        CircleLayer("m-user-halo", "m-user").withProperties(
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleColor("#1F6FEB"),
            PropertyFactory.circleOpacity(0.16f),
        ),
    )
    s.addLayer(
        CircleLayer("m-user", "m-user").withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor("#1F6FEB"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    // Soft halo marking the vehicle the camera is following.
    s.addLayer(
        CircleLayer("m-vehicles-focus", "m-vehicles")
            .withFilter(Expression.eq(Expression.get("focus"), true))
            .withProperties(
                PropertyFactory.circleRadius(26f),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleOpacity(0.22f),
                PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeOpacity(0.5f),
            ),
    )
    s.addLayer(
        SymbolLayer("m-vehicles", "m-vehicles").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconOpacity(Expression.get("opacity")),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        ),
    )
}

private fun hex(c: Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())

private fun linesFc(lines: List<MapLine>) = FeatureCollection.fromFeatures(
    lines.filter { it.points.size >= 2 }.map { l ->
        Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
            addStringProperty("color", hex(l.color))
            addNumberProperty("width", l.width)
            addBooleanProperty("dashed", l.dashed)
        }
    },
)

private fun markersFc(markers: List<MapMarker>) = FeatureCollection.fromFeatures(
    markers.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.point.lon, m.point.lat)).apply {
            m.id?.let { addStringProperty("id", it) }
            when (m.kind) {
                MarkerKind.STOP -> { r(5.5f); fill("#FFFFFF"); stroke(hex(m.color)); sw(3f) }
                MarkerKind.STOP_SMALL -> { r(3.5f); fill("#FFFFFF"); stroke(hex(m.color)); sw(2f) }
                MarkerKind.ORIGIN -> { r(7f); fill("#FFFFFF"); stroke("#0F1115"); sw(3.5f) }
                MarkerKind.DESTINATION -> { r(8f); fill(hex(m.color)); stroke("#FFFFFF"); sw(3f) }
            }
        }
    },
)

private fun Feature.r(v: Float) = addNumberProperty("r", v)
private fun Feature.fill(v: String) = addStringProperty("fill", v)
private fun Feature.stroke(v: String) = addStringProperty("stroke", v)
private fun Feature.sw(v: Float) = addNumberProperty("sw", v)

private fun lerpPoint(a: GeoPoint, b: GeoPoint, f: Float) =
    GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)

private fun vehiclesFc(vs: List<MapVehicle>, focusId: String?) = FeatureCollection.fromFeatures(
    vs.map { v ->
        Feature.fromGeometry(Point.fromLngLat(v.point.lon, v.point.lat)).apply {
            addStringProperty("id", v.id)
            addStringProperty("color", hex(v.color))
            addBooleanProperty("focus", v.id == focusId)
            addNumberProperty("opacity", if (v.faded) 0.55f else 1f)
            addStringProperty("label", v.label.take(4))
            addStringProperty("icon", iconName(v.color, v.bearing != null))
            addNumberProperty("bearing", v.bearing ?: 0f)
        }
    },
)

private fun iconName(c: Color, pointed: Boolean) = "veh-${hex(c)}-${if (pointed) "p" else "c"}"

/** Vehicle marker: a coloured disc with a direction tip, drawn once per colour. */
private fun ensureVehicleIcons(s: Style, vs: List<MapVehicle>) {
    for (v in vs) {
        val name = iconName(v.color, v.bearing != null)
        if (s.getImage(name) != null) continue
        // Drawn at 3x (xxhdpi) so it renders ~32dp regardless of screen density.
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.density = android.util.DisplayMetrics.DENSITY_XXHIGH
        val cnv = Canvas(bmp)
        val cx = size / 2f
        val r = 30f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = v.color.toArgb() }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        if (v.bearing != null) {
            val tip = Path().apply {
                moveTo(cx, 1f); lineTo(cx - 15f, cx - r + 8f); lineTo(cx + 15f, cx - r + 8f); close()
            }
            cnv.drawPath(tip, white)
            val inner = Path().apply {
                moveTo(cx, 7f); lineTo(cx - 10f, cx - r + 8f); lineTo(cx + 10f, cx - r + 8f); close()
            }
            cnv.drawPath(inner, fill)
        }
        cnv.drawCircle(cx, cx, r + 3f, white)
        cnv.drawCircle(cx, cx, r, fill)
        s.addImage(name, bmp)
    }
}
