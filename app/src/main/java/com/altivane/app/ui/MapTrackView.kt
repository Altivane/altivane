package com.altivane.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import com.altivane.app.domain.AppLog
import kotlinx.coroutines.delay
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/** CARTO Voyager 免费矢量样式（明亮主流 OSM 风；离线缓存绑定此 URL 勿改）。
 *  v0.8.33 自 OpenFreeMap Liberty 迁移：桌面基准 100 瓦片 8 并发，CARTO 100/100 成功 17s
 *  vs OpenFreeMap 挂 10 个 59s——在线出图与离线批量下载全面占优。归署名：© OSM © CARTO */
internal const val MAP_STYLE_URL = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"

/** Esri World Imagery 免费卫星栅格（无 key；SLA 无保证） */
private const val SAT_TILE =
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

/** OpenTopoMap 免费等高线栅格（徒步/越野向，含地形晕渲；© OpenTopoMap CC-BY-SA，量大有限流） */
private val TOPO_TILES = arrayOf(
    "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
    "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
    "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
)

/** CyclOSM 免费骑行向栅格（OSM 骑行要素渲染；© CyclOSM/OSM 贡献者） */
private val CYCLO_TILES = arrayOf(
    "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
    "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
    "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png"
)

/** 图层模式名（点击图层钮循环）：0 标准 / 1 卫星 / 2 徒步 / 3 骑行 */
private val LAYER_MODES = arrayOf("标准", "卫星", "徒步", "骑行")

/**
 * MapLibre 轨迹视图：暗色矢量底图（卫星可切）+ 速度彩虹轨迹 + 打卡点。
 * 坐标 WGS84 直接绘制（OSM 底图同系，无 GCJ 偏差）。
 *
 * 两种模式（与旧 AmapTrackView 语义一致）：
 * - follow=false（回放）：数据变化全量重画并 fit 全程 — 运动详情/预置路线
 * - follow=true（实时）：全量刷新轨迹 + 相机跟随最后位置 + 蓝点驱动 — 主页录制
 */
