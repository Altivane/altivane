@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.altivane.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.domain.AppLog
import com.altivane.app.domain.CardType
import com.altivane.app.domain.Coach
import com.altivane.app.domain.LiveState
import com.altivane.app.domain.Phase
import com.altivane.app.domain.RidePoint
import com.altivane.app.domain.RideStats
import com.altivane.app.domain.SportType
import com.altivane.app.domain.TimedVal
import com.altivane.app.domain.TimedValF
import com.altivane.app.service.RideRecordingService
import com.altivane.app.tts.CoachTts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val PREF = "altivane_ui"

/** 每种运动的默认看板（主卡之后） */
fun defaultCards(sport: SportType): List<CardType> = when (sport) {
    SportType.RIDE -> listOf(
        CardType.DISTANCE, CardType.DURATION, CardType.TRACK,
        CardType.HR, CardType.CAD, CardType.CALS,
        CardType.ALTITUDE, CardType.COMPASS, CardType.BARO
    )
    SportType.HIKE -> listOf(
        CardType.PACE, CardType.DISTANCE, CardType.TRACK,
        CardType.DURATION, CardType.CALS,
        CardType.ALTITUDE, CardType.COMPASS, CardType.BARO
    )
}

fun loadCards(ctx: Context, sport: SportType): List<CardType> {
    // 历史配置兼容：剔除已下线的卡型（海拔剖面 / 最高时速）
    val removed = setOf(CardType.PROFILE, CardType.MAX_SPEED)
    val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString("cards_${sport.name}", null)
        ?: return defaultCards(sport).filter { it !in removed }
    // 严格按保存顺序还原：编辑页的排序/移除必须原样生效
    return raw.split(",")
        .mapNotNull { n -> CardType.entries.firstOrNull { it.name == n } }
        .filter { it !in removed }
        .distinct()
}

fun saveCards(ctx: Context, sport: SportType, order: List<CardType>) {
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString("cards_${sport.name}", order.joinToString(",") { it.name }).apply()
}

/** 每卡一色，色不重复 */
fun accentOf(t: CardType): Color = when (t) {
    CardType.DISTANCE -> AvColors.blue
    CardType.DURATION -> AvColors.cyan
    CardType.PACE -> AvColors.yellow
    CardType.HR -> AvColors.pink
    CardType.CAD -> AvColors.purple
    CardType.CALS -> AvColors.orange
    CardType.MAX_SPEED -> AvColors.red
    CardType.COMPASS -> AvColors.teal
    CardType.BARO -> AvColors.violet
    CardType.ALTITUDE -> Color(0xFFA78BFA)   // 薰衣草紫（高度专属）
    CardType.TRACK -> AvColors.green
    CardType.PROFILE -> AvColors.fuchsia
}

fun cardValue(t: CardType, s: LiveState): Pair<String, String> = when (t) {
    CardType.DISTANCE -> "%.2f".format(s.distanceM / 1000.0) to "km"
    CardType.DURATION -> {
        // 数字冒号格式（1:23:45 / 5:32 / 0:00），与其他卡数字风格对齐，无中文单位
        val sec = s.movingMs / 1000
        val h = sec / 3600
        (if (h > 0) "%d:%02d:%02d".format(h, (sec % 3600) / 60, sec % 60)
        else "%d:%02d".format(sec / 60, sec % 60)) to ""
    }
    CardType.PACE -> Coach.paceText(s.paceMinPerKm) to "min/km"
    CardType.HR -> (if (s.hr > 0) s.hr.toString() else "--") to "次/分"
    CardType.CAD -> (if (s.cad >= 0) s.cad.toString() else "--") to "rpm"
    CardType.CALS -> s.kcal.toInt().toString() to "kcal"
    CardType.MAX_SPEED -> "%.1f".format(s.maxKmh) to "km/h"
    CardType.COMPASS -> "%.0f".format(s.headingDeg) to "°"
    // 气压卡：只显示气压（hPa）；设备无气压计走空态说明，不再回退 GPS 海拔
    CardType.BARO -> when {
        !s.hasBaro -> "--" to "无气压计"
        s.pressureHpa > 0 -> "%.1f".format(s.pressureHpa) to "hPa"
        else -> "--" to ""
    }
    // 高度卡：徒步=气压计海拔（起点GPS校准+气压追踪），骑行=相对起点高程；无气压回退 GPS
    CardType.ALTITUDE -> when (s.sport) {
        SportType.HIKE -> when {
            s.altBaroM != 0.0 -> "%.0f".format(s.altBaroM) to "m 海拔"
            s.altM > 0.0 -> "%.0f".format(s.altM) to "m 海拔"
            else -> "--" to "m"
        }
        SportType.RIDE -> when {
            s.baseAltSet -> "%.0f".format(s.relAltM) to "m 相对"
            else -> "--" to "m"
        }
    }
    CardType.TRACK -> "" to ""
    CardType.PROFILE -> "" to ""
}

/** 小卡专属图表：按卡型映射图型与数据（无数据返回 null 回退纯数值）。
 *  注意：蓝牙心率样本可与轨迹点独立存在（未开始运动 pts 为空），
 *  因此除 HR/CAD 外的所有分支都必须对空 pts 防御，否则 NoSuchElementException 闪退 */
