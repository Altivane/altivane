package com.altivane.app.ui

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.data.PointEntity
import com.altivane.app.data.RideEntity
import com.altivane.app.data.WaypointEntity
import com.altivane.app.domain.CardType
import com.altivane.app.domain.Coach
import com.altivane.app.domain.LiveState
import com.altivane.app.domain.Phase
import com.altivane.app.domain.RidePoint
import com.altivane.app.domain.RideStats
import com.altivane.app.domain.SportType
import com.altivane.app.domain.TimedValF
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** 卡片详情页：单个指标大字 + 实时曲线 + 说明（运动中实时，空闲显示上次运动值） */
@Composable
fun CardDetailScreen(
    app: RideApp,
    type: CardType,
    onBack: () -> Unit,
    onShareRide: (Long) -> Unit = {}
) {
    val s by app.session.state.collectAsState()
    val pts by app.session.points.collectAsState()
    val hrS by app.session.hrSamples.collectAsState()
    val cadS by app.session.cadSamples.collectAsState()
    val pressS by app.session.pressureSamples.collectAsState()
    val liveHr by app.ble.hr.collectAsState()
    val liveCad by app.ble.cad.collectAsState()
    var lastRide by remember { mutableStateOf<RideEntity?>(null) }
    var lastPts by remember { mutableStateOf<List<PointEntity>>(emptyList()) }
    var lastWps by remember { mutableStateOf<List<WaypointEntity>>(emptyList()) }
    var zoomPhoto by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // 按当前运动类型取最近一次记录（骑/徒数据隔离）
        val cur = s.sport
        val r = app.db.dao().rides().first().firstOrNull { rr ->
            try { com.altivane.app.domain.SportType.valueOf(rr.sportType) == cur } catch (_: Exception) { false }
        }
        lastRide = r
        if (r != null) {
            lastPts = app.db.dao().points(r.id)
            lastWps = app.db.dao().waypoints(r.id)
        } else {
            lastPts = emptyList()
            lastWps = emptyList()
        }
        // 轨迹管线埋点：详情页轨迹不显示时日志直接给出数据侧真相（条数/首尾坐标）
        com.altivane.app.domain.AppLog.i("Track",
            "detail load ride=${r?.id} sport=${r?.sportType} pts=${lastPts.size} wps=${lastWps.size} " +
            "first=${lastPts.firstOrNull()?.lat},${lastPts.firstOrNull()?.lon} " +
            "last=${lastPts.lastOrNull()?.lat},${lastPts.lastOrNull()?.lon}")
    }
    // 地图输入 remember 稳定化：lastPts/lastWps 不变就不重建列表实例——
    // 否则每次重组（传感器刷新 LiveState，每秒多次）都换新实例喂给地图 effect 反复重启
    val mapPts = remember(lastPts) { lastPts.map { TrackPt(it.lat, it.lon) } }
    val mapSpeeds = remember(lastPts) { lastPts.map { it.speed } }
    val mapWps = remember(lastWps) { lastWps.map { WpPt(it.lat, it.lon, it.kind, it.label, it.photo) } }
    // 详情页数据源：运动中用实时点，空闲用上次运动的落库点（统一转 domain 类型）
    val chartPts: List<com.altivane.app.domain.RidePoint> = if (s.phase == Phase.IDLE)
        lastPts.map { com.altivane.app.domain.RidePoint(it.t, it.lat, it.lon, it.ele, it.speed, it.hr, it.cad) }
    else pts

    // 定位（罗盘详情页用）：进入页面即主动请求 GPS/网络定位（免 GMS），离开注销
    var locFix by remember { mutableStateOf<android.location.Location?>(null) }
    val locCtx = LocalContext.current
    DisposableEffect(Unit) {
        val lm = locCtx.getSystemService(android.content.Context.LOCATION_SERVICE)
                as android.location.LocationManager
        // 先用缓存填首屏，避免空白
        locFix = cachedLoc(locCtx)
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(l: android.location.Location) {
                if (l.latitude != 0.0 || l.longitude != 0.0) locFix = l
            }
        }
        try {
            for (p in listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER
            )) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0.5f, listener)
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        onDispose {
            try { lm.removeUpdates(listener) } catch (_: Exception) { }
        }
    }

    val accent = when (type) {
        CardType.DISTANCE -> AvColors.blue
        CardType.DURATION -> AvColors.cyan
        CardType.PACE -> AvColors.yellow
        CardType.HR -> AvColors.pink
        CardType.CAD -> AvColors.purple
        CardType.CALS -> AvColors.orange
        CardType.MAX_SPEED -> AvColors.red
        CardType.COMPASS -> AvColors.teal
        CardType.BARO -> AvColors.violet
        CardType.ALTITUDE -> Color(0xFFA78BFA)
        CardType.TRACK -> AvColors.green
        CardType.PROFILE -> AvColors.fuchsia
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column {
                Text(stringResource(type.labelRes), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
            // 轨迹详情：右上角标注运动类型 + 时间（按设计稿）
            if (type == CardType.TRACK) {
                Spacer(Modifier.weight(1f))
                lastRide?.let { r ->
                    val sportName = try {
                        SportType.valueOf(r.sportType)
                    } catch (_: Exception) { SportType.HIKE }
                    Text(
                        stringResource(sportName.labelRes) + " · " + java.text.SimpleDateFormat(
                            "MM-dd HH:mm", java.util.Locale.CHINA
                        ).format(java.util.Date(r.startTime)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (type) {
                // 指南针详情：380dp 大盘（粗刻度 + 红北蓝南双指针）+ 读数 + 经纬度 / SOS / 分享
                CardType.COMPASS -> {
                    val livePt = if (s.phase != Phase.IDLE) pts.lastOrNull() else null
                    val lat = livePt?.lat ?: locFix?.latitude
                    val lon = livePt?.lon ?: locFix?.longitude
                    val alt = livePt?.ele ?: locFix?.altitude?.takeIf { it != 0.0 }
                    val acc = locFix?.accuracy
                    val shareText = if (lat != null && lon != null)
                        "我的位置：%.5f, %.5f\n高德地图：https://uri.amap.com/marker?position=%.6f,%.6f&name=我的位置\nGoogle Maps：https://maps.google.com/?q=%.5f,%.5f"
                            .format(lat, lon, lon, lat, lat, lon)
                    else null

                    Box(
                        Modifier.fillMaxWidth().height(380.dp).glassCard(isDark(), accent),
                        contentAlignment = Alignment.Center
                    ) {
                        CompassBig(s.headingDeg, s.tiltDeg, s.tiltDir, Modifier.fillMaxSize().padding(20.dp))
                    }
                    // 读数卡：航向数字 + 方位名（倾斜时附倾角读数）
                    GlassCard(accent = accent) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "%.0f°".format(s.headingDeg),
                                fontSize = 46.sp, fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cardinalName(s.headingDeg),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = AvColors.onColor(accent)
                                )
                                Text(
                                    buildString {
                                        if (s.hasCompass) append("磁力计实时") else append("等待传感器数据")
                                        if (s.tiltDeg >= 1f) append(" · 倾斜 %.0f°".format(s.tiltDeg))
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // SOS 手电筒 + 分享位置（经纬度并入分享卡）
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SosTorchCard(Modifier.weight(1f))
                        ShareLocationCard(lat, lon, alt, acc, livePt != null, shareText, Modifier.weight(1f))
                    }
                    // 野外辨向 · 迷失生存技巧
                    SurvivalTipsCard()
                }
                // 轨迹详情（v0.8.35 方案A 沉浸大图）：大地图做主角 + 4 格数据条 + 打卡点 + 大分享按钮
                // （每公里配速 / 海拔曲线等数据分析移入运动详情页）
                CardType.TRACK -> {
                    // 空闲用 remember 稳定化的落库数据；运动中用实时点（remember 包一层，
                    // 防 StateFlow 值不变但重组时 map{} 每次换新实例喂地图 effect）
                    val liveOn = s.phase != Phase.IDLE
                    val livePtsMap = remember(pts) { pts.map { TrackPt(it.lat, it.lon) } }
                    val liveSpdMap = remember(pts) { pts.map { it.speed } }
                    val viewPts = if (liveOn) livePtsMap else mapPts
                    val viewSpeeds = if (liveOn) liveSpdMap else mapSpeeds
                    val viewWps = mapWps
                    val maxSpd = if (!liveOn && lastRide != null)
                        lastRide!!.maxKmh.coerceAtLeast(1.0)
                    else AvColors.rainbowMax(s.sport)

                    // ① 大地图（矢量底图）：全程轨迹 + 打卡点 Marker。
                    //    恒组合（v0.8.39）：数据后到由 styleReady 回调 + 回放 effect 自动落入，
                    //    与实时页同款生命周期——此前是全 App 唯一"等异步数据才组合"的调用点，
                    //    疑似踩中 MapView 晚创建的渲染死点（整页连底图瓦片都不出）
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clip(RoundedCornerShape(18.dp))
                    ) {
                        MapTrackView(
                            pts = viewPts,
                            speeds = viewSpeeds,
                            maxSpeed = maxSpd,
                            waypoints = viewWps,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (viewPts.size < 2) {
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    "开始运动后记录实时轨迹",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (viewPts.size >= 2) {
                        // ② 4 格数据条（里程/时长/均速或配速/爬升）
                        val idle = s.phase == Phase.IDLE
                        val km = if (idle && lastRide != null) lastRide!!.distanceM / 1000.0 else s.distanceM / 1000.0
                        val dur = if (idle && lastRide != null) lastRide!!.movingS * 1000L else s.movingMs
                        val gain = if (idle && lastRide != null) lastRide!!.elevGainM
                        else remember(chartPts) { elevGainLoss(chartPts.map { it.ele }).first }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrackStatCell("%.2f".format(km), "km · 里程", null, Modifier.weight(1f))
                            TrackStatCell(Coach.fmtDur(dur), "时长", null, Modifier.weight(1f))
                            TrackStatCell(
                                if (s.sport == SportType.RIDE) "%.1f".format(
                                    if (dur > 0) km / (dur / 3600000.0) else 0.0
                                ) else Coach.paceText(if (dur > 0 && km > 0) dur / 60000.0 / km else 0.0),
                                if (s.sport == SportType.RIDE) "km/h · 均速" else "min/km · 配速",
                                null, Modifier.weight(1f)
                            )
                            TrackStatCell("%.0f".format(gain), "m · 爬升", AvColors.orange, Modifier.weight(1f))
                        }

                        // ③ 打卡行：照片缩略（最多 3 张）+ 最新打卡信息 pill
                        if (lastWps.isNotEmpty()) {
                            val lastWp = lastWps.last()
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                lastWps.take(3).forEach { w ->
                                    val bmp = w.photo?.let { p -> rememberPhotoBitmap(p) }
                                    Box(
                                        Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                when (w.kind) {
                                                    "M" -> AvColors.yellow.copy(alpha = 0.25f)
                                                    "K" -> AvColors.blue.copy(alpha = 0.25f)
                                                    "C" -> AvColors.green.copy(alpha = 0.25f)
                                                    else -> AvColors.purple.copy(alpha = 0.25f)
                                                }
                                            )
                                            .clickable(enabled = w.photo != null) { zoomPhoto = w.photo!! },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bmp != null) {
                                            Image(
                                                bmp, null,
                                                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Text(
                                                "打卡", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .clickable(enabled = lastWp.photo != null) { zoomPhoto = lastWp.photo!! }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Column {
                                        Text(
                                            "${lastWp.label} · 打卡",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1
                                        )
                                        Text(
                                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                                                .format(java.util.Date(lastWp.t)) + " · 共 ${lastWps.size} 个",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // ④ 生成分享图：电光青大按钮（空闲态分享上次运动，运动中分享当前记录）
                        val shareId = if (s.rideId != 0L) s.rideId else lastRide?.id ?: 0L
                        if (shareId != 0L) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(AvColors.cyan)
                                    .clickable { onShareRide(shareId) },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Share, null,
                                        Modifier.size(18.dp),
                                        tint = Color(0xFF0D1117)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "生成分享图",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF0D1117)
                                    )
                                }
                            }
                        }
                    }
                }
                // 海拔图详情：立体剖面大图
                CardType.PROFILE -> {
                    Box(
                        Modifier.fillMaxWidth().height(220.dp).glassCard(isDark(), accent)
                    ) {
                        if (chartPts.size >= 2) {
                            ElevationSpark(
                                chartPts.map { it.ele },
                                AvColors.onColor(AvColors.orange),
                                Modifier.fillMaxSize().padding(14.dp)
                            )
                            Text(
                                "%.0f - %.0f m".format(chartPts.map { it.ele }.min(), chartPts.map { it.ele }.max()),
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "开始运动后记录海拔剖面",
                                Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // 常规指标：大数字 + 专属图表 + 统计摘要 + 分析卡 + 说明
                else -> {
                    val idle = s.phase == Phase.IDLE
                    val sport = if (idle)
                        try {
                            SportType.valueOf(lastRide?.sportType ?: "HIKE")
                        } catch (_: Exception) {
                            SportType.HIKE
                        }
                    else s.sport
                    val chart = chartFor(
                        type, chartPts, sport,
                        // 心率/踏频/气压的实时样本在空闲态也持续到达，详情页与主页小卡同步显示
                        hrS = if (idle && type != CardType.HR) emptyList() else hrS,
                        cadS = if (idle && type != CardType.CAD) emptyList() else cadS,
                        pressS = if (idle && type != CardType.BARO) emptyList() else pressS
                    )
                    // ① 主数值（心率/踏频 = 实时仪表盘，其余运动中实时 / 空闲显示上次）
                    Box(
                        Modifier.fillMaxWidth().height(150.dp).glassCard(isDark(), accent),
                        contentAlignment = Alignment.Center
                    ) {
                        val value = when {
                            type == CardType.HR -> (if (liveHr > 0) liveHr.toString() else "--") to "次/分"
                            type == CardType.CAD -> (if (liveCad >= 0) liveCad.toString() else "--") to "rpm"
                            // 气压计常开：空闲态也显示实时气压（纯 hPa，不回退 GPS 海拔）
                            type == CardType.BARO -> when {
                                !s.hasBaro -> "--" to "设备无气压计"
                                s.pressureHpa > 0 -> "%.1f".format(s.pressureHpa) to "hPa"
                                pressS.isNotEmpty() -> "%.1f".format(pressS.last().v) to "hPa"
                                else -> "--" to "hPa"
                            }
                            idle -> lastValueOf(type, lastRide)
                            else -> valueOf(type, s)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                value.first,
                                fontSize = 64.sp, fontWeight = FontWeight.Bold,
                                color = AvColors.onColor(accent),
                                maxLines = 1
                            )
                            if (value.second.isNotEmpty()) {
                                Text(value.second, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // ② 专属趋势图
                    if (chart != null) {
                        DetailChartCard(type, chart, accent, chartPts)
                    }
                    // ③ 统计摘要
                    DetailSummary(type, s, lastRide, chartPts, idle, pressS)
                    // ④ 深度分析（心率区间 / 每公里配速 / 强度分段 / 移动占比 / 天气趋势）
                    DetailAnalysis(type, chartPts, idle, hrS, pressS, s, lastRide)
                    // ⑤ 指标说明（气压卡说明已在天气趋势卡中，不再重复小字）
                    if (cardDesc(type).isNotEmpty()) {
                        GlassCard(accent = accent.copy(alpha = 0.5f)) {
                            Text(
                                cardDesc(type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 打卡照片全屏放大
    zoomPhoto?.let { p ->
        Dialog(onDismissRequest = { zoomPhoto = null }) {
            val bmp = rememberPhotoBitmap(p, 2048)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { zoomPhoto = null },
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Text("加载中…", color = Color.White)
                }
            }
        }
    }
}

// ---------- 详情页丰富信息：趋势图 / 统计摘要 / 深度分析 ----------

private fun fmtChartVal(type: CardType, v: Double): String = when (type) {
    CardType.HR -> "${v.toInt()} 次/分"
    CardType.PACE, CardType.MAX_SPEED -> "%.1f km/h".format(v)
    CardType.CAD -> "${v.toInt()} rpm"
    CardType.DISTANCE -> "%.2f km".format(v)
    CardType.DURATION -> "${v.toInt()} 分"
    CardType.BARO -> "%.1f hPa".format(v)
    CardType.ALTITUDE -> "%.0f m".format(v)
    else -> "%.1f".format(v)
}

/** 趋势图卡：专属图型 + 低/峰角标 */
@Composable
private fun DetailChartCard(
    type: CardType, chart: CardChart, accent: Color, pts: List<RidePoint>
) {
    GlassCard(Modifier.height(190.dp), accent = accent) {
        Column(Modifier.fillMaxSize()) {
            CardTitleRow("趋势", accent)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f)) {
                CardChartView(chart, Modifier.fillMaxSize(), accent)
                // 峰/低角标：ENERGY 分段与 HOURLY_BARS/MOVE_REST（自带峰值标注/图例）不显示，
                // 后者 series 是分桶/速度序列，min/max 角标无意义
                val showTicks = chart.kind != ChartKind.ENERGY &&
                        chart.kind != ChartKind.HOURLY_BARS && chart.kind != ChartKind.MOVE_REST
                if (showTicks && chart.series.size >= 2) {
                    Text(
                        "峰 ${fmtChartVal(type, chart.series.max())}",
                        Modifier.align(Alignment.TopEnd),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "低 ${fmtChartVal(type, chart.series.min())}",
                        Modifier.align(Alignment.TopStart),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 轨迹详情数据格：玻璃小卡 + 数值大字（可着色）+ 单位标签 */
@Composable
private fun TrackStatCell(value: String, label: String, valueColor: Color?, modifier: Modifier = Modifier) {
    Box(modifier.height(74.dp).glassCard(isDark()), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = valueColor ?: MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单个统计项：标签小字 + 数值大字 */
@Composable
private fun DetailStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
            color = AvColors.onColor(accent),
            maxLines = 1
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 统计摘要行：三列关键数字（数据源：实时点 / 上次运动落库点） */
@Composable
private fun DetailSummary(
    type: CardType, s: LiveState, ride: RideEntity?, pts: List<RidePoint>,
    idle: Boolean, pressList: List<TimedValF> = emptyList()
) {
    val accent = when (type) {
        CardType.DISTANCE -> AvColors.blue
        CardType.DURATION -> AvColors.cyan
        CardType.PACE -> AvColors.yellow
        CardType.HR -> AvColors.pink
        CardType.CAD -> AvColors.purple
        CardType.CALS -> AvColors.orange
        CardType.MAX_SPEED -> AvColors.red
        CardType.BARO -> AvColors.violet
        CardType.ALTITUDE -> Color(0xFFA78BFA)
        else -> AvColors.cyan
    }
    val hrs = pts.map { it.hr }.filter { it > 0 }
    val cads = pts.map { it.cad }.filter { it >= 0 }
    val spd = pts.map { it.speed * 3.6 }
    val eles = pts.map { it.ele }.filter { it > 0.0 }
    val totalKm = if (idle) (ride?.distanceM ?: 0.0) / 1000.0 else s.distanceM / 1000.0
    val avgKmh = if (idle) (ride?.avgKmh ?: 0.0) else s.avgKmh
    val movingMs = if (idle) (ride?.movingS ?: 0) * 1000L else s.movingMs
    val kcal = if (idle) (ride?.kcal ?: 0.0) else s.kcal
    val fastest = remember(pts) { RideStats.kmPaces(pts).minOrNull() }
    val pressVals = pressList.map { it.v }

    val items: List<Pair<String, String>> = when (type) {
        CardType.HR -> listOf(
            "当前" to (if (idle) (hrs.lastOrNull()?.toString() ?: "--") else (if (s.hr > 0) s.hr.toString() else "--")),
            "平均" to (if (hrs.isNotEmpty()) "${hrs.average().toInt()} 次/分" else "--"),
            "峰值" to (if (hrs.isNotEmpty()) "${hrs.max()} 次/分" else "--")
        )
        CardType.PACE -> listOf(
            "当前" to Coach.paceText(if (idle) 0.0 else s.paceMinPerKm),
            "均速" to "%.1f km/h".format(avgKmh),
            "峰值时速" to (if (spd.isNotEmpty()) "%.1f km/h".format(spd.max()) else "--"),
            "最快公里" to (fastest?.let { Coach.paceText(it) } ?: "--")
        )
        CardType.DISTANCE -> {
            // 步数换算仅徒步有意义（骑行不显示）：成人通用步长 0.72 m 估算
            val sportName = if (idle) ride?.sportType ?: "HIKE" else s.sport.name
            val base = listOf(
                "里程" to "%.2f km".format(totalKm),
                "均速" to "%.1f km/h".format(avgKmh),
                "移动" to Coach.fmtDur(movingMs)
            )
            if (sportName != "RIDE") base + (
                "步数" to (
                    if (totalKm > 0.05) "%d 步".format((totalKm * 1000 / 0.72).toInt()) else "--"
                    )
                )
            else base
        }
        CardType.DURATION -> {
            val total = if (pts.size >= 2) pts.last().t - pts.first().t else 0L
            val rest = (total - movingMs).coerceAtLeast(0)
            listOf(
                "移动" to Coach.fmtDur(movingMs),
                "停留" to Coach.fmtDur(rest),
                "占比" to (if (total > 0) "${(movingMs * 100 / total)}%" else "--")
            )
        }
        CardType.CAD -> listOf(
            "当前" to (if (idle) (cads.lastOrNull()?.toString() ?: "--") else s.cad.toString()),
            "平均" to (if (cads.isNotEmpty()) "${cads.average().toInt()} rpm" else "--"),
            "峰值" to (if (cads.isNotEmpty()) "${cads.max()} rpm" else "--")
        )
        CardType.CALS -> listOf(
            "总消耗" to "${kcal.toInt()} kcal",
            "每公里" to (if (totalKm > 0.2) "${(kcal / totalKm).toInt()} kcal" else "--"),
            "时长" to Coach.fmtDur(movingMs)
        )
        CardType.MAX_SPEED -> listOf(
            "峰值" to "%.1f km/h".format(if (idle) (ride?.maxKmh ?: 0.0) else s.maxKmh),
            "平均" to (if (spd.isNotEmpty()) "%.1f km/h".format(spd.average()) else "--"),
            "里程" to "%.2f km".format(totalKm)
        )
        // 气压卡：只显示气压数据（hPa），海拔统计归高度卡；无气压计不渲染统计行
        CardType.BARO -> if (!s.hasBaro) emptyList() else listOf(
            "当前" to (if (s.pressureHpa > 0) "%.1f hPa".format(s.pressureHpa) else "--"),
            "最高" to (if (pressVals.isNotEmpty()) "%.1f hPa".format(pressVals.max()) else "--"),
            "最低" to (if (pressVals.isNotEmpty()) "%.1f hPa".format(pressVals.min()) else "--")
        )
        // 高度卡：海拔 / 相对起点爬升 / 本次最高
        CardType.ALTITUDE -> listOf(
            "当前" to (if (!idle && s.altBaroM != 0.0) "%.0f m".format(s.altBaroM)
            else if (!idle && s.relAltM != 0.0) "%.0f m".format(s.relAltM)
            else if (idle && ride != null) "%.0f m".format(ride.maxAltM) else "--"),
            "相对起点" to (if (!idle && s.baseAltSet) "%+.0f m".format(s.relAltM) else "--"),
            "累计爬升" to (if (idle && ride != null) "%.0f m".format(ride.elevGainM) else "%.0f m".format(s.elevGainM))
        )
        else -> emptyList()
    }
    if (items.isEmpty()) return
    GlassCard(accent = accent.copy(alpha = 0.5f)) {
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, (label, value) ->
                if (i > 0) Spacer(Modifier.width(10.dp))
                DetailStat(label, value, accent, Modifier.weight(1f))
            }
        }
    }
}

/** 深度分析卡：按卡型出专属分析（心率区间 / 每公里配速 / 强度分段 / 移动占比 / 天气趋势） */
@Composable
private fun DetailAnalysis(
    type: CardType,
    pts: List<RidePoint>,
    idle: Boolean,
    hrSamples: List<com.altivane.app.domain.TimedVal> = emptyList(),
    pressList: List<TimedValF> = emptyList(),
    s: LiveState? = null,
    ride: RideEntity? = null
) {
    when (type) {
        CardType.HR -> {
            // 优先用 BLE 直收样本时序（静止时 GPS 点被过滤也有数据）；上次运动回退落库点
            val live = hrSamples.map { RidePoint(it.t, 0.0, 0.0, 0.0, hr = it.v) }
            when {
                live.size >= 2 -> ZoneBarsCard(live)
                pts.any { it.hr > 0 } -> ZoneBarsCard(pts)
                else -> ZoneBarsCard(emptyList())
            }
        }
        CardType.PACE -> KmPaceCard(pts)
        CardType.CALS -> EnergyBreakdownCard(pts)
        CardType.DURATION -> MovingRatioCard(pts, idle)
        // 气压：天气趋势判读（用户核心用途——气压卡主要用来预测天气）；无气压计显示提示卡
        CardType.BARO -> {
            if (s?.hasBaro == false) NoBaroCard()
            else WeatherTrendCard(s?.pressureHpa ?: 0f, pressList)
        }
        else -> {}
    }
}

/** 无气压计提示卡：替代天气趋势卡（设备无传感器，气压功能不可用） */
@Composable
private fun NoBaroCard() {
    GlassCard(accent = AvColors.violet.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow("天气趋势（气压判读）", AvColors.violet)
            Spacer(Modifier.height(8.dp))
            Text(
                "此设备没有气压传感器，气压卡与天气趋势不可用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "其余功能（GPS 轨迹 / 心率 / 踏频等）不受影响",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 气压→天气趋势卡：常见气压规格说明 + 3h 趋势预警 */
@Composable
private fun WeatherTrendCard(liveHpa: Float, pressList: List<TimedValF>) {
    val vals = pressList.map { it.v }
    val now = if (liveHpa > 0) liveHpa.toDouble() else vals.lastOrNull() ?: 0.0
    if (now <= 0.0) return
    // 3h 变化：取不早于 3h 前的最近样本对比（样本不足则用全程首尾）
    val ref = vals.firstOrNull() ?: now
    val d3 = now - ref
    val level = when {
        now >= 1020.0 -> Triple("高压 · 晴好稳定", AvColors.green,
            "气压 ≥1020 hPa，高压系统控制，天气晴朗稳定，适合出行")
        now >= 1000.0 -> Triple("常压 · 天气多变", AvColors.yellow,
            "气压 1000~1020 hPa，处于常见区间，关注云量与风的变化")
        else -> Triple("低压 · 阴雨概率大", AvColors.red,
            "气压 ≤1000 hPa，低压系统影响，阴雨/大风概率显著上升")
    }
    val trend = when {
        d3 <= -1.5 -> Triple("▼ 气压骤降 %.1f hPa".format(d3), AvColors.red,
            "3 小时内下降超 1.5 hPa，天气即将转坏，注意防风防雨")
        d3 <= -0.8 -> Triple("↘ 缓慢下降 %.1f hPa".format(d3), AvColors.orange,
            "气压走低，天气趋于不稳定")
        d3 >= 1.5 -> Triple("▲ 气压回升 %.1f hPa".format(d3), AvColors.green,
            "气压持续回升，天气趋于好转")
        else -> null
    }
    GlassCard(accent = AvColors.violet.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow("天气趋势（气压判读）", AvColors.violet)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(level.second))
                Spacer(Modifier.width(8.dp))
                Text(level.first, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                Text("%.1f hPa".format(now),
                    style = MaterialTheme.typography.titleSmall,
                    color = AvColors.onColor(AvColors.violet))
            }
            Spacer(Modifier.height(4.dp))
            Text(level.third, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            trend?.let { (t, c, d) ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(8.dp))
                    Text(t, style = MaterialTheme.typography.titleSmall,
                        color = AvColors.onColor(c))
                }
                Text(d, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            // 常见气压规格速查
            Text("常见气压规格", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            listOf(
                "1026" to "强高压 · 干冷晴朗（冬季寒潮）",
                "1013.25" to "海平面标准大气压（ISA 基准）",
                "1000" to "一般低压 · 多云阵雨",
                "980" to "温带气旋 · 大风降雨",
                "950" to "强台风外围 · 恶劣天气"
            ).forEach { (v, d) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("${v} hPa", Modifier.width(86.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = AvColors.onColor(AvColors.violet))
                    Text(d, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 心率区间分布：Z1-Z5 时长占比横条（恢复→极限）；v0.8.35 公开供运动详情页复用 */
@Composable
fun ZoneBarsCard(pts: List<RidePoint>) {
    val zones = remember(pts) { RideStats.zoneSeconds(pts) }
    val total = zones.sum()
    val names = listOf("Z1 恢复", "Z2 轻松", "Z3 有氧", "Z4 乳酸", "Z5 极限")
    val colors = listOf(AvColors.blue, AvColors.green, AvColors.yellow, AvColors.orange, AvColors.red)
    GlassCard(accent = AvColors.pink.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow("心率区间分布", AvColors.pink)
            Spacer(Modifier.height(8.dp))
            if (total <= 0) {
                Text(
                    "连接心率带并开始运动后，按 Z1~Z5 统计各区间时长占比",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            zones.forEachIndexed { i, sec ->
                val pct = if (total > 0) sec * 100f / total else 0f
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        names[i],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(pct / 100f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(colors[i])
                        )
                    }
                    Text(
                        "%d:%02d".format(sec / 60, sec % 60),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.width(46.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }
}

/** 每公里配速：列表 + 最快公里高亮（最多显示 12 条） */
@Composable
private fun KmPaceCard(pts: List<RidePoint>) {
    val paces = remember(pts) { RideStats.kmPaces(pts) }
    if (paces.isEmpty()) return
    val fastestIdx = paces.indices.minBy { paces[it] }
    GlassCard(accent = AvColors.yellow.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow("每公里配速", AvColors.yellow)
            Spacer(Modifier.height(6.dp))
            paces.take(12).forEachIndexed { i, p ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "第 ${i + 1} 公里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Coach.paceText(p),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (i == fastestIdx) AvColors.onColor(AvColors.green)
                        else MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            if (paces.size > 12) {
                Text(
                    "…共 ${paces.size} 公里",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 消耗强度分段：低/中/高强度三行明细 */
@Composable
private fun EnergyBreakdownCard(pts: List<RidePoint>) {
    val split = remember(pts) { RideStats.kcalSplit(pts, pts.firstOrNull()?.let { SportType.HIKE } ?: SportType.HIKE) }
    val total = split.sum()
    if (total <= 0.0) return
    val names = listOf("低强度", "中强度", "高强度")
    val colors = listOf(AvColors.cyan, AvColors.yellow, AvColors.orange)
    GlassCard(accent = AvColors.orange.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow("强度分段消耗", AvColors.orange)
            Spacer(Modifier.height(8.dp))
            split.forEachIndexed { i, v ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(9.dp).clip(CircleShape).background(colors[i])
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        names[i],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${v.toInt()} kcal · ${(v * 100 / total).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

/** 移动/停留占比条 */
@Composable
private fun MovingRatioCard(pts: List<RidePoint>, idle: Boolean) {
    val total = if (pts.size >= 2) pts.last().t - pts.first().t else 0L
    if (total <= 0) return
    val moving = remember(pts) {
        var ms = 0L
        for (i in 1 until pts.size) {
            val dt = pts[i].t - pts[i - 1].t
            if (dt in 1..8000 && pts[i - 1].speed >= 0.7f) ms += dt
        }
        ms
    }
    val rest = (total - moving).coerceAtLeast(0)
    val mp = moving * 100f / total
    GlassCard(accent = AvColors.cyan.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow("移动 / 停留", AvColors.cyan)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    Modifier.weight(mp.coerceAtLeast(1f))
                        .clip(RoundedCornerShape(6.dp))
                        .background(AvColors.green)
                )
                Box(
                    Modifier.weight((100f - mp).coerceAtLeast(1f))
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "● 移动 ${Coach.fmtDur(moving)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "● 停留 ${Coach.fmtDur(rest)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 轨迹总长（km，纯函数） */
private fun trackKm(pts: List<RidePoint>): Double {
    var acc = 0.0
    for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]
        acc += RideStats.haversine(a.lat, a.lon, b.lat, b.lon)
    }
    return acc / 1000.0
}

private fun valueOf(t: CardType, s: LiveState): Pair<String, String> = when (t) {
    CardType.DISTANCE -> "%.2f".format(s.distanceM / 1000.0) to "km"
    CardType.DURATION -> Coach.fmtDur(s.movingMs) to ""
    CardType.PACE -> Coach.paceText(s.paceMinPerKm) to "min/km"
    CardType.HR -> (if (s.hr > 0) s.hr.toString() else "--") to "次/分"
    CardType.CAD -> (if (s.cad >= 0) s.cad.toString() else "--") to "rpm"
    CardType.CALS -> s.kcal.toInt().toString() to "kcal"
    CardType.MAX_SPEED -> "%.1f".format(s.maxKmh) to "km/h"
    CardType.COMPASS -> "%.0f".format(s.headingDeg) to "航向"
    CardType.BARO -> when {
        !s.hasBaro -> "--" to "设备无气压计"
        s.pressureHpa > 0 -> "%.1f".format(s.pressureHpa) to "hPa"
        else -> "--" to "hPa"
    }
    CardType.ALTITUDE -> when (s.sport) {
        SportType.HIKE -> when {
            s.altBaroM != 0.0 -> "%.0f".format(s.altBaroM) to "m 海拔"
            s.altM > 0.0 -> "%.0f".format(s.altM) to "m（GPS）"
            else -> "--" to ""
        }
        SportType.RIDE -> when {
            s.baseAltSet -> "%.0f".format(s.relAltM) to "m 相对"
            else -> "--" to ""
        }
    }
    CardType.TRACK -> "" to ""
    CardType.PROFILE -> "" to ""
}

private fun lastValueOf(t: CardType, r: RideEntity?): Pair<String, String> {
    if (r == null) return "--" to "暂无运动记录"
    return when (t) {
        CardType.DISTANCE -> "%.2f".format(r.distanceM / 1000.0) to "km（上次）"
        CardType.DURATION -> Coach.fmtDur(r.movingS * 1000L) to "（上次）"
        CardType.PACE -> if (r.avgKmh > 0.1) Coach.paceText(60.0 / r.avgKmh) to "min/km（平均）"
        else "--" to ""
        CardType.HR -> "--" to "实时心率见上方图表"
        CardType.CAD -> "--" to "实时踏频见上方图表"
        CardType.CALS -> r.kcal.toInt().toString() to "kcal（上次）"
        CardType.MAX_SPEED -> "%.1f".format(r.maxKmh) to "km/h（上次）"
        CardType.COMPASS -> "--" to "航向实时可见"
        CardType.BARO -> "--" to "实时气压见上方数值"
        CardType.ALTITUDE -> "%.0f".format(r.maxAltM) to "m（上次最高）"
        CardType.TRACK -> "" to ""
        CardType.PROFILE -> "" to ""
    }
}

private fun cardDesc(t: CardType): String = when (t) {
    CardType.DISTANCE -> "累计里程：GPS 逐点累加（Haversine 球面距离）；步数 = 里程 ÷ 0.72 m（成人通用步长估算，仅徒步）"
    CardType.DURATION -> "移动时间：速度低于 2.5 km/h 的停留不计入"
    CardType.PACE -> "配速 = 每公里用时。跑步/徒步核心指标，实时按当前速度换算"
    CardType.HR -> "实时心率（BLE 0x2A37 标准协议）。超过最大心率 90% 自动语音预警降速"
    CardType.CAD -> "踏频（BLE CSC 0x2A5B 协议）：每分钟曲柄圈数，含回绕与停踩处理"
    CardType.CALS -> "实时消耗：有心率用 Keytel 公式，无心率按 MET 分桶估算"
    CardType.MAX_SPEED -> "本次运动最高瞬时速度（GPS 多普勒）"
    CardType.COMPASS -> "磁力计航向：红针指北、蓝针指南；下方附经纬度、SOS 求救闪光（三短三长三短）与一键位置分享"
    CardType.BARO -> ""
    CardType.ALTITUDE -> "气压计测高（ISA 国际标准大气）：运动开始时以 GPS 海拔校准零点，之后气压灵敏追踪——徒步显示海拔高程，骑行显示相对起点高程；气压 0.1 hPa ≈ 0.8 m"
    CardType.TRACK -> "实时彩虹轨迹：速度分色，慢=紫蓝、快=红；打卡照片钉在对应位置"
    CardType.PROFILE -> "海拔剖面：上山下山的高度变化，彩条渐变描边"
}

/** 方位名：16 方位（北 / 北偏东 / 东北 …） */
private fun cardinalName(deg: Float): String {
    val d = ((deg % 360) + 360) % 360
    val names = listOf(
        "北", "北偏东", "东北", "东偏北", "东", "东偏南", "东南", "南偏东",
        "南", "南偏西", "西南", "西偏南", "西", "西偏北", "西北", "北偏西"
    )
    return names[Math.round(d / 22.5f).toInt() % 16]
}

/** 缓存定位：读系统最后已知位置（免 GMS） */
private fun cachedLoc(ctx: android.content.Context): android.location.Location? {
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    for (p in listOf(
        android.location.LocationManager.GPS_PROVIDER,
        android.location.LocationManager.NETWORK_PROVIDER,
        android.location.LocationManager.PASSIVE_PROVIDER
    )) {
        try {
            if (lm.isProviderEnabled(p)) {
                lm.getLastKnownLocation(p)?.let { if (it.latitude != 0.0 || it.longitude != 0.0) return it }
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    return null
}

/** 手电筒开关（需 CAMERA 权限），成功返回 true */
private fun setTorch(ctx: android.content.Context, on: Boolean): Boolean = try {
    val cm = ctx.getSystemService(android.content.Context.CAMERA_SERVICE)
        as android.hardware.camera2.CameraManager
    val id = cm.cameraIdList.firstOrNull { cid ->
        cm.getCameraCharacteristics(cid)
            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } ?: return false
    cm.setTorchMode(id, on)
    true
} catch (_: Exception) {
    false
}

/** 分享位置卡：经纬度直显（海拔/精度小字），点击一键发送（高德/谷歌链接）；无定位时提示 */
@Composable
private fun ShareLocationCard(
    lat: Double?,
    lon: Double?,
    alt: Double?,
    acc: Float?,
    live: Boolean,
    shareText: String?,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .fillMaxWidth()
            .height(124.dp)
            .glassCard(isDark(), AvColors.cyan)
            .clickable {
                if (shareText == null) {
                    Toast.makeText(ctx, "暂无定位，请开启 GPS 后重试", Toast.LENGTH_SHORT).show()
                } else {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                    }
                    ctx.startActivity(
                        android.content.Intent.createChooser(send, "分享我的位置")
                    )
                }
            }
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "分享位置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (live) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(AvColors.green)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (lat != null && lon != null) {
                Text(
                    "%.5f° N".format(lat),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
                Text(
                    "%.5f° E".format(lon),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
                val subs = buildList {
                    alt?.let { add("海拔 %.0f m".format(it)) }
                    acc?.let { add("±%.0f m".format(it)) }
                }
                if (subs.isNotEmpty()) {
                    Text(
                        subs.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = dim
                    )
                }
            } else {
                Text(
                    "暂无定位，请到空旷处开启 GPS",
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim
                )
            }
        }
    }
}

/** SOS 手电筒卡：整卡点击 = SOS 摩尔斯闪烁（三短-三长-三短 循环），再点停止；离开页面自动熄灭 */
@Composable
private fun SosTorchCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var sos by remember { mutableStateOf(false) }
    val currentSos by rememberUpdatedState(sos)

    // SOS 闪烁循环：S(...) O(---) S(...)，单位 ≈ 220ms
    if (sos) {
        LaunchedEffect(Unit) {
            val dot = 220L
            val dash = dot * 3L
            val gap = dot
            val letter = dot * 3L
            while (true) {
                repeat(3) { setTorch(ctx, true); delay(dot); setTorch(ctx, false); delay(gap) }
                delay(letter - gap)
                repeat(3) { setTorch(ctx, true); delay(dash); setTorch(ctx, false); delay(gap) }
                delay(letter - gap)
                repeat(3) { setTorch(ctx, true); delay(dot); setTorch(ctx, false); delay(gap) }
                delay(letter)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (currentSos) setTorch(ctx, false) }
    }
    val hasCam = remember {
        androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val pulse = rememberInfiniteTransition(label = "sos")
    val blink by pulse.animateFloat(
        0.15f, 1f,
        infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label = "blink"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(124.dp)
            .glassCard(isDark(), if (sos) AvColors.red else null)
            .clickable {
                if (!hasCam) {
                    Toast.makeText(ctx, "手电筒需要相机权限，请重新进入应用完成授权", Toast.LENGTH_LONG).show()
                    (ctx as? com.altivane.app.MainActivity)?.requestPerms()
                } else if (sos) {
                    sos = false
                    setTorch(ctx, false) // 闪烁可能停在亮态，立即熄灭
                } else {
                    sos = true
                }
            }
            .padding(14.dp)
    ) {
        Column {
            // 标题行：图标 + 标题（闪烁中红点跟在文字后面）
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 手电筒矢量图标
                val torchColor = if (sos) AvColors.yellow
                else MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(20.dp)) {
                    val w2 = size.width; val h2 = size.height
                    val body = Path().apply {
                        moveTo(w2 * 0.22f, h2 * 0.06f)
                        lineTo(w2 * 0.78f, h2 * 0.06f)
                        lineTo(w2 * 0.60f, h2 * 0.38f)
                        lineTo(w2 * 0.60f, h2 * 0.94f)
                        lineTo(w2 * 0.40f, h2 * 0.94f)
                        lineTo(w2 * 0.40f, h2 * 0.38f)
                        close()
                    }
                    drawPath(
                        body, torchColor,
                        style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    if (sos) {
                        drawCircle(AvColors.yellow, radius = 2.6f, center = Offset(w2 * 0.50f, h2 * 0.66f))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (sos) "SOS 闪烁中" else "SOS 手电筒",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (sos) AvColors.red else MaterialTheme.colorScheme.onBackground
                )
                if (sos) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(10.dp)
                            .alpha(blink)
                            .clip(CircleShape)
                            .background(AvColors.red)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (sos) {
                // SOS 节拍柱：短闪矮柱 / 长闪高柱，随闪烁节奏依次点亮
                MorseBars(Modifier.fillMaxWidth().height(34.dp))
            } else {
                Text(
                    "点击开手电筒",
                    style = MaterialTheme.typography.labelSmall,
                    color = dim
                )
            }
        }
    }
}

/** SOS 节拍柱：· · · — — — · · ·（短=窄矮柱，长=宽高柱），播放头随真实摩尔斯节拍推进，扫过的柱子亮起 */
@Composable
private fun MorseBars(modifier: Modifier = Modifier) {
    val cycle = 6600f   // 与真实 SOS 闪烁同拍：点 220ms / 划 660ms / 位距 220ms / 字距 660ms
    val phase by rememberInfiniteTransition(label = "morse").animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(cycle.toInt(), easing = androidx.compose.animation.core.LinearEasing),
            RepeatMode.Restart
        ),
        label = "morsePhase"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        data class Seg(val s: Float, val e: Float, val long: Boolean)
        val segs = listOf(
            Seg(0f, 220f, false), Seg(440f, 660f, false), Seg(880f, 1100f, false),      // S
            Seg(1760f, 2420f, true), Seg(2640f, 3300f, true), Seg(3520f, 4180f, true),  // O
            Seg(4840f, 5060f, false), Seg(5280f, 5500f, false), Seg(5720f, 5940f, false) // S
        )
        val t = phase * cycle
        for (sg in segs) {
            val x0 = w * (sg.s / cycle)
            val bw = (w * ((sg.e - sg.s) / cycle)).coerceAtLeast(2.5.dp.toPx())
            val bh = if (sg.long) h * 0.94f else h * 0.32f
            val active = t >= sg.s && t < sg.e
            val radius = androidx.compose.ui.geometry.CornerRadius(bw / 2f, bw / 2f)
            if (active) {
                // 辉光
                drawRoundRect(
                    AvColors.red.copy(alpha = 0.22f),
                    topLeft = Offset(x0 - 1.5.dp.toPx(), h - bh - 1.5.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(bw + 3.dp.toPx(), bh + 3.dp.toPx()),
                    cornerRadius = radius
                )
            }
            drawRoundRect(
                if (active) AvColors.red else AvColors.red.copy(alpha = 0.20f),
                topLeft = Offset(x0, h - bh),
                size = androidx.compose.ui.geometry.Size(bw, bh),
                cornerRadius = radius
            )
        }
    }
}

/** 野外辨向 · 迷失生存技巧卡：默认收起，点击展开全部 */
@Composable
private fun SurvivalTipsCard(modifier: Modifier = Modifier) {
    val tips = listOf(
        "☀️ 太阳辨向：日出东方、日落西方；正午（北半球）太阳在正南，影子最短的一端指向北",
        "⌚ 手表辨向：时针对准太阳，时针与 12 点方向夹角的平分线即正南（北半球）",
        "🌟 夜间辨向：沿北斗七星勺口两颗星连线延长约 5 倍距离找到北极星，它永远指正北",
        "🌲 植被辨向：向阳面（南侧）枝叶更茂密、年轮间距更宽；树皮背阴面多生青苔",
        "⛰️ 迷路三步：先停下（STOP 原则），回忆最后确定位置的地标；登上制高点观察来路，原路返回最优先",
        "💧 寻找人烟：沿溪流顺水而下，下游更容易遇到村庄与道路；入夜停止移动，保存体力与手机电量"
    )
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .glassCard(isDark(), AvColors.green)
            .clickable { open = !open }
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🧭 野外辨向 · 迷失生存",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    if (open) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = AvColors.onColor(AvColors.green)
                )
            }
            if (open) {
                Spacer(Modifier.height(6.dp))
                tips.forEach { t ->
                    Text(
                        t,
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "太阳 / 手表 / 北极星 / 植被辨向 + 迷路 STOP 三步 · 点击展开",
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 一键分享位置卡：文本 + 高德/谷歌地图链接，系统分享面板 */
@Composable
private fun ShareLocationCard(shareText: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(
        modifier
            .fillMaxWidth()
            .height(124.dp)
            .glassCard(isDark(), AvColors.cyan)
            .clickable {
                if (shareText == null) {
                    Toast.makeText(ctx, "暂无定位，请开启 GPS 后重试", Toast.LENGTH_SHORT).show()
                } else {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                    }
                    ctx.startActivity(
                        android.content.Intent.createChooser(send, "分享我的位置")
                    )
                }
            }
            .padding(14.dp)
    ) {
        Column {
            Text(
                if (shareText == null) "等待定位…" else "📤 发送经纬度给好友",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            if (shareText != null) {
                Text(
                    "含高德 / 谷歌地图链接",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 轨迹详情统计项：大数值 + 小标签 */
@Composable
private fun TrackStat(value: String, label: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