@Composable
fun MapTrackView(
    pts: List<TrackPt>,
    speeds: List<Float>,
    maxSpeed: Double,
    waypoints: List<WpPt>,
    modifier: Modifier = Modifier,
    startSatellite: Boolean = false,
    follow: Boolean = false,
    layersCorner: Alignment = Alignment.TopEnd,
    showLocBtn: Boolean = false,
    presetMarkers: Boolean = false,
    plannedPath: List<TrackPt>? = null,
    fullscreen: Boolean = false,
    startAtUser: Boolean = false
) {
    val ctx = LocalContext.current
    // 图层模式：0 标准(CARTO) / 1 卫星(Esri) / 2 徒步等高线(OpenTopoMap) / 3 骑行(CyclOSM)
    var layerMode by remember { mutableStateOf(if (startSatellite) 1 else 0) }
    var styleReady by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    // MapLibre 实例必须先于 MapView 构造初始化（幂等）
    val mv = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx)
    }

    // 最新数据引用：style 异步就绪回调里直接推当前数据（防"数据只随组合推一次、style 后到"的空轨迹时序）
    val curPts by rememberUpdatedState(pts)
    val curSpeeds by rememberUpdatedState(speeds)
    val curMax by rememberUpdatedState(maxSpeed)
    val curWps by rememberUpdatedState(waypoints)
    val curPlanned by rememberUpdatedState(plannedPath)
    val curPreset by rememberUpdatedState(presetMarkers)

    // 数据指纹去重：宿主页面高频重组（传感器/气压刷新 LiveState）会让 pts 的 List 实例
    // 每次重组都重建，effect 反复重启 → setGeoJson 高频失效，几千段的轨迹线永远画不完一帧
    // （打卡 marker 个位数 feature 渲染快所以可见 = "只有标没有线"）。指纹不变就跳过重推。
    var lastSig by remember { mutableStateOf("") }

    // v0.8.42 拖动守卫：setGeoJson 的 GeoJSON 解析/上传与手势抢主线程 = 拖动掉帧卡顿
    // （"拖动断开、隔几秒恢复"）。拖动中暂停数据重推，didChange 时 effect 补推一次。
    // 注意监听器挂在 MapView（mv）上，MapLibreMap 没有这组 API。
    var camMoving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        mv.addOnCameraIsChangingListener { camMoving = true }
        mv.addOnCameraDidChangeListener { camMoving = false }
    }

    // 生命周期直推：组合即 onCreate/onStart/onResume（宿主 lifecycle 已 RESUMED 时
    // 不重放历史事件，观察者模式会漏 onCreate → 详情页地图永不初始化全白）；离开组合才销毁
    DisposableEffect(Unit) {
        mv.onCreate(Bundle())
        mv.onStart()
        mv.onResume()
        onDispose {
            mv.onPause()
            mv.onStop()
            mv.onDestroy()
        }
    }

    AndroidView(factory = { mv }, modifier = Modifier.fillMaxSize())

    // style 装配（一次）：sources/layers/images/定位组件
    LaunchedEffect(Unit) {
        AppLog.i("Track", "getMapAsync requesting")
        mv.getMapAsync { m ->
            AppLog.i("Track", "map ready, requesting style")
            map = m
            // 样式/瓦片加载失败监听：静默失败 = 整页无瓦片（v0.8.39 诊断埋点；挂在 MapView 上）
            mv.addOnDidFailLoadingMapListener { err ->
                AppLog.e("Track", "style/tile load FAILED: $err", null)
            }
            mv.addOnDidFinishLoadingMapListener {
                AppLog.i("Track", "style/tile finished loading")
            }
            // 关 MapLibre 内置浮层钮：正北罗盘钮（与右上图层钮重叠）、attribution ⓘ / logo
            // （右下角落出屏/与定位钮打架）；浮层按钮统一用本组件自绘圆钮
            m.uiSettings.apply {
                isCompassEnabled = false
                isAttributionEnabled = false
                isLogoEnabled = false
            }
            // 放大时预取父级瓦片（加速缩放浏览的出图速度）
            try { m.setPrefetchesTiles(true) } catch (_: Exception) {}
            m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { st ->
                // 整体 try：装配中途抛异常会被 native 侧吞掉（不崩溃、无日志、地图半初始化）
                try {
                style = st
                // ① 彩虹轨迹（逐段 Feature 带 color 属性，data-driven lineColor）
                st.addSource(GeoJsonSource("track-src"))
                st.addLayer(
                    LineLayer("track-line", "track-src").withProperties(
                        lineColor(Expression.get("color")),
                        lineWidth(5f),
                        lineJoin("round"),
                        lineCap("round")
                    )
                )
                // ② 规划路线：橙主线 + 节点示意淡青线
                st.addSource(GeoJsonSource("route-src"))
                st.addLayer(
                    LineLayer("route-line", "route-src").withProperties(
                        lineColor(Expression.literal("#FF6D00")),
                        lineWidth(6f),
                        lineJoin("round"),
                        lineCap("round")
                    )
                )
                st.addSource(GeoJsonSource("hint-src"))
                st.addLayer(
                    LineLayer("hint-line", "hint-src").withProperties(
                        lineColor(Expression.literal("#2DD4BF")),
                        lineWidth(2.5f),
                        lineCap("round")
                    )
                )
                // ③ 打卡点/起终点/预置节点（SymbolLayer + 位图图标）
                st.addSource(GeoJsonSource("wp-src"))
                st.addImage("wp-blue", dotBitmap(0xFF29B6F6.toInt()))
                st.addImage("wp-red", dotBitmap(0xFFEF5350.toInt()))
                st.addImage("wp-node", dotBitmap(0xFFFF6D00.toInt()))
                st.addImage("wp-yellow", dotBitmap(0xFFFFD54F.toInt()))
                st.addImage("wp-cyan", dotBitmap(0xFF26C6DA.toInt()))
                st.addImage("wp-green", dotBitmap(0xFF9CCC65.toInt()))
                st.addImage("wp-pink", dotBitmap(0xFFEC407A.toInt()))
                st.addLayer(
                    SymbolLayer("wp-sym", "wp-src").withProperties(
                        iconImage(Expression.get("icon")),
                        iconSize(1f),
                        iconAnchor(Expression.literal("bottom")),
                        textField(Expression.get("label")),
                        textSize(11f),
                        // Liberty 明亮底图默认深字白晕（两主题下都可读）；切卫星时联动反转
                        textColor(Expression.literal("#1F2937")),
                        textHaloColor(Expression.literal("#FFFFFF"))
                    )
                )
                // ④ 栅格图层（卫星/徒步等高线/骑行，默认隐藏）：一律 addLayerBelow 压到轨迹线之下，
                // 底图永不会盖住轨迹与打卡点（此前卫星层叠在轨迹上方，切卫星即吞掉轨迹线）
                st.addSource(RasterSource("sat-src", TileSet("sat", SAT_TILE), 256))
                st.addLayerBelow(
                    RasterLayer("sat-layer", "sat-src").withProperties(visibility("none")),
                    "track-line"
                )
                st.addSource(RasterSource("topo-src", TileSet("topo", *TOPO_TILES), 256))
                st.addLayerBelow(
                    RasterLayer("topo-layer", "topo-src").withProperties(visibility("none")),
                    "track-line"
                )
                st.addSource(RasterSource("cyclo-src", TileSet("cyclo", *CYCLO_TILES), 256))
                st.addLayerBelow(
                    RasterLayer("cyclo-layer", "cyclo-src").withProperties(visibility("none")),
                    "track-line"
                )
                // ⑤ 定位蓝点（默认引擎 = 系统 LocationManager，不依赖 GMS）
                try {
                    val lc = m.locationComponent
                    val opts = LocationComponentOptions.builder(ctx).pulseEnabled(true).build()
                    lc.activateLocationComponent(
                        LocationComponentActivationOptions.builder(ctx, st)
                            .locationComponentOptions(opts)
                            .build()
                    )
                    lc.isLocationComponentEnabled = true
                    lc.renderMode = RenderMode.COMPASS
                } catch (_: Exception) {
                    // 无定位权限：静默降级无蓝点
                }
                // style 就绪瞬间直接落当前数据（修复轨迹详情页只出打卡标、不出轨迹线的时序）
                val sig = sigOf(curPts, curSpeeds, curWps, curPlanned, curPreset)
                if (sig != lastSig) {
                    lastSig = sig
                    AppLog.i("Track", "styleReady apply pts=${curPts.size} speeds=${curSpeeds.size} " +
                        "wps=${curWps.size} planned=${curPlanned?.size ?: 0}")
                    applyTrackData(st, m, curPts, curSpeeds, curMax, curWps, curPlanned, curPreset, fit = true)
                }
                } catch (e: Exception) {
                    AppLog.e("Track", "style assemble failed", e)
                } finally {
                    styleReady = true
                }
            }
        }
    }

    // 诊断 watchdog：5s 后 style 仍未就绪 = 渲染管线死亡（surface 未建 / GL 死），把现场写入日志
    LaunchedEffect(Unit) {
        delay(5000)
        if (!styleReady) {
            val cam = try { map?.cameraPosition.toString() } catch (_: Exception) { "n/a" }
            AppLog.w(
                "Track", "watchdog 5s: styleReady=false map=${map != null} " +
                "mv=${mv.width}x${mv.height} shown=${mv.isShown} cam=$cam"
            )
        }
    }

    // 图层模式切换（卫星影像偏暗：路点标签同步反转成白字深晕；等高线/骑行底图偏亮维持深字）
    LaunchedEffect(layerMode, styleReady) {
        fun vis(id: String, on: Boolean) {
            style?.getLayer(id)?.setProperties(visibility(if (on) "visible" else "none"))
        }
        vis("sat-layer", layerMode == 1)
        vis("topo-layer", layerMode == 2)
        vis("cyclo-layer", layerMode == 3)
        style?.getLayer("wp-sym")?.setProperties(
            textColor(Expression.literal(if (layerMode == 1) "#E8ECF4" else "#1F2937")),
            textHaloColor(Expression.literal(if (layerMode == 1) "#0D1117" else "#FFFFFF"))
        )
    }

    // 全屏打开时先定位到当前位置（避免从默认视角干等）
    LaunchedEffect(styleReady) {
        if (!styleReady || !startAtUser) return@LaunchedEffect
        try {
            val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
                    as android.location.LocationManager
            val best = listOf("gps", "network", "passive", "fused")
                .mapNotNull { n -> try { lm.getLastKnownLocation(n) } catch (_: Exception) { null } }
                .filterNotNull().maxByOrNull { it.time }
            best?.let {
                map?.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 13.5)
                )
            }
        } catch (_: Exception) {
        }
    }

    // ---------- 数据绘制 ----------
    if (!follow) {
        // 回放：全量重画 + fit（指纹不变跳过——宿主重组风暴不再反复失效轨迹源；拖动中暂停）
        LaunchedEffect(styleReady, camMoving, pts, speeds, waypoints, plannedPath) {
            val st = style ?: return@LaunchedEffect
            if (!styleReady || camMoving) return@LaunchedEffect
            val sig = sigOf(pts, speeds, waypoints, plannedPath, presetMarkers)
            if (sig == lastSig) return@LaunchedEffect
            lastSig = sig
            applyTrackData(st, map, pts, speeds, maxSpeed, waypoints, plannedPath, presetMarkers, fit = true)
        }
    } else {
        // 实时：全量刷新 + 相机跟随 + 蓝点（拖动中暂停，didChange 后补推）
        LaunchedEffect(styleReady, camMoving, pts, speeds, waypoints) {
            val st = style ?: return@LaunchedEffect
            if (!styleReady || camMoving || pts.size < 2) return@LaunchedEffect
            val sig = sigOf(pts, speeds, waypoints, null, presetMarkers)
            if (sig == lastSig) return@LaunchedEffect
            lastSig = sig
            applyTrackData(st, map, pts, speeds, maxSpeed, waypoints, null, presetMarkers, fit = false)
        }
        LaunchedEffect(pts.size, styleReady) {
            if (!styleReady || pts.isEmpty()) return@LaunchedEffect
            val last = pts.last()
            val m = map ?: return@LaunchedEffect
            try {
                m.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(last.lat, last.lon), m.cameraPosition.zoom
                    )
                )
            } catch (_: Exception) {}
        }
    }

    // 浮层按钮：统一 44dp 半透明圆钮（细描边，激活态电光青）
    var locFlash by remember { mutableStateOf(false) }
    LaunchedEffect(locFlash) { if (locFlash) { delay(1600); locFlash = false } }
    // 两段式定位：第 1 按跳到我的位置，第 2 按保持位置回正北（罗盘模式常把地图转歪，一键摆正）
    var locStage by remember { mutableStateOf(0) }
    LaunchedEffect(locStage) { if (locStage != 0) { delay(4000); locStage = 0 } }
    // 图层循环：标准→卫星→徒步→骑行，切换后模式名 pill 提示 1.2s 自灭
    var modeFlash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(modeFlash) { if (modeFlash != null) { delay(1200); modeFlash = null } }
    val darkUi = isDark()
    Box(modifier) {
        // 定位动作（复用）：相机跳到最近位置 + 青色闪烁反馈
        val doLocate = {
            locFlash = true
            if (locStage == 0) {
                locStage = 1
                try {
                    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
                            as android.location.LocationManager
                    val best = listOf("gps", "network", "passive", "fused")
                        .mapNotNull { n -> try { lm.getLastKnownLocation(n) } catch (_: Exception) { null } }
                        .filterNotNull().maxByOrNull { it.time }
                    best?.let {
                        map?.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16.0)
                        )
                    }
                } catch (_: Exception) {
                }
            } else {
                locStage = 0
                try {
                    map?.let { mm ->
                        mm.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder(mm.cameraPosition).bearing(0.0).build()
                            )
                        )
                    }
                } catch (_: Exception) {
                }
            }
            Unit
        }
        val cycleLayers = {
            layerMode = (layerMode + 1) % LAYER_MODES.size
            modeFlash = LAYER_MODES[layerMode]
            Unit
        }
        if (fullscreen) {
            // 全屏：图层钮左上（模式名 pill 跟在其右侧）；定位箭头钮在右侧中部偏下
            MapCircleBtn(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp),
                icon = "layers", active = layerMode != 0, onClick = cycleLayers
            )
            modeFlash?.let { name ->
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 62.dp, top = 10.dp)
                        .background(
                            if (darkUi) Color(0xFF0D1117).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.86f),
                            RoundedCornerShape(22.dp)
                        )
                        .border(
                            1.dp,
                            if (darkUi) Color.White.copy(alpha = 0.30f) else Color(0xFF0D1117).copy(alpha = 0.22f),
                            RoundedCornerShape(22.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Text(
                        name, fontSize = 13.sp,
                        color = if (darkUi) Color.White.copy(alpha = 0.92f) else Color(0xFF1F2937)
                    )
                }
            }
            if (showLocBtn) {
                MapCircleBtn(
                    modifier = Modifier.align(Alignment.CenterEnd).offset(y = 96.dp).padding(end = 10.dp),
                    icon = "locate", active = locFlash, onClick = doLocate
                )
            }
        } else {
            // 常规：图层跟随 layersCorner，定位右下
            MapCircleBtn(
                modifier = Modifier.align(layersCorner).padding(10.dp),
                icon = "layers", active = layerMode != 0, onClick = cycleLayers
            )
            if (showLocBtn) {
                MapCircleBtn(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    icon = "locate", active = locFlash, onClick = doLocate
                )
            }
        }
    }
}