fun chartFor(
    t: CardType,
    pts: List<RidePoint>,
    sport: SportType = SportType.HIKE,
    hrS: List<TimedVal> = emptyList(),
    cadS: List<TimedVal> = emptyList(),
    pressS: List<TimedValF> = emptyList()
): CardChart? {
    if (pts.size < 2 && hrS.size < 2 && pressS.size < 2) return null
    return when (t) {
        // 心率：心电图式波动线（Q→R尖峰→S回落），mini 带数字档位标尺（可仅凭心率样本渲染）
        CardType.HR -> (hrS.map { it.v.toDouble() }.takeIf { it.size >= 2 }
            ?: pts.map { it.hr.toDouble() }.filter { it > 0 }.takeIf { it.size >= 2 })
            ?.let { CardChart(ChartKind.HR_WAVE, it) }
        // 配速：三色竖条（健康时速对照：骑行 紫≥40 绿25-40 黄<25；徒步 紫≥8 绿5-8 黄<5）
        CardType.PACE -> pts.takeIf { it.size >= 2 }?.let { p ->
            if (sport == SportType.RIDE)
                CardChart(ChartKind.PACE_BARS, p.map { it.speed * 3.6 }, yMax = 25f, gMax = 40f)
            else
                CardChart(ChartKind.PACE_BARS, p.map { it.speed * 3.6 }, yMax = 5f, gMax = 8f)
        }
        // 踏频：竖条图（优先独立样本）
        CardType.CAD -> (cadS.map { it.v.toDouble() }.takeIf { it.size >= 2 }
            ?: pts.map { it.cad.toDouble() }.filter { it >= 0 }.takeIf { it.size >= 2 })
            ?.let { CardChart(ChartKind.PACE_BARS, it) }
        // 里程：百米云梯（累计里程刻度杠，v0.8.43 满高统一）
        CardType.DISTANCE -> pts.takeIf { it.size >= 2 }
            ?.let { CardChart(ChartKind.LADDER, cumulativeKm(it)) }
        // 时长：时间方块（实心=移动/空心=停留/半透明=进行中，v0.8.43 满高统一）
        CardType.DURATION -> pts.takeIf { it.size >= 2 }?.let { p ->
            CardChart(
                ChartKind.TIME_BLOCKS, p.map { it.speed * 3.6 },
                durationMs = (p.last().t - p.first().t).coerceAtLeast(0L),
                timestamps = p.map { it.t }
            )
        }
        // 最高时速：三色竖条柱状图（黄慢/绿快/紫极限）
        CardType.MAX_SPEED -> pts.takeIf { it.size >= 2 }
            ?.let { CardChart(ChartKind.SPEED_BARS, it.map { pt -> pt.speed * 3.6 }) }
        // 气压：纯气压曲线（hPa 时序，天气趋势用）；无气压传感器不画（不回退 GPS 海拔）
        CardType.BARO -> pressS.map { it.v }.takeIf { it.size >= 2 }
            ?.let { CardChart(ChartKind.AREA, it) }
        // 高度：海拔面积图（运动轨迹点高程）
        CardType.ALTITUDE -> pts.map { it.ele }
            .takeIf { l -> l.size >= 2 && l.any { e -> e > 0.0 } }
            ?.let { CardChart(ChartKind.ELEV, it) }
        // 消耗：三竖柱（低/中/高强度 kcal）
        CardType.CALS -> pts.takeIf { it.size >= 2 }
            ?.let { CardChart(ChartKind.ENERGY, split = RideStats.kcalSplit(it, sport).toList()) }
        else -> null
    }
}

/** 相邻点 haversine 累计里程（km） */
private fun cumulativeKm(p: List<RidePoint>): List<Double> {    var acc = 0.0
    val out = ArrayList<Double>(p.size)
    for (i in p.indices) {
        if (i > 0) {
            val q = p[i - 1]; val pt = p[i]
            val la1 = Math.toRadians(q.lat); val la2 = Math.toRadians(pt.lat)
            val dla = la2 - la1
            val dlo = Math.toRadians(pt.lon - q.lon)
            val a = kotlin.math.sin(dla / 2).pow(2.0) +
                    kotlin.math.cos(la1) * kotlin.math.cos(la2) * kotlin.math.sin(dlo / 2).pow(2.0)
            acc += 2 * 6371000.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        }
        out.add(acc / 1000.0)
    }
    return out
}

private fun Double.pow(n: Double): Double = Math.pow(this, n)

/** 里程小时分桶：24 桶（0-23 时）每小时 km（段增量归属起点小时） */
private fun hourlyKmBuckets(p: List<RidePoint>): List<Double> {
    val out = DoubleArray(24)
    for (i in 1 until p.size) {
        val d = RideStats.haversine(p[i - 1].lat, p[i - 1].lon, p[i].lat, p[i].lon) / 1000.0
        if (d > 0.0) out[(((p[i - 1].t / 3600000L) % 24L)).toInt()] += d
    }
    return out.toList()
}

