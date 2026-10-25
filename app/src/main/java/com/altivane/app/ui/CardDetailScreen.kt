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
                        stringResource(R.string.share_pos_text)
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
                                    cardinalName(s.headingDeg, locCtx),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = AvColors.onColor(accent)
                                )
                                Text(
                                    buildString {
                                        if (s.hasCompass) append(stringResource(R.string.compass_status_realtime)) else append(stringResource(R.string.compass_status_wait))
                                        if (s.tiltDeg >= 1f) append(stringResource(R.string.tilt_fmt, s.tiltDeg))
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
                                    stringResource(R.string.track_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (viewPts.size >= 2) {
                        // ② 4 格数据条（里程/时长/均速或配速/爬升）
                        val idle = s.phase == Phase.IDLE
                        // 口径按数据记录的运动类型：空闲查历史用落库 sportType，不用当前选择
                        val isRide = if (idle && lastRide != null)
                            lastRide!!.sportType == "RIDE" else s.sport == SportType.RIDE
                        val km = if (idle && lastRide != null) lastRide!!.distanceM / 1000.0 else s.distanceM / 1000.0
                        val dur = if (idle && lastRide != null) lastRide!!.movingS * 1000L else s.movingMs
                        val gain = if (idle && lastRide != null) lastRide!!.elevGainM
                        else remember(chartPts) { elevGainLoss(chartPts.map { it.ele }).first }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrackStatCell("%.2f".format(km), stringResource(R.string.unit_km_dist), null, Modifier.weight(1f))
                            TrackStatCell(Coach.fmtDur(dur), stringResource(R.string.card_duration), null, Modifier.weight(1f))
                            TrackStatCell(
                                if (isRide) "%.1f".format(
                                    if (dur > 0) km / (dur / 3600000.0) else 0.0
                                ) else Coach.paceText(if (dur > 0 && km > 0) dur / 60000.0 / km else 0.0),
                                if (isRide) stringResource(R.string.unit_avg_kmh) else stringResource(R.string.unit_pace_minkm),
                                null, Modifier.weight(1f)
                            )
                            TrackStatCell("%.0f".format(gain), stringResource(R.string.unit_m_climb), AvColors.orange, Modifier.weight(1f))
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
                                                stringResource(R.string.wp_tag), style = MaterialTheme.typography.labelSmall,
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
                                            stringResource(R.string.wp_last_fmt, lastWp.label),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1
                                        )
                                        Text(
                                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                                                .format(java.util.Date(lastWp.t)) + stringResource(R.string.wp_count_fmt, lastWps.size),
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
                                        stringResource(R.string.btn_share_image),
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
                                stringResource(R.string.profile_empty),
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
                            type == CardType.HR -> (if (liveHr > 0) liveHr.toString() else "--") to stringResource(R.string.ble_bpm)
                            type == CardType.CAD -> (if (liveCad >= 0) liveCad.toString() else "--") to "rpm"
                            // 气压计常开：空闲态也显示实时气压（纯 hPa，不回退 GPS 海拔）
                            type == CardType.BARO -> when {
                                !s.hasBaro -> "--" to stringResource(R.string.no_baro)
                                s.pressureHpa > 0 -> "%.1f".format(s.pressureHpa) to "hPa"
                                pressS.isNotEmpty() -> "%.1f".format(pressS.last().v) to "hPa"
                                else -> "--" to "hPa"
                            }
                            idle -> lastValueOf(type, lastRide, locCtx)
                            else -> valueOf(type, s, locCtx)
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
                    if (cardDesc(type, locCtx).isNotEmpty()) {
                        GlassCard(accent = accent.copy(alpha = 0.5f)) {
                            Text(
                                cardDesc(type, locCtx),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 打卡照片全屏放大（EXIF 转正 / 按比例占满 / 动态播放 / 相册查看）
    zoomPhoto?.let { p ->
        PhotoZoomDialog(p) { zoomPhoto = null }
    }
}

// ---------- 详情页丰富信息：趋势图 / 统计摘要 / 深度分析 ----------

private fun fmtChartVal(type: CardType, v: Double, ctx: android.content.Context): String = when (type) {
    CardType.HR -> ctx.getString(R.string.fmt_bpm_val, v.toInt())
    CardType.PACE, CardType.MAX_SPEED -> "%.1f km/h".format(v)
    CardType.CAD -> "${v.toInt()} rpm"
    CardType.DISTANCE -> "%.2f km".format(v)
    CardType.DURATION -> ctx.getString(R.string.fmt_min_val, v.toInt())
    CardType.BARO -> "%.1f hPa".format(v)
    CardType.ALTITUDE -> "%.0f m".format(v)
    else -> "%.1f".format(v)
}

/** 趋势图卡：专属图型 + 低/峰角标 */
@Composable
private fun DetailChartCard(
    type: CardType, chart: CardChart, accent: Color, pts: List<RidePoint>
) {
    val ctx = LocalContext.current
    GlassCard(Modifier.height(190.dp), accent = accent) {
        Column(Modifier.fillMaxSize()) {
            CardTitleRow(stringResource(R.string.trend_title), accent)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f)) {
                CardChartView(chart, Modifier.fillMaxSize(), accent)
                // 峰/低角标：ENERGY 分段与 HOURLY_BARS/MOVE_REST（自带峰值标注/图例）不显示，
                // 后者 series 是分桶/速度序列，min/max 角标无意义
                val showTicks = chart.kind != ChartKind.ENERGY &&
                        chart.kind != ChartKind.HOURLY_BARS && chart.kind != ChartKind.MOVE_REST
                if (showTicks && chart.series.size >= 2) {
                    Text(
                        stringResource(R.string.peak_fmt, fmtChartVal(type, chart.series.max(), ctx)),
                        Modifier.align(Alignment.TopEnd),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.low_fmt, fmtChartVal(type, chart.series.min(), ctx)),
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
            stringResource(R.string.lbl_current) to (if (idle) (hrs.lastOrNull()?.toString() ?: "--") else (if (s.hr > 0) s.hr.toString() else "--")),
            stringResource(R.string.lbl_avg) to (if (hrs.isNotEmpty()) "${hrs.average().toInt()} ${stringResource(R.string.ble_bpm)}" else "--"),
            stringResource(R.string.lbl_peak) to (if (hrs.isNotEmpty()) "${hrs.max()} ${stringResource(R.string.ble_bpm)}" else "--")
        )
        CardType.PACE -> listOf(
            stringResource(R.string.lbl_current) to Coach.paceText(if (idle) 0.0 else s.paceMinPerKm),
            stringResource(R.string.lbl_avg_speed) to "%.1f km/h".format(avgKmh),
            stringResource(R.string.lbl_peak_speed) to (if (spd.isNotEmpty()) "%.1f km/h".format(spd.max()) else "--"),
            stringResource(R.string.lbl_fastest_km) to (fastest?.let { Coach.paceText(it) } ?: "--")
        )
        CardType.DISTANCE -> {
            // 口径按运动类型：骑行=距离/均速/峰值时速/移动时间；徒步=距离/均速/移动时间/步数
            val sportName = if (idle) ride?.sportType ?: "HIKE" else s.sport.name
            val base = listOf(
                stringResource(R.string.card_distance) to "%.2f km".format(totalKm),
                stringResource(R.string.lbl_avg_speed) to "%.1f km/h".format(avgKmh),
                stringResource(R.string.lbl_moving) to Coach.fmtDur(movingMs)
            )
            if (sportName == "RIDE") base + (
                stringResource(R.string.lbl_peak_speed) to "%.1f km/h".format(
                    if (idle) (ride?.maxKmh ?: 0.0) else s.maxKmh
                )
                )
            else base + (
                stringResource(R.string.lbl_steps) to (
                    if (totalKm > 0.05) stringResource(R.string.unit_steps_fmt, (totalKm * 1000 / 0.72).toInt()) else "--"
                    )
                )
        }
        CardType.DURATION -> {
            val total = if (pts.size >= 2) pts.last().t - pts.first().t else 0L
            val rest = (total - movingMs).coerceAtLeast(0)
            listOf(
                stringResource(R.string.lbl_moving) to Coach.fmtDur(movingMs),
                stringResource(R.string.lbl_stay) to Coach.fmtDur(rest),
                stringResource(R.string.lbl_ratio) to (if (total > 0) "${(movingMs * 100 / total)}%" else "--")
            )
        }
        CardType.CAD -> listOf(
            stringResource(R.string.lbl_current) to (if (idle) (cads.lastOrNull()?.toString() ?: "--") else s.cad.toString()),
            stringResource(R.string.lbl_avg) to (if (cads.isNotEmpty()) "${cads.average().toInt()} rpm" else "--"),
            stringResource(R.string.lbl_peak) to (if (cads.isNotEmpty()) "${cads.max()} rpm" else "--")
        )
        CardType.CALS -> listOf(
            stringResource(R.string.lbl_total_cals) to "${kcal.toInt()} kcal",
            stringResource(R.string.lbl_per_km) to (if (totalKm > 0.2) "${(kcal / totalKm).toInt()} kcal" else "--"),
            stringResource(R.string.card_duration) to Coach.fmtDur(movingMs)
        )
        CardType.MAX_SPEED -> listOf(
            stringResource(R.string.lbl_peak) to "%.1f km/h".format(if (idle) (ride?.maxKmh ?: 0.0) else s.maxKmh),
            stringResource(R.string.lbl_avg) to (if (spd.isNotEmpty()) "%.1f km/h".format(spd.average()) else "--"),
            stringResource(R.string.card_distance) to "%.2f km".format(totalKm)
        )
        // 气压卡：只显示气压数据（hPa），海拔统计归高度卡；无气压计不渲染统计行
        CardType.BARO -> if (!s.hasBaro) emptyList() else listOf(
            stringResource(R.string.lbl_current) to (if (s.pressureHpa > 0) "%.1f hPa".format(s.pressureHpa) else "--"),
            stringResource(R.string.lbl_max) to (if (pressVals.isNotEmpty()) "%.1f hPa".format(pressVals.max()) else "--"),
            stringResource(R.string.lbl_min) to (if (pressVals.isNotEmpty()) "%.1f hPa".format(pressVals.min()) else "--")
        )
        // 高度卡：海拔 / 相对起点爬升 / 本次最高
        CardType.ALTITUDE -> listOf(
            stringResource(R.string.lbl_current) to (if (!idle && s.altBaroM != 0.0) "%.0f m".format(s.altBaroM)
            else if (!idle && s.relAltM != 0.0) "%.0f m".format(s.relAltM)
            else if (idle && ride != null) "%.0f m".format(ride.maxAltM) else "--"),
            stringResource(R.string.lbl_rel_start) to (if (!idle && s.baseAltSet) "%+.0f m".format(s.relAltM) else "--"),
            stringResource(R.string.lbl_total_climb) to (if (idle && ride != null) "%.0f m".format(ride.elevGainM) else "%.0f m".format(s.elevGainM))
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
            CardTitleRow(stringResource(R.string.weather_title), AvColors.violet)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.weather_na),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.weather_rest),
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
        now >= 1020.0 -> Triple(stringResource(R.string.wx_hi_t), AvColors.green,
            stringResource(R.string.wx_hi_d))
        now >= 1000.0 -> Triple(stringResource(R.string.wx_mid_t), AvColors.yellow,
            stringResource(R.string.wx_mid_d))
        else -> Triple(stringResource(R.string.wx_lo_t), AvColors.red,
            stringResource(R.string.wx_lo_d))
    }
    val trend = when {
        d3 <= -1.5 -> Triple(stringResource(R.string.wx_drop_fast_t, d3), AvColors.red,
            stringResource(R.string.wx_drop_fast_d))
        d3 <= -0.8 -> Triple(stringResource(R.string.wx_drop_slow_t, d3), AvColors.orange,
            stringResource(R.string.wx_drop_slow_d))
        d3 >= 1.5 -> Triple(stringResource(R.string.wx_rise_t, d3), AvColors.green,
            stringResource(R.string.wx_rise_d))
        else -> null
    }
    GlassCard(accent = AvColors.violet.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow(stringResource(R.string.weather_title), AvColors.violet)
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
            Text(stringResource(R.string.spec_title), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            listOf(
                "1026" to stringResource(R.string.spec_1026),
                "1013.25" to stringResource(R.string.spec_1013),
                "1000" to stringResource(R.string.spec_1000),
                "980" to stringResource(R.string.spec_980),
                "950" to stringResource(R.string.spec_950)
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
    val names = listOf(
        stringResource(R.string.zone_z1), stringResource(R.string.zone_z2), stringResource(R.string.zone_z3),
        stringResource(R.string.zone_z4), stringResource(R.string.zone_z5)
    )
    val colors = listOf(AvColors.blue, AvColors.green, AvColors.yellow, AvColors.orange, AvColors.red)
    GlassCard(accent = AvColors.pink.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow(stringResource(R.string.hr_zones_title), AvColors.pink)
            Spacer(Modifier.height(8.dp))
            if (total <= 0) {
                Text(
                    stringResource(R.string.hr_zones_empty),
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
            CardTitleRow(stringResource(R.string.pace_title), AvColors.yellow)
            Spacer(Modifier.height(6.dp))
            paces.take(12).forEachIndexed { i, p ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.km_fmt, i + 1),
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
                    stringResource(R.string.km_total_fmt, paces.size),
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
    val names = listOf(
        stringResource(R.string.int_low), stringResource(R.string.int_mid), stringResource(R.string.int_high)
    )
    val colors = listOf(AvColors.cyan, AvColors.yellow, AvColors.orange)
    GlassCard(accent = AvColors.orange.copy(alpha = 0.5f)) {
        Column {
            CardTitleRow(stringResource(R.string.intensity_title), AvColors.orange)
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
            CardTitleRow(stringResource(R.string.move_stay_title), AvColors.cyan)
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
                    stringResource(R.string.move_fmt, Coach.fmtDur(moving)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.stay_fmt, Coach.fmtDur(rest)),
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

private fun valueOf(t: CardType, s: LiveState, ctx: android.content.Context): Pair<String, String> = when (t) {
    CardType.DISTANCE -> "%.2f".format(s.distanceM / 1000.0) to "km"
    CardType.DURATION -> Coach.fmtDur(s.movingMs) to ""
    CardType.PACE -> Coach.paceText(s.paceMinPerKm) to "min/km"
    CardType.HR -> (if (s.hr > 0) s.hr.toString() else "--") to ctx.getString(R.string.ble_bpm)
    CardType.CAD -> (if (s.cad >= 0) s.cad.toString() else "--") to "rpm"
    CardType.CALS -> s.kcal.toInt().toString() to "kcal"
    CardType.MAX_SPEED -> "%.1f".format(s.maxKmh) to "km/h"
    CardType.COMPASS -> "%.0f".format(s.headingDeg) to ctx.getString(R.string.lbl_heading)
    CardType.BARO -> when {
        !s.hasBaro -> "--" to ctx.getString(R.string.no_baro)
        s.pressureHpa > 0 -> "%.1f".format(s.pressureHpa) to "hPa"
        else -> "--" to "hPa"
    }
    CardType.ALTITUDE -> when (s.sport) {
        SportType.HIKE -> when {
            s.altBaroM != 0.0 -> "%.0f".format(s.altBaroM) to ctx.getString(R.string.unit_m_alt)
            s.altM > 0.0 -> "%.0f".format(s.altM) to "m GPS"
            else -> "--" to ""
        }
        SportType.RIDE -> when {
            s.baseAltSet -> "%.0f".format(s.relAltM) to ctx.getString(R.string.unit_m_rel)
            else -> "--" to ""
        }
    }
    CardType.TRACK -> "" to ""
    CardType.PROFILE -> "" to ""
}

private fun lastValueOf(t: CardType, r: RideEntity?, ctx: android.content.Context): Pair<String, String> {
    if (r == null) return "--" to ctx.getString(R.string.last_ride_none)
    return when (t) {
        CardType.DISTANCE -> "%.2f".format(r.distanceM / 1000.0) to ctx.getString(R.string.unit_km_last)
        CardType.DURATION -> Coach.fmtDur(r.movingS * 1000L) to ctx.getString(R.string.unit_last)
        CardType.PACE -> if (r.avgKmh > 0.1) Coach.paceText(60.0 / r.avgKmh) to ctx.getString(R.string.unit_pace_avg)
        else "--" to ""
        CardType.HR -> "--" to ctx.getString(R.string.hr_hint_live)
        CardType.CAD -> "--" to ctx.getString(R.string.cad_hint_live)
        CardType.CALS -> r.kcal.toInt().toString() to ctx.getString(R.string.unit_kcal_last)
        CardType.MAX_SPEED -> "%.1f".format(r.maxKmh) to ctx.getString(R.string.unit_kmh_last)
        CardType.COMPASS -> "--" to ctx.getString(R.string.compass_hint_live)
        CardType.BARO -> "--" to ctx.getString(R.string.baro_hint_live)
        CardType.ALTITUDE -> "%.0f".format(r.maxAltM) to ctx.getString(R.string.unit_m_lastmax)
        CardType.TRACK -> "" to ""
        CardType.PROFILE -> "" to ""
    }
}

private fun cardDesc(t: CardType, ctx: android.content.Context): String = when (t) {
    CardType.DISTANCE -> ctx.getString(R.string.hint_distance)
    CardType.DURATION -> ctx.getString(R.string.hint_duration)
    CardType.PACE -> ctx.getString(R.string.hint_pace)
    CardType.HR -> ctx.getString(R.string.hint_hr)
    CardType.CAD -> ctx.getString(R.string.hint_cad)
    CardType.CALS -> ctx.getString(R.string.hint_cals)
    CardType.MAX_SPEED -> ctx.getString(R.string.hint_max_speed)
    CardType.COMPASS -> ctx.getString(R.string.hint_compass)
    CardType.BARO -> ""
    CardType.ALTITUDE -> ctx.getString(R.string.hint_altitude)
    CardType.TRACK -> ctx.getString(R.string.hint_track)
    CardType.PROFILE -> ctx.getString(R.string.hint_profile)
}

/** 方位名：16 方位（N / NNE / NE …），随应用语言 */
private fun cardinalName(deg: Float, ctx: android.content.Context): String {
    val d = ((deg % 360) + 360) % 360
    val names = listOf(
        R.string.dir_0, R.string.dir_1, R.string.dir_2, R.string.dir_3,
        R.string.dir_4, R.string.dir_5, R.string.dir_6, R.string.dir_7,
        R.string.dir_8, R.string.dir_9, R.string.dir_10, R.string.dir_11,
        R.string.dir_12, R.string.dir_13, R.string.dir_14, R.string.dir_15
    )
    return ctx.getString(names[Math.round(d / 22.5f).toInt() % 16])
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
                    Toast.makeText(ctx, ctx.getString(R.string.toast_no_gps), Toast.LENGTH_SHORT).show()
                } else {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                    }
                    ctx.startActivity(
                        android.content.Intent.createChooser(send, ctx.getString(R.string.share_pos_title))
                    )
                }
            }
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.btn_share_pos),
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
                    alt?.let { add(ctx.getString(R.string.alt_fmt, it)) }
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
                    stringResource(R.string.no_gps_outdoor),
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
                    Toast.makeText(ctx, ctx.getString(R.string.toast_sos_perm), Toast.LENGTH_LONG).show()
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
                    if (sos) stringResource(R.string.sos_on) else stringResource(R.string.sos_off),
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
                    stringResource(R.string.btn_torch),
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
        stringResource(R.string.survive_1),
        stringResource(R.string.survive_2),
        stringResource(R.string.survive_3),
        stringResource(R.string.survive_4),
        stringResource(R.string.survive_5),
        stringResource(R.string.survive_6)
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
                    stringResource(R.string.survive_title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    stringResource(if (open) R.string.collapse else R.string.expand),
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
                    stringResource(R.string.survive_sub),
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
                    Toast.makeText(ctx, ctx.getString(R.string.toast_no_gps), Toast.LENGTH_SHORT).show()
                } else {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                    }
                    ctx.startActivity(
                        android.content.Intent.createChooser(send, ctx.getString(R.string.share_pos_title))
                    )
                }
            }
            .padding(14.dp)
    ) {
        Column {
            Text(
                if (shareText == null) stringResource(R.string.wait_fix) else stringResource(R.string.send_pos_btn),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            if (shareText != null) {
                Text(
                    stringResource(R.string.share_pos_sub),
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