/** 地图浮层圆钮：44dp 半透明圆 + 线性图标（layers=三层菱形 / locate=准星），激活态电光青；明暗主题自适应 */
@Composable
private fun MapCircleBtn(icon: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val darkUi = isDark()
    val bg = if (darkUi) Color(0xFF0D1117).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.86f)
    val line = if (active) AvColors.cyan
    else if (darkUi) Color.White.copy(alpha = 0.30f) else Color(0xFF0D1117).copy(alpha = 0.22f)
    val c = when {
        active -> AvColors.cyan
        darkUi -> Color.White.copy(alpha = 0.92f)
        else -> Color(0xFF1F2937)
    }
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        DrawCanvas(Modifier.size(21.dp)) {
            val w = 1.7.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            if (icon == "layers") {
                // 双层菱形全描边（v0.8.36：去实心块，暗色主题下不再读作黑色实心团）
                fun layer(dy: Float) {
                    val p = Path().apply {
                        moveTo(cx, cy - size.height * 0.24f + dy)
                        lineTo(cx + size.width * 0.38f, cy + dy)
                        lineTo(cx, cy + size.height * 0.24f + dy)
                        lineTo(cx - size.width * 0.38f, cy + dy)
                        close()
                    }
                    drawPath(p, c, style = Stroke(w, join = StrokeJoin.Round))
                }
                layer(-size.height * 0.13f)
                layer(size.height * 0.13f)
            } else {
                // 定位箭头（导航飞镖样式，v0.8.36 替换旧准星）
                val r = size.minDimension * 0.42f
                val p = Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx + r * 0.85f, cy + r * 0.9f)
                    lineTo(cx, cy + r * 0.38f)
                    lineTo(cx - r * 0.85f, cy + r * 0.9f)
                    close()
                }
                drawPath(p, c)
            }
        }
    }
}