@Composable
fun RideScreen(
    app: RideApp,
    onStart: () -> Unit,
    onServiceAction: (String) -> Unit,
    onViewRide: (Long) -> Unit,
    onOpenCard: (CardType) -> Unit,
    onPhotoWaypoint: () -> Unit,
    onEditCards: () -> Unit,
    onOpenRoutes: () -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState =
        androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    // 全屏地图状态提升到 AppRoot（全屏时隐藏底部导航条，见 AppRoot）
    fullMap: Boolean = false,
    onFullMapChange: (Boolean) -> Unit = {}
) {
    val session = app.session
    val s by session.state.collectAsState()
    val ctx = LocalContext.current

    // 长按卡片菜单
    var menuCard by remember { mutableStateOf<CardType?>(null) }
    // 全屏地图开关 fullMap/onFullMapChange 由 AppRoot 持有（v0.8.39 提升：
    // 状态在本页时覆盖层盖不住 MainActivity 的底部玻璃条）

    var order by remember { mutableStateOf(loadCards(ctx, s.sport)) }
    LaunchedEffect(s.sport) { order = loadCards(ctx, s.sport) }

    // 实时轨迹点（轨迹卡 / 海拔图卡 / 徒步主卡）
    val ptsLive by session.points.collectAsState()
    // 独立心率/踏频样本时序（BLE 直收，静止被 GPS 过滤时图表仍有数据）
    val hrLive by session.hrSamples.collectAsState()
    val cadLive by session.cadSamples.collectAsState()
    val pressLive by session.pressureSamples.collectAsState()
    // 上次运动快照：按运动类型分存（骑/徒数据隔离），空闲态显示当前运动的快照
    val lastSnaps by session.lastSnapshot.collectAsState()
    val lastPtsSnaps by session.lastPoints.collectAsState()
    val lastSnap = lastSnaps[s.sport]
    val lastPtsSnap = lastPtsSnaps[s.sport] ?: emptyList()

    // 展示视图：录制中用实时，空闲用当前运动的快照（航向/气压/蓝牙心率踏频始终实时共享）
    val view = if (s.phase != Phase.IDLE) s
    else (lastSnap ?: s).copy(
        sport = s.sport,
        headingDeg = s.headingDeg, hasCompass = s.hasCompass, pressureHpa = s.pressureHpa,
        hr = if (s.hr > 0) s.hr else (lastSnap ?: s).hr,
        cad = if (s.cad >= 0) s.cad else (lastSnap ?: s).cad
    )
    val viewPts = if (s.phase != Phase.IDLE) ptsLive else lastPtsSnap

    // GO → 全屏 321 倒计时（点击覆盖层取消）
    var countdown by remember { mutableStateOf<Int?>(null) }
    // 定位服务总开关检查：进入页面即查，按 GO 再查一次（关着就弹窗引导，不进倒计时）
    var gpsOff by remember { mutableStateOf(false) }
    fun gpsEnabled(): Boolean = try {
        (ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
            .isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    } catch (_: Exception) { true }
    LaunchedEffect(Unit) { if (!gpsEnabled()) gpsOff = true }
    LaunchedEffect(countdown) {
        when (val c = countdown) {
            null -> {}
            else -> {
                if (c > 0) {
                    delay(1000)
                    countdown = c - 1
                } else {
                    delay(450)
                    countdown = null
                    onStart()
                }
            }
        }
    }

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(s.phase, s.startTime) {
        if (s.phase == Phase.IDLE) {
            elapsed = 0
        } else {
            var base = elapsed
            var last = System.currentTimeMillis()
            while (true) {
                kotlinx.coroutines.delay(500)
                val now = System.currentTimeMillis()
                if (s.phase == Phase.RECORDING) base += now - last
                last = now
                elapsed = base
            }
        }
    }

    KeepScreenOn(s.phase != Phase.IDLE)

    // 蓝牙外设未连接时隐藏对应卡
    val hrName by app.ble.hrName.collectAsState()
    val cadName by app.ble.cadName.collectAsState()
    val visible = order.filter {
        (it != CardType.HR || hrName.isNotEmpty()) &&
                (it != CardType.CAD || cadName.isNotEmpty())
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .padding(horizontal = 14.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            TopRow(s)
            if (s.phase == Phase.IDLE) SportChipsRow(s) { session.setSport(it) }
            if (s.phase != Phase.IDLE) BackgroundLocHint()

            // 按剩余空间自适应：主卡 1 行 + 方卡多行；超出部分滚动，底部渐变淡出避免"漏半行"的生硬感
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val gridH = maxHeight
                val smallH = ((gridH - 10.dp) / 2f).coerceIn(118.dp, 172.dp)
                val mainH = (gridH - smallH - 10.dp).coerceIn(150.dp, 232.dp)
                val bg = MaterialTheme.colorScheme.background
                Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 34.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(span = { GridItemSpan(2) }) { MainStatCard(view, s.phase, elapsed, mainH, viewPts, onOpenRoutes, onFullscreen = { onFullMapChange(true) }) }
                    items(visible, key = { it.name }) { t ->
                        val base = Modifier
                            .fillMaxWidth()
                            .height(smallH)
                            .combinedClickable(
                                onClick = { onOpenCard(t) },
                                onLongClick = { menuCard = t }
                            )
                        when (t) {
                            CardType.COMPASS -> CompassTile(view.headingDeg, base)
                            CardType.TRACK -> TrackTile(viewPts, view.sport, base)
                            CardType.PROFILE -> ProfileTile(viewPts, base)
                            else -> StatCard(
                                stringResource(t.labelRes), cardValue(t, view).first, cardValue(t, view).second, accentOf(t),
                                base, fill = true,
                                chart = chartFor(
                                    t, viewPts, view.sport,
                                    hrS = hrLive, cadS = cadLive, pressS = pressLive
                                ),
                                bleBadge = (t == CardType.HR && hrName.isNotEmpty()) ||
                                        (t == CardType.CAD && cadName.isNotEmpty())
                            )
                        }
                    }
                    }
                    // 底部淡出遮罩：滚动露出的半行卡片自然消隐
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(bg.copy(alpha = 0f), bg)
                                )
                            )
                    )
                }
            }

            ControlRow(
                s,
                onStart = {
                    if (!gpsEnabled()) gpsOff = true else countdown = 3   // GO → 321 倒计时 → 真正开始
                },
                onPhotoWaypoint = onPhotoWaypoint,
                onPause = { onServiceAction(RideRecordingService.ACTION_PAUSE) },
                onResume = { onServiceAction(RideRecordingService.ACTION_RESUME) },
                onStop = { onServiceAction(RideRecordingService.ACTION_STOP) }
            )
            Spacer(Modifier.height(4.dp))
        }

        // GO 后全屏 321 倒计时（透明覆盖层，点击取消）
        countdown?.let { c ->
            CountdownOverlay(c, view.sport) { countdown = null }
        }

        // 全屏地图（大卡下拉唤出，骑/徒共用）：根级覆盖层——Activity 已 enableEdgeToEdge，
        // 天然延伸到状态栏/刘海/系统导航栏之下；底部玻璃条由 AppRoot 在 fullMap 时隐藏。
        // 返回键 BackHandler 收掉
        if (fullMap) {
            BackHandler { onFullMapChange(false) }
            // 空闲态查 DB 取最近一次同运动类型的落库轨迹（快照 flow 可能未恢复，v0.8.39）；
            // 录制中直接用实时点
            val liveNow = s.phase != Phase.IDLE
            var dbPts by remember { mutableStateOf<List<RidePoint>>(emptyList()) }
            var dbMax by remember { mutableStateOf(0.0) }
            LaunchedEffect(fullMap, liveNow, s.sport) {
                if (fullMap && !liveNow) {
                    try {
                        val r = app.db.dao().rides().first().firstOrNull { rr ->
                            try { SportType.valueOf(rr.sportType) == s.sport } catch (_: Exception) { false }
                        }
                        dbPts = if (r == null) emptyList()
                        else app.db.dao().points(r.id).map {
                            RidePoint(it.t, it.lat, it.lon, it.ele, it.speed, it.hr, it.cad)
                        }
                        dbMax = r?.maxKmh ?: 0.0
                        AppLog.i("Track", "fullscreen db load pts=${dbPts.size} max=$dbMax")
                    } catch (e: Exception) {
                        AppLog.e("Track", "fullscreen db load failed", e)
                    }
                }
            }
            val overlaySrc = if (liveNow) ptsLive else dbPts
            // remember 稳定化：不随每次重组换新实例喂给地图 effect
            val overlayPts = remember(overlaySrc) { overlaySrc.map { TrackPt(it.lat, it.lon) } }
            val overlaySpeeds = remember(overlaySrc) { overlaySrc.map { it.speed } }
            val overlayMax = when {
                liveNow -> AvColors.rainbowMax(view.sport)
                dbMax > 0 -> dbMax
                else -> AvColors.rainbowMax(view.sport)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                MapTrackView(
                    pts = overlayPts,
                    speeds = overlaySpeeds,
                    maxSpeed = overlayMax,
                    waypoints = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                    showLocBtn = true,
                    fullscreen = true,
                    startAtUser = true
                )
            }
        }
    }

    // 定位服务未开启：弹窗引导（轨迹没数据多半是这个原因）
    if (gpsOff) {
        AlertDialog(
            onDismissRequest = { gpsOff = false },
            confirmButton = {
                TextButton(onClick = {
                    gpsOff = false
                    try {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (_: Exception) { }
                }) { Text("去开启", color = AvColors.cyan) }
            },
            dismissButton = { TextButton(onClick = { gpsOff = false }) { Text("取消") } },
            title = { Text("定位服务未开启") },
            text = { Text("GPS 总开关没打开，运动轨迹将不会记录。\n请到系统设置的定位服务里开启后再按 GO。") }
        )
    }

    // 卡片长按菜单：移除 / 进入编辑卡片
    menuCard?.let { t ->
        AlertDialog(
            onDismissRequest = { menuCard = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuCard = null }) { Text(stringResource(R.string.btn_cancel)) } },
            title = { Text(stringResource(R.string.fmt_card_tile, stringResource(t.labelRes))) },
            text = {
                Column {
                    TextButton(onClick = {
                        order = order.filter { it != t }
                        saveCards(ctx, s.sport, order)
                        menuCard = null
                    }) { Text(stringResource(R.string.btn_remove_card), color = AvColors.pink) }
                    TextButton(onClick = {
                        menuCard = null
                        onEditCards()
                    }) { Text(stringResource(R.string.btn_edit_cards)) }
                }
            }
        )
    }
}

@Composable
private fun TopRow(s: LiveState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AvColors.cyan)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Altivane",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.weight(1f))
        if (s.phase != Phase.IDLE) {
            val blink by rememberInfiniteTransition().animateFloat(
                0.25f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse)
            )
            Box(
                Modifier
                    .size(9.dp)
                    .alpha(if (s.phase == Phase.RECORDING) blink else 1f)
                    .clip(CircleShape)
                    .background(AvColors.pink)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (s.phase == Phase.RECORDING) "REC" else "PAUSED",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

/** 运动切换：两个等分占满整行 */
@Composable
private fun SportChipsRow(s: LiveState, onSelect: (SportType) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SportType.entries.forEach { st ->
            val sel = st == s.sport
            val accent = when (st) {
                SportType.RIDE -> AvColors.cyan
                SportType.HIKE -> AvColors.green
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (sel) accent else Color.Transparent)
                    .border(
                        1.dp,
                        if (sel) accent else accent.copy(alpha = 0.45f),
                        RoundedCornerShape(18.dp)
                    )
                    .clickable { onSelect(st) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                // 选中：文字前带运动图标 + 弹跳动效；未选中：纯文字
                val pop by animateFloatAsState(
                    if (sel) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.45f, stiffness = 640f),
                    label = "pop"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sel) {
                        Canvas(
                            Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = 0.5f + 0.5f * pop
                                    scaleY = 0.5f + 0.5f * pop
                                    alpha = pop
                                }
                        ) {
                            val ink = Color(0xFF0D1117)
                            if (st == SportType.HIKE) {
                                // 人形：头圆 + 躯干 + 迈步双腿 + 摆臂
                                val w = size.width; val h = size.height
                                drawCircle(ink, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.16f))
                                val body = Path().apply {
                                    moveTo(w * 0.52f, h * 0.30f)
                                    lineTo(w * 0.48f, h * 0.56f)
                                    moveTo(w * 0.50f, h * 0.36f); lineTo(w * 0.30f, h * 0.48f)
                                    moveTo(w * 0.50f, h * 0.36f); lineTo(w * 0.70f, h * 0.46f)
                                    moveTo(w * 0.48f, h * 0.56f); lineTo(w * 0.32f, h * 0.88f)
                                    moveTo(w * 0.48f, h * 0.56f); lineTo(w * 0.68f, h * 0.82f)
                                    lineTo(w * 0.74f, h * 0.94f)
                                }
                                drawPath(body, ink, style = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            } else {
                                // 自行车：双轮 + 车架 + 车把 + 座管
                                val w = size.width; val h = size.height
                                val r = w * 0.20f
                                drawCircle(ink, radius = r, center = Offset(w * 0.26f, h * 0.68f), style = Stroke(w * 0.08f))
                                drawCircle(ink, radius = r, center = Offset(w * 0.76f, h * 0.68f), style = Stroke(w * 0.08f))
                                val frame = Path().apply {
                                    moveTo(w * 0.26f, h * 0.68f); lineTo(w * 0.48f, h * 0.40f)
                                    lineTo(w * 0.66f, h * 0.68f); close()
                                    moveTo(w * 0.48f, h * 0.40f); lineTo(w * 0.56f, h * 0.40f)
                                    moveTo(w * 0.76f, h * 0.68f); lineTo(w * 0.66f, h * 0.68f)
                                    moveTo(w * 0.56f, h * 0.40f); lineTo(w * 0.60f, h * 0.34f)
                                    lineTo(w * 0.70f, h * 0.34f)
                                }
                                drawPath(frame, ink, style = Stroke(w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        stringResource(st.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (sel) Color(0xFF0D1117) else accent
                    )
                }
            }
        }
    }
}

/** 骑行主卡背景：平缓起伏山脊 + 双速度弧线 + 气流速度线（横向动感） */
@Composable
private fun RideBackdrop(accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // 远层：平缓长坡
        val p1 = Path().apply {
            moveTo(0f, h * 0.80f)
            lineTo(w * 0.22f, h * 0.68f); lineTo(w * 0.45f, h * 0.74f)
            lineTo(w * 0.68f, h * 0.62f); lineTo(w * 0.88f, h * 0.70f)
            lineTo(w, h * 0.66f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(p1, accent.copy(alpha = 0.07f))
        // 近层：缓坡路面
        val p2 = Path().apply {
            moveTo(0f, h * 0.94f)
            lineTo(w * 0.25f, h * 0.86f); lineTo(w * 0.55f, h * 0.90f)
            lineTo(w * 0.80f, h * 0.82f); lineTo(w, h * 0.88f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(p2, accent.copy(alpha = 0.13f))
        // 速度弧线（右上）
        drawArc(
            accent.copy(alpha = 0.22f), -60f, 95f, false,
            topLeft = Offset(w * 0.56f, h * 0.08f), size = Size(w * 0.42f, w * 0.42f),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            accent.copy(alpha = 0.12f), -45f, 75f, false,
            topLeft = Offset(w * 0.64f, h * 0.16f), size = Size(w * 0.26f, w * 0.26f),
            style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
        )
        // 气流速度线（左中，向右冲刺感）
        val dash = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))
        drawLine(
            accent.copy(alpha = 0.30f),
            Offset(w * 0.06f, h * 0.30f), Offset(w * 0.34f, h * 0.30f),
            strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash
        )
        drawLine(
            accent.copy(alpha = 0.20f),
            Offset(w * 0.02f, h * 0.40f), Offset(w * 0.22f, h * 0.40f),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash
        )
        drawLine(
            accent.copy(alpha = 0.14f),
            Offset(w * 0.10f, h * 0.20f), Offset(w * 0.30f, h * 0.20f),
            strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash
        )
    }
}

/** 徒步主卡背景：尖峰山脊 + 等高线环组 + 虚线登山小径 + 峰顶标记 */
@Composable
private fun HikeBackdrop(accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // 远层：三座尖峰
        val p1 = Path().apply {
            moveTo(0f, h * 0.82f)
            lineTo(w * 0.20f, h * 0.48f); lineTo(w * 0.34f, h * 0.68f)
            lineTo(w * 0.52f, h * 0.34f); lineTo(w * 0.70f, h * 0.60f)
            lineTo(w * 0.84f, h * 0.44f); lineTo(w, h * 0.72f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(p1, accent.copy(alpha = 0.07f))
        // 近层：山坡
        val p2 = Path().apply {
            moveTo(0f, h * 0.96f)
            lineTo(w * 0.18f, h * 0.74f); lineTo(w * 0.38f, h * 0.88f)
            lineTo(w * 0.60f, h * 0.70f); lineTo(w * 0.82f, h * 0.84f)
            lineTo(w, h * 0.78f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(p2, accent.copy(alpha = 0.12f))
        // 等高线环组（右上高地）
        val cc = Offset(w * 0.80f, h * 0.22f)
        listOf(w * 0.30f, w * 0.22f, w * 0.145f, w * 0.08f).forEachIndexed { i, rad ->
            drawCircle(
                accent.copy(alpha = 0.16f - i * 0.03f), radius = rad, center = cc,
                style = Stroke(1.5.dp.toPx())
            )
        }
        // 次级等高线（左中）
        val cc2 = Offset(w * 0.14f, h * 0.34f)
        drawCircle(accent.copy(alpha = 0.09f), radius = w * 0.12f, center = cc2, style = Stroke(1.2.dp.toPx()))
        drawCircle(accent.copy(alpha = 0.06f), radius = w * 0.07f, center = cc2, style = Stroke(1.2.dp.toPx()))
        // 虚线登山小径（左下 → 右上，之字形）
        val trail = Path().apply {
            moveTo(w * 0.08f, h * 0.92f)
            lineTo(w * 0.26f, h * 0.78f)
            lineTo(w * 0.20f, h * 0.64f)
            lineTo(w * 0.40f, h * 0.50f)
            lineTo(w * 0.34f, h * 0.38f)
            lineTo(w * 0.52f, h * 0.28f)
        }
        drawPath(
            trail, accent.copy(alpha = 0.34f),
            style = Stroke(
                2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))
            )
        )
        // 峰顶标记：两座小三角 + 顶点
        val m = Path().apply {
            moveTo(w * 0.47f, h * 0.40f)
            lineTo(w * 0.52f, h * 0.30f); lineTo(w * 0.57f, h * 0.40f)
            close()
        }
        drawPath(m, accent.copy(alpha = 0.30f), style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
        drawCircle(accent.copy(alpha = 0.55f), radius = 2.6.dp.toPx(), center = Offset(w * 0.52f, h * 0.27f))
    }
}

/** 指南针小卡：罗盘居中铺满整卡，航向数字在圆心 */
@Composable
private fun CompassTile(heading: Float, modifier: Modifier = Modifier) {
    val dark = isDark()
    Box(
        modifier
            .glassCard(dark, AvColors.teal)
            .padding(8.dp)
    ) {
        CompassDial(heading, Modifier.fillMaxSize(), showCenter = true)
    }
}

/** 实时轨迹扁卡：标题 + 剖面图（海拔线=真实比例×3 贴底，速度虚线=上方独立归一，与分享图抬升线同语义） */
@Composable
private fun TrackTile(pts: List<RidePoint>, sport: SportType, modifier: Modifier = Modifier) {
    val dark = isDark()
    Column(
        modifier
            .glassCard(dark, AvColors.green)
            .padding(12.dp)
    ) {
        CardTitleRow(stringResource(CardType.TRACK.labelRes), AvColors.green)
        Spacer(Modifier.height(6.dp))
        // v0.8.43 方案 B：彩虹足迹形状在上 + 海拔剪影在下 + 起蓝终红点（满高统一）
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TrackShape(
                pts.map { listOf(it.lat, it.lon) },
                pts.map { it.ele },
                Modifier.fillMaxSize()
            )
        }
    }
}

/** 海拔剖面扁卡：标题 + 立体渐变填充曲线 */
@Composable
private fun ProfileTile(pts: List<RidePoint>, modifier: Modifier = Modifier) {
    val dark = isDark()
    Column(
        modifier
            .glassCard(dark, AvColors.fuchsia)
            .padding(12.dp)
    ) {
        CardTitleRow(stringResource(CardType.PROFILE.labelRes), AvColors.fuchsia)
        Spacer(Modifier.height(6.dp))
        ElevationSpark(
            pts.map { it.ele },
            AvColors.onColor(AvColors.fuchsia),
            Modifier.fillMaxWidth().weight(1f)
        )
    }
}

@Composable
private fun MainStatCard(
    view: LiveState,
    phase: Phase,
    elapsedMs: Long,
    mainH: Dp,
    pts: List<RidePoint>,
    onOpenRoutes: () -> Unit,
    onFullscreen: () -> Unit = {}
) {
    val accent = when (view.sport) {
        SportType.RIDE -> AvColors.cyan
        SportType.HIKE -> AvColors.green
    }
    val dark = isDark()
    val fg = MaterialTheme.colorScheme.onBackground
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val isLive = phase != Phase.IDLE
    val durMs = if (isLive) elapsedMs else view.movingMs
    val showReady = phase == Phase.IDLE && view.rideId == 0L

    // 折叠状态按运动类型持久化（骑/徒独立，退出重开保持）
    val ctx = LocalContext.current
    var expanded by remember(view.sport) {
        mutableStateOf(
            !ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean("collapsed_${view.sport.name}", false)
        )
    }
    fun setExpanded(v: Boolean) {
        expanded = v
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("collapsed_${view.sport.name}", !v).apply()
    }
    val h by animateDpAsState(if (expanded) mainH else 62.dp, AvMotion.emphasized())

    Box(
        Modifier
            .fillMaxWidth()
            .height(h)
            .glassCard(dark, accent)
            // 大卡范围内下拉 → 全屏高德地图（骑/徒都支持）
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var mode = 0
                    var acc = 0f
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: break
                        val dy = ch.positionChange().y
                        if (mode == 0) {
                            if (dy > 8) mode = 1
                            else if (dy < -8) break
                        }
                        if (mode == 1) {
                            ch.consume()
                            acc += dy
                            if (acc > 240f) { onFullscreen(); break }
                        }
                        if (ch.changedToUp()) break
                    }
                }
            }
    ) {
        if (expanded) {
            when {
            showReady -> {
                if (view.sport == SportType.RIDE) RideBackdrop(accent) else HikeBackdrop(accent)
                Text(
                    "--",
                    Modifier.align(Alignment.Center),
                    fontSize = 72.sp, fontWeight = FontWeight.Black, color = dim
                )
            }
            else -> {
                // 数字主卡：录制中实时 / 空闲显示上次运动
                if (view.sport == SportType.RIDE) RideBackdrop(accent) else HikeBackdrop(accent)
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val big: String
                    val unit: String
                    val sub: String
                    when (view.sport) {
                        SportType.RIDE -> {
                            big = "%.1f".format(view.speedKmh); unit = "km/h"
                            sub = "均速 %.1f · 峰值 %.1f · %s".format(view.avgKmh, view.maxKmh, Coach.fmtDur(durMs))
                        }
                        SportType.HIKE -> {
                            big = Coach.paceText(view.paceMinPerKm); unit = "min/km"
                            sub = "时速 %.1f · %s".format(view.speedKmh, Coach.fmtDur(durMs))
                        }
                    }
                    Text(big, fontSize = if (isLive) 76.sp else 64.sp, fontWeight = FontWeight.Black,
                        color = fg, maxLines = 1)
                    Text(unit + "   $sub", style = MaterialTheme.typography.bodyMedium,
                        color = dim, maxLines = 1)
                }
            }
        }

        // 右上角：路线按钮（实心 accent 圆钮 + 路线图标）
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable(onClick = onOpenRoutes),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(16.dp)) {
                    // 蜿蜒路线：起点圆点 → S 弯 → 终点定位环
                    val w = size.width
                    val h = size.height
                    val ink = Color(0xFF0D1117)
                    val route = Path().apply {
                        moveTo(w * 0.16f, h * 0.86f)
                        cubicTo(w * 0.52f, h * 0.86f, w * 0.10f, h * 0.34f, w * 0.48f, h * 0.34f)
                        cubicTo(w * 0.86f, h * 0.34f, w * 0.46f, h * 0.10f, w * 0.84f, h * 0.14f)
                    }
                    drawPath(
                        route, ink,
                        style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawCircle(ink, radius = 2.8.dp.toPx(), center = Offset(w * 0.16f, h * 0.86f))
                    drawCircle(
                        ink, radius = 3.6.dp.toPx(), center = Offset(w * 0.84f, h * 0.14f),
                        style = Stroke(1.7.dp.toPx())
                    )
                }
            }

            // 右下角：收缩按钮（卡片变窄为信息条）
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                    .border(1.dp, dim.copy(alpha = 0.5f), CircleShape)
                    .clickable { setExpanded(false) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp, null,
                    tint = fg, modifier = Modifier.size(22.dp)
                )
            }
        } else {
            // 收缩态：单行信息条，点击展开
            Row(
                Modifier
                    .fillMaxSize()
                    .clickable { setExpanded(true) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(view.sport.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = fg
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (view.sport == SportType.RIDE)
                        "%.2f km · %.1f km/h · %s".format(
                            view.distanceM / 1000.0, view.speedKmh, Coach.fmtDur(durMs)
                        )
                    else
                        "%.2f km · %s".format(
                            view.distanceM / 1000.0, Coach.paceText(view.paceMinPerKm)
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown, null,
                    tint = fg, modifier = Modifier.size(22.dp)
                )
            }
        }
    }

}