/** 数据指纹：点数 + 首/中/尾坐标（结构性判同，实例无关——重组风暴不再触发重推） */
private fun sigOf(
    pts: List<TrackPt>, speeds: List<Float>, wps: List<WpPt>,
    planned: List<TrackPt>?, preset: Boolean
): String {
    val focus = planned?.takeIf { it.size >= 2 } ?: pts
    val shape = if (focus.isEmpty()) "empty" else buildString {
        append("%.5f,%.5f".format(focus.first().lat, focus.first().lon))
        append("|%.5f,%.5f".format(focus[focus.size / 2].lat, focus[focus.size / 2].lon))
        append("|%.5f,%.5f".format(focus.last().lat, focus.last().lon))
    }
    return "${pts.size}|${speeds.size}|${wps.size}|${planned?.size ?: -1}|$preset|$shape"
}

/** 数据落地：轨迹/规划线/打卡点一次性推入 style 源（fit=true 顺带 fit 全程）。
 *  style 就绪回调与数据变化的 LaunchedEffect 共用同一条路径，消除双轨时序。 */
private fun applyTrackData(
    st: Style, map: MapLibreMap?,
    pts: List<TrackPt>, speeds: List<Float>, maxSpeed: Double,
    waypoints: List<WpPt>, plannedPath: List<TrackPt>?, preset: Boolean, fit: Boolean
) {
    try {
        if (plannedPath != null && plannedPath.size >= 2) {
            (st.getSource("route-src") as? GeoJsonSource)?.setGeoJson(
                lineFeature(plannedPath, null)
            )
            // 预置路线：节点平滑连线一条主线，示意连线清空（双线易混淆）
            (st.getSource("hint-src") as? GeoJsonSource)?.setGeoJson(
                FeatureCollection.fromFeatures(emptyList())
            )
        } else if (pts.size >= 2) {
            val col = trackFeatures(pts, speeds, maxSpeed)
            (st.getSource("track-src") as? GeoJsonSource)?.setGeoJson(col)
            AppLog.i("Track", "track-src set pts=${pts.size} feats=${col.features()?.size ?: 0} " +
                "src=${st.getSource("track-src") != null}")
        } else {
            AppLog.w("Track", "apply: pts=${pts.size}（<2，不出轨迹线）")
        }
        (st.getSource("wp-src") as? GeoJsonSource)?.setGeoJson(wpFeatures(st, pts, waypoints, preset))
        val focus = if (plannedPath != null && plannedPath.size >= 2) plannedPath else pts
        if (fit && focus.size >= 2) fitAll(map, focus)
    } catch (e: Exception) {
        AppLog.e("Track", "applyTrackData failed pts=${pts.size}", e)
    }
}