@Composable
private fun ControlRow(
    s: LiveState,
    onStart: () -> Unit,
    onPhotoWaypoint: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (s.phase) {
            Phase.IDLE -> Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                GoButton(s.sport, onStart)
                WarmupButton(s.sport, Modifier.align(Alignment.CenterStart).padding(start = 30.dp))
            }
            Phase.RECORDING -> {
                if (s.sport == SportType.HIKE) {
                    CameraWaypointButton(onPhotoWaypoint)
                    Spacer(Modifier.width(16.dp))
                }
                StopButton(onStop)
                Spacer(Modifier.width(16.dp))
                RoundButton("暂停", false, 64, accent = MaterialTheme.colorScheme.onSurfaceVariant) { onPause() }
            }
            Phase.PAUSED -> {
                RoundButton("继续", false, 64, accent = AvColors.cyan) { onResume() }
                Spacer(Modifier.width(16.dp))
                StopButton(onStop)
            }
        }
    }
}

/** 拍照打卡钮：黑白自适应（透明底 + onBackground 图标 + 半透描边），与热身钮同风格 */
@Composable
private fun CameraWaypointButton(onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Box(
        Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .border(2.dp, ink.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(26.dp)) {
            val w = size.width
            val h = size.height
            // 机身圆角矩形
            drawRoundRect(
                ink,
                topLeft = Offset(w * 0.08f, h * 0.26f),
                size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.60f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f, w * 0.14f),
                style = Stroke(2.2.dp.toPx())
            )
            // 顶部取景器梯台
            drawPath(
                Path().apply {
                    moveTo(w * 0.34f, h * 0.26f)
                    lineTo(w * 0.40f, h * 0.14f)
                    lineTo(w * 0.60f, h * 0.14f)
                    lineTo(w * 0.66f, h * 0.26f)
                },
                ink,
                style = Stroke(2.2.dp.toPx(), join = StrokeJoin.Round)
            )
            // 镜头
            drawCircle(ink, radius = w * 0.15f, center = Offset(w * 0.50f, h * 0.56f), style = Stroke(2.2.dp.toPx()))
            // 快门小点
            drawCircle(ink, radius = 1.4.dp.toPx(), center = Offset(w * 0.78f, h * 0.38f))
        }
    }
}

/** GO 按钮：主行动钮，颜色随运动（骑行=电光青 / 徒步=荧光绿），点击带振动反馈 */
@Composable
private fun GoButton(sport: SportType, onStart: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val btn = if (sport == SportType.RIDE) AvColors.cyan else AvColors.green
    Box(
        Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(btn)
            .combinedClickable(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onStart()
            }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "GO",
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            color = AvColors.deepInk,
            letterSpacing = 2.sp
        )
    }
}

/** 热身按钮：黑白自适应描边 + 拉伸人形（徒步）/ 自行车（骑行）动效图标，点击弹热身清单 */
@Composable
private fun WarmupButton(sport: SportType, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val mono = MaterialTheme.colorScheme.onBackground   // 黑白自适应：浅色=深字 深色=白字
    val phase by rememberInfiniteTransition(label = "wu").animateFloat(
        initialValue = 0f, targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = androidx.compose.animation.core.LinearEasing)),
        label = "wuPhase"
    )
    Box(
        modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, mono.copy(alpha = 0.45f), CircleShape)
            .clickable { show = true },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        // 呼吸浮动
                        translationY = kotlin.math.sin(phase) * 1.4.dp.toPx()
                    }
            ) {
                val c = mono
                val wd = 1.8.dp.toPx()
                if (sport == SportType.RIDE) {
                    // 自行车：两轮（辐条旋转）+ 车架三角 + 车把
                    val spoke = kotlin.math.cos(phase) * size.width * 0.14f
                    drawCircle(c, radius = size.width * 0.18f, center = Offset(size.width * 0.22f, size.height * 0.72f), style = Stroke(wd))
                    drawCircle(c, radius = size.width * 0.18f, center = Offset(size.width * 0.78f, size.height * 0.72f), style = Stroke(wd))
                    drawLine(c, Offset(size.width * 0.22f - spoke, size.height * 0.72f - spoke), Offset(size.width * 0.22f + spoke, size.height * 0.72f + spoke), wd * 0.7f, cap = StrokeCap.Round)
                    drawLine(c, Offset(size.width * 0.78f - spoke, size.height * 0.72f + spoke), Offset(size.width * 0.78f + spoke, size.height * 0.72f - spoke), wd * 0.7f, cap = StrokeCap.Round)
                    val frame = Path().apply {
                        moveTo(size.width * 0.22f, size.height * 0.72f)
                        lineTo(size.width * 0.44f, size.height * 0.34f)
                        lineTo(size.width * 0.62f, size.height * 0.72f)
                        lineTo(size.width * 0.22f, size.height * 0.72f)
                        moveTo(size.width * 0.44f, size.height * 0.34f)
                        lineTo(size.width * 0.66f, size.height * 0.32f)
                        lineTo(size.width * 0.78f, size.height * 0.72f)
                    }
                    drawPath(frame, c, style = Stroke(wd, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawLine(c, Offset(size.width * 0.36f, size.height * 0.28f), Offset(size.width * 0.52f, size.height * 0.28f), wd, cap = StrokeCap.Round)
                } else {
                    // 拉伸人形：弓步 + 双臂前后摆动（原地拉伸）
                    val swing = kotlin.math.sin(phase)          // -1..1
                    val headC = Offset(size.width * 0.42f, size.height * 0.16f)
                    drawCircle(c, radius = size.width * 0.11f, center = headC, style = Stroke(wd))
                    // 躯干（前倾）
                    val hip = Offset(size.width * 0.50f, size.height * 0.52f)
                    val shoulder = Offset(size.width * 0.40f, size.height * 0.28f)
                    drawLine(c, shoulder, hip, wd, cap = StrokeCap.Round)
                    // 前臂：向前平伸（拉伸），随相位轻摆
                    val armY = size.height * (0.30f + swing * 0.06f)
                    drawLine(c, shoulder, Offset(size.width * 0.86f, armY), wd, cap = StrokeCap.Round)
                    // 后臂：屈肘后摆
                    drawLine(
                        c, shoulder,
                        Offset(size.width * (0.26f - swing * 0.05f), size.height * (0.48f - swing * 0.05f)),
                        wd, cap = StrokeCap.Round
                    )
                    // 弓步双腿
                    drawLine(c, hip, Offset(size.width * 0.80f, size.height * 0.90f), wd, cap = StrokeCap.Round)
                    drawLine(c, hip, Offset(size.width * 0.30f, size.height * 0.90f), wd, cap = StrokeCap.Round)
                }
            }
            Text(
                stringResource(R.string.warmup_tag),
                style = MaterialTheme.typography.labelSmall,
                color = mono
            )
        }
    }
    if (show) {
        val items = Coach.warmup(sport)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { show = false }) { Text(stringResource(R.string.fmt_start, stringResource(sport.labelRes))) }
            },
            dismissButton = {},
            title = { Text(stringResource(R.string.fmt_warmup, stringResource(sport.labelRes))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEachIndexed { i, s ->
                        Row {
                            Text(
                                "${i + 1}  ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(s, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        )
    }
}

/** GO 全屏倒计时覆盖层：3 → 2 → 1 → GO，半透明黑底，点击取消 */
@Composable
private fun CountdownOverlay(count: Int, sport: SportType, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = count,
            transitionSpec = {
                (androidx.compose.animation.scaleIn(
                    initialScale = 2.2f, animationSpec = tween(260)
                ) + androidx.compose.animation.fadeIn(tween(100)))
                    .togetherWith(
                        androidx.compose.animation.scaleOut(targetScale = 0.45f, animationSpec = tween(260)) +
                                androidx.compose.animation.fadeOut(tween(180))
                    )
            },
            label = "countdown"
        ) { n ->
            Text(
                if (n > 0) "$n" else "GO",
                fontSize = if (n > 0) 150.sp else 110.sp,
                fontWeight = FontWeight.Black,
                color = if (n > 0) Color.White
                        else if (sport == SportType.RIDE) AvColors.cyan else AvColors.green
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 130.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.fmt_coming, stringResource(sport.labelRes)),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.cancel_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * 结束按钮：长按 2 秒触发，带进度环 + 每秒震动 + 语音提示，中途松手取消。
 */
@Composable
private fun StopButton(onStop: () -> Unit) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val tts = remember { CoachTts(ctx) }
    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }
    Box(
        Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        Toast.makeText(ctx, "长按 2 秒结束，避免误触", Toast.LENGTH_SHORT).show()
                    },
                    onPress = {
                        if (progress <= 0f) {
                            tts.say("松手取消，长按两秒结束运动")
                            val job = scope.launch {
                                val t0 = System.currentTimeMillis()
                                var lastSec = 0
                                while (true) {
                                    val el = System.currentTimeMillis() - t0
                                    progress = (el / 2000f).coerceIn(0f, 1f)
                                    val sec = (el / 1000).toInt()
                                    if (sec > lastSec) {
                                        lastSec = sec
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    if (progress >= 1f) break
                                    delay(40)
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStop()
                                progress = 0f
                            }
                            try {
                                awaitRelease()
                            } finally {
                                job.cancel()
                                progress = 0f
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            if (progress > 0f) {
                drawArc(
                    AvColors.green, -90f, 360f * progress, false,
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Text(
            "结束",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.background
        )
    }
}

@Composable
private fun RoundButton(label: String, filled: Boolean, sizeDp: Int, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(
                if (filled) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.background
            )
            .border(1.dp, if (filled) MaterialTheme.colorScheme.onBackground else accent, CircleShape)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (filled) MaterialTheme.colorScheme.background else accent
        )
    }
}

@Composable
private fun BackgroundLocHint() {
    val ctx = LocalContext.current
    val hasBg = remember {
        androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    if (!hasBg) {
        Text(
            "提示：锁屏后若轨迹中断，请到系统设置将本应用位置权限设为「始终允许」",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun KeepScreenOn(on: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(on) {
        val win = (ctx as? android.app.Activity)?.window
        if (on && win != null) {
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

fun fmtDur(ms: Long): String = Coach.fmtDur(ms)