/** 轨迹彩虹分段：每 2 点一段 Feature，color 属性 = 该段起点速度色（与分享图同一套定标）。
 *  长轨迹 >1200 点按步长抽稀（保首尾）：几千段 GeoJSON 每次重推都整包重解析，
 *  配合高频重组曾是轨迹线永远画不出来的元凶之一。 */
private fun trackFeatures(pts: List<TrackPt>, speeds: List<Float>, maxSpeed: Double): FeatureCollection {
    var src = pts
    var spd = speeds
    if (pts.size > 1200) {
        val step = Math.ceil(pts.size / 1200.0).toInt()
        val p2 = ArrayList<TrackPt>(pts.size / step + 2)
        val s2 = ArrayList<Float>(pts.size / step + 2)
        var i = 0
        while (i < pts.size) {
            p2.add(pts[i])
            if (spd.size == pts.size) s2.add(spd[i])
            i += step
        }
        if (p2.lastOrNull() !== pts.last()) {
            p2.add(pts.last())
            if (spd.size == pts.size) s2.add(spd.last())
        }
        src = p2
        spd = s2
    }
    val feats = ArrayList<Feature>(src.size)
    if (spd.size == src.size) {
        for (i in 0 until src.size - 1) {
            val c = AvColors.rainbow(spd[i].toDouble(), maxSpeed)
            feats.add(
                lineFeature2(src[i], src[i + 1], String.format("#%06X", c.toArgb() and 0xFFFFFF))
            )
        }
    } else {
        // 无速度数据（预置路线/示意连线）：平滑后的单一亮橙线
        feats.add(lineFeature(smoothTrack(src), "#FF6D00"))
    }
    return FeatureCollection.fromFeatures(feats)
}

/** 打卡点 / 起终点 / 预置节点 → SymbolLayer features */
private fun wpFeatures(
    st: Style?, pts: List<TrackPt>, waypoints: List<WpPt>, preset: Boolean
): FeatureCollection {
    val feats = ArrayList<Feature>()
    fun add(lat: Double, lon: Double, icon: String, label: String?) {
        val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        f.addStringProperty("icon", icon)
        label?.let { f.addStringProperty("label", it) }
        feats.add(f)
    }
    waypoints.forEachIndexed { i, wp ->
        if (preset) {
            // 预置路线：节点橙点（终点也标），label 恒显示
            add(wp.lat, wp.lon, "wp-node", wp.label)
        } else {
            val photoIcon = wp.photo?.let { photoMarkerBitmap(it) }
            if (photoIcon != null) {
                val key = "wp-photo-$i"
                st?.addImage(key, photoIcon)
                add(wp.lat, wp.lon, key, null)
            } else {
                val icon = when (wp.kind) {
                    "M" -> "wp-yellow"; "K" -> "wp-cyan"; "C" -> "wp-green"; else -> "wp-pink"
                }
                add(wp.lat, wp.lon, icon, null)
            }
        }
    }
    if (pts.isNotEmpty() && !preset) {
        add(pts.first().lat, pts.first().lon, "wp-blue", null)
        add(pts.last().lat, pts.last().lon, "wp-red", null)
    }
    return FeatureCollection.fromFeatures(feats)
}

private fun lineFeature(pts: List<TrackPt>, color: String?): Feature {
    val f = Feature.fromGeometry(
        LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })
    )
    color?.let { f.addStringProperty("color", it) }
    return f
}

private fun lineFeature2(a: TrackPt, b: TrackPt, color: String): Feature {
    val f = Feature.fromGeometry(
        LineString.fromLngLats(listOf(Point.fromLngLat(a.lon, a.lat), Point.fromLngLat(b.lon, b.lat)))
    )
    f.addStringProperty("color", color)
    return f
}

/** fit 全程 */
private fun fitAll(map: MapLibreMap?, pts: List<TrackPt>) {
    if (map == null) {
        AppLog.w("Track", "fitAll: map not ready")
        return
    }
    if (pts.isEmpty()) return
    val b = LatLngBounds.Builder()
    pts.forEach { b.include(LatLng(it.lat, it.lon)) }
    try {
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80))
    } catch (e: Exception) {
        AppLog.e("Track", "fitAll failed n=${pts.size} " +
            "first=${pts.first().lat},${pts.first().lon} last=${pts.last().lat},${pts.last().lon}", e)
    }
}

/** 圆点图标（起终点/色标打卡） */
private fun dotBitmap(color: Int): Bitmap {
    val size = 40
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    c.drawCircle(size / 2f, size / 2f, size / 3f, p)
    val w = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = 0xFFFFFFFF.toInt(); strokeWidth = 4f
    }
    c.drawCircle(size / 2f, size / 2f, size / 3f, w)
    return bmp
}

/** 打卡照片缩略图 marker：居中裁方 + 圆角 + 白描边（96px，加载失败回退色标） */
private fun photoMarkerBitmap(path: String): Bitmap? = try {
    val f = java.io.File(path)
    if (!f.exists()) null
    else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample + 1) >= 128 || bounds.outHeight / (sample + 1) >= 128) sample *= 2
        val src = android.graphics.BitmapFactory.decodeFile(
            path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val w = src.width; val h = src.height
        val side = minOf(w, h)
        val square = Bitmap.createBitmap(src, (w - side) / 2, (h - side) / 2, side, side)
        val size = 96
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = 16f
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.FILL
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, paint)
        paint.shader = android.graphics.BitmapShader(
            scaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
        )
        c.drawRoundRect(5f, 5f, (size - 5).toFloat(), (size - 5).toFloat(), r * 0.8f, r * 0.8f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(2f, 2f, (size - 2).toFloat(), (size - 2).toFloat(), r, r, paint)
        out
    }
} catch (_: Exception) {
    null
}

/** Catmull-Rom 平滑插值：稀疏路线点圆滑过渡（预置路线防直线生硬） */
private fun smoothTrack(pts: List<TrackPt>, per: Int = 12): List<TrackPt> {
    if (pts.size < 3) return pts
    val out = ArrayList<TrackPt>(pts.size * per)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[(i - 1).coerceAtLeast(0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[(i + 2).coerceAtMost(pts.size - 1)]
        for (j in 0 until per) {
            val t = j.toDouble() / per
            val t2 = t * t
            val t3 = t2 * t
            val lat = 0.5 * ((2 * p1.lat) + (-p0.lat + p2.lat) * t +
                    (2 * p0.lat - 5 * p1.lat + 4 * p2.lat - p3.lat) * t2 +
                    (-p0.lat + 3 * p1.lat - 3 * p2.lat + p3.lat) * t3)
            val lon = 0.5 * ((2 * p1.lon) + (-p0.lon + p2.lon) * t +
                    (2 * p0.lon - 5 * p1.lon + 4 * p2.lon - p3.lon) * t2 +
                    (-p0.lon + 3 * p1.lon - 3 * p2.lon + p3.lon) * t3)
            out.add(TrackPt(lat, lon))
        }
    }
    out.add(pts.last())
    return out
}
