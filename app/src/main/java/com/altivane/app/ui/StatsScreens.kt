package com.altivane.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.data.RideEntity
import com.altivane.app.domain.Coach
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/** 成就徽章：kind 决定矢量图形，hue 为点亮主题色 */
private data class Badge(
    val name: String,
    val cond: String,
    val value: Double,
    val target: Double,
    val kind: String,
    val hue: Color
)

/** 里程碑徽章 */
private fun milestoneBadges(km: Double, n: Double): List<Badge> = listOf(
    Badge("首次出发", "完成第 1 次运动", n, 1.0, "first", AvColors.yellow),
    Badge("渐入佳境", "累计 10 次运动", n, 10.0, "growth", AvColors.yellow),
    Badge("运动成瘾", "累计 50 次运动", n, 50.0, "addict", AvColors.orange),
    Badge("百公里", "累计骑行/徒步 100 km", km, 100.0, "km100", AvColors.cyan),
    Badge("五百公里", "累计 500 km", km, 500.0, "km500", AvColors.blue),
    Badge("千里走单骑", "累计 1000 km", km, 1000.0, "km1000", AvColors.purple)
)

/** 三山五岳徽章：徒步轨迹包围盒覆盖主峰坐标（±0.02° ≈ 2km）即视为登顶 */
private fun mountainBadges(hikeBounds: List<com.altivane.app.data.RideBound>): List<Badge> =
    com.altivane.app.domain.RoutesData.all.map { r ->
        val s = r.pts.last()
        val hit = hikeBounds.any { b ->
            s.lat >= b.minLat - 0.02 && s.lat <= b.maxLat + 0.02 &&
                    s.lon >= b.minLon - 0.02 && s.lon <= b.maxLon + 0.02
        }
        Badge(
            "${r.name}登顶", "${r.tag} · 徒步到达主峰",
            if (hit) 1.0 else 0.0, 1.0,
            "mt_${r.name}", AvColors.green
        )
    }

/** 知名打卡景点徽章：任意运动轨迹包围盒覆盖景点坐标（±0.03°）即打卡成功 */
private data class Spot(val name: String, val lat: Double, val lon: Double, val kind: String, val hue: Color)

private val Spots = listOf(
    Spot("杭州西湖", 30.2489, 120.1487, "sp_xihu", AvColors.teal),
    Spot("上海外滩", 31.2397, 121.4903, "sp_bund", AvColors.blue),
    Spot("北京故宫", 39.9163, 116.3972, "sp_gugong", AvColors.red),
    Spot("八达岭长城", 40.3597, 116.0199, "sp_badaling", AvColors.orange),
    Spot("厦门鼓浪屿", 24.4446, 118.0672, "sp_gulangyu", AvColors.pink),
    Spot("大理洱海", 25.7580, 100.1830, "sp_erhai", AvColors.cyan),
    Spot("张家界武陵源", 29.3480, 110.5410, "sp_zhangjiajie", AvColors.green),
    Spot("桂林阳朔", 24.7736, 110.4930, "sp_yangshuo", AvColors.yellow)
)

private fun spotBadges(bounds: List<com.altivane.app.data.RideBound>): List<Badge> =
    Spots.map { sp ->
        val hit = bounds.any { b ->
            sp.lat >= b.minLat - 0.03 && sp.lat <= b.maxLat + 0.03 &&
                    sp.lon >= b.minLon - 0.03 && sp.lon <= b.maxLon + 0.03
        }
        Badge("${sp.name}打卡", "骑行或徒步到达此地", if (hit) 1.0 else 0.0, 1.0, sp.kind, sp.hue)
    }

/** 我的成就：全屏详情页（徽章网格 + 进度条） */
@Composable
fun AchievementsScreen(app: RideApp, onBack: () -> Unit) {
    val rides by app.db.dao().rides().collectAsState(initial = emptyList())

    // 轨迹包围盒（登山/打卡判定，一次读取）
    var bounds by remember { mutableStateOf<List<com.altivane.app.data.RideBound>>(emptyList()) }
    LaunchedEffect(rides.size) {
        bounds = app.db.dao().rideBounds()
    }

    val km = rides.sumOf { it.distanceM } / 1000.0
    val n = rides.size.toDouble()
    val hikeBounds = bounds.filter { b ->
        try {
            com.altivane.app.domain.SportType.valueOf(
                rides.firstOrNull { it.id == b.rideId }?.sportType ?: ""
            ) == com.altivane.app.domain.SportType.HIKE
        } catch (_: Exception) { false }
    }
    val badges = milestoneBadges(km, n) + mountainBadges(hikeBounds) + spotBadges(bounds)
    val got = badges.count { it.value >= it.target }

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column {
                Text(stringResource(R.string.menu_achv), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.achv_progress, got, badges.size),
                    style = MaterialTheme.typography.labelMedium, color = AvColors.yellow)
            }
        }

        // 头部大卡：完成度
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .glassCard(isDark(), null)
                .padding(16.dp)
        ) {
            Column {
                Text(
                    stringResource(R.string.achv_done_fmt, got * 100 / badges.size),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 34.sp),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(got.toFloat() / badges.size)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AvColors.yellow)
                    )
                }
            }
        }

        // 徽章网格
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(badges) { b ->
                val done = b.value >= b.target
                val ratio = (b.value / b.target).coerceIn(0.0, 1.0).toFloat()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassCard(isDark(), null)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                (if (done) b.hue else MaterialTheme.colorScheme.onSurfaceVariant)
                                    .copy(alpha = if (done) 0.16f else 0.08f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        BadgeIcon(b.kind, done, b.hue, Modifier.size(34.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        b.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (done) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        b.cond,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (done) b.hue else AvColors.cyan)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (done) stringResource(R.string.badge_done) else "%.0f%%".format(ratio * 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (done) AvColors.yellow else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 徽章矢量图标：kind → 独立图形（点亮 fg=主题色，未点亮=灰） */
@Composable
private fun BadgeIcon(kind: String, done: Boolean, hue: Color, modifier: Modifier = Modifier) {
    val fg = if (done) hue else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) { drawBadgeArt(kind, fg) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBadgeArt(kind: String, fg: Color) {
    val w = size.width
    val h = size.height
    val stroke = 2.6.dp.toPx()
    fun P(x: Float, y: Float) = Offset(w * x, h * y)
    fun path(vararg pts: Pair<Float, Float>, close: Boolean = false): Path =
        Path().apply {
            moveTo(w * pts[0].first, h * pts[0].second)
            pts.drop(1).forEach { lineTo(w * it.first, h * it.second) }
            if (close) close()
        }
    fun stroked(p: Path, width: Float = stroke, c: Color = fg) =
        drawPath(p, c, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    fun filled(p: Path, c: Color = fg, alpha: Float = 1f) = drawPath(p, c.copy(alpha = alpha))
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, wd: Float = stroke, c: Color = fg) =
        drawLine(c, P(x1, y1), P(x2, y2), wd, cap = StrokeCap.Round)

    // 通用山形（三峰）
    fun mountain(fill: Boolean = true) {
        val m = path(
            0.08f to 0.80f, 0.30f to 0.36f, 0.46f to 0.62f,
            0.62f to 0.28f, 0.90f to 0.80f, close = true
        )
        if (fill) filled(m, fg, 0.18f)
        stroked(m)
    }

    when (kind) {
        // ---- 里程碑 ----
        "first" -> {   // 旗帜：出发
            line(0.36f, 0.14f, 0.36f, 0.86f, stroke * 1.3f)
            filled(path(0.36f to 0.16f, 0.80f to 0.30f, 0.36f to 0.44f))
            line(0.22f, 0.86f, 0.62f, 0.86f)
        }
        "growth" -> {  // 上升折线 + 箭头
            stroked(path(0.14f to 0.78f, 0.40f to 0.56f, 0.58f to 0.66f, 0.84f to 0.24f))
            stroked(path(0.84f to 0.24f, 0.66f to 0.26f))
            stroked(path(0.84f to 0.24f, 0.80f to 0.42f))
        }
        "addict" -> {  // 火焰
            val flame = Path().apply {
                moveTo(w * 0.50f, h * 0.10f)
                cubicTo(w * 0.78f, h * 0.36f, w * 0.74f, h * 0.66f, w * 0.50f, h * 0.88f)
                cubicTo(w * 0.26f, h * 0.66f, w * 0.22f, h * 0.36f, w * 0.50f, h * 0.10f)
                close()
            }
            filled(flame)
            // 内焰（同色半透明叠加）
            val inner = Path().apply {
                moveTo(w * 0.50f, h * 0.34f)
                cubicTo(w * 0.62f, h * 0.48f, w * 0.60f, h * 0.62f, w * 0.50f, h * 0.74f)
                cubicTo(w * 0.40f, h * 0.62f, w * 0.38f, h * 0.48f, w * 0.50f, h * 0.34f)
                close()
            }
            filled(inner, fg, 0.55f)
        }
        "km100" -> {   // 三重环
            drawCircle(fg, radius = w * 0.40f, style = Stroke(stroke))
            drawCircle(fg, radius = w * 0.27f, style = Stroke(stroke * 0.8f))
            drawCircle(fg, radius = w * 0.10f)
        }
        "km500" -> {   // 盾形
            stroked(path(
                0.50f to 0.10f, 0.84f to 0.24f, 0.80f to 0.60f,
                0.50f to 0.88f, 0.20f to 0.60f, 0.16f to 0.24f, close = true
            ))
            line(0.32f, 0.44f, 0.68f, 0.44f)
            line(0.36f, 0.58f, 0.64f, 0.58f)
        }
        "km1000" -> {  // 车轮
            drawCircle(fg, radius = w * 0.40f, style = Stroke(stroke * 1.2f))
            drawCircle(fg, radius = w * 0.11f, style = Stroke(stroke))
            for (i in 0 until 6) {
                val a = Math.toRadians((i * 60f).toDouble())
                line(
                    (0.50f + 0.11f * sin(a)).toFloat(), (0.50f - 0.11f * cos(a)).toFloat(),
                    (0.50f + 0.36f * sin(a)).toFloat(), (0.50f - 0.36f * cos(a)).toFloat(),
                    stroke * 0.8f
                )
            }
        }

        // ---- 三山五岳 ----
        "mt_泰山" -> {   // 旭日东升
            mountain()
            drawArc(fg, 180f, 180f, true,
                topLeft = Offset(w * 0.62f, h * 0.06f),
                size = Size(w * 0.26f, h * 0.26f))
            line(0.58f, 0.19f, 0.92f, 0.19f, stroke * 0.7f)
        }
        "mt_华山" -> {   // 险峰陡崖
            val m = path(0.08f to 0.82f, 0.34f to 0.18f, 0.52f to 0.56f,
                0.68f to 0.24f, 0.92f to 0.82f, close = true)
            filled(m, fg, 0.18f)
            stroked(m)
            line(0.42f, 0.38f, 0.42f, 0.72f, stroke * 0.7f)
            line(0.50f, 0.48f, 0.50f, 0.76f, stroke * 0.7f)
        }
        "mt_衡山" -> {   // 山巅香火（小庙）
            mountain()
            stroked(path(0.62f to 0.26f, 0.74f to 0.14f, 0.86f to 0.26f))
            line(0.66f, 0.26f, 0.66f, 0.40f, stroke * 0.8f)
            line(0.82f, 0.26f, 0.82f, 0.40f, stroke * 0.8f)
            line(0.60f, 0.40f, 0.88f, 0.40f, stroke * 0.8f)
        }
        "mt_恒山" -> {   // 北地悬空（雪花星）
            mountain()
            val cx = 0.74f; val cy = 0.20f; val r = 0.12f
            line(cx, cy - r, cx, cy + r, stroke * 0.8f)
            line(cx - r * 0.87f, cy - r * 0.5f, cx + r * 0.87f, cy + r * 0.5f, stroke * 0.8f)
            line(cx - r * 0.87f, cy + r * 0.5f, cx + r * 0.87f, cy - r * 0.5f, stroke * 0.8f)
            drawCircle(fg, radius = 2.dp.toPx(), center = P(cx, cy))
        }
        "mt_嵩山" -> {   // 少林密檐塔
            mountain()
            line(0.74f, 0.08f, 0.74f, 0.40f, stroke * 1.1f)
            stroked(path(0.74f to 0.06f, 0.68f to 0.14f, 0.80f to 0.14f, close = true), stroke * 0.7f)
            line(0.68f, 0.18f, 0.80f, 0.18f, stroke * 0.7f)
            line(0.69f, 0.27f, 0.79f, 0.27f, stroke * 0.7f)
            line(0.70f, 0.36f, 0.78f, 0.36f, stroke * 0.7f)
        }
        "mt_黄山" -> {   // 迎客松
            mountain()
            line(0.72f, 0.36f, 0.78f, 0.12f, stroke * 1.1f)          // 斜干
            stroked(path(0.78f to 0.12f, 0.62f to 0.10f, 0.58f to 0.20f), stroke * 0.8f)  // 迎客侧枝
            stroked(path(0.76f to 0.20f, 0.88f to 0.16f, 0.90f to 0.26f), stroke * 0.8f)
        }
        "mt_庐山" -> {   // 飞瀑
            mountain()
            val fall = Path().apply {
                moveTo(w * 0.72f, h * 0.34f)
                quadraticBezierTo(w * 0.68f, h * 0.48f, w * 0.72f, h * 0.60f)
                quadraticBezierTo(w * 0.75f, h * 0.70f, w * 0.71f, h * 0.80f)
            }
            stroked(fall, stroke * 0.8f)
            val fall2 = Path().apply {
                moveTo(w * 0.80f, h * 0.34f)
                quadraticBezierTo(w * 0.76f, h * 0.48f, w * 0.80f, h * 0.60f)
                quadraticBezierTo(w * 0.83f, h * 0.70f, w * 0.79f, h * 0.80f)
            }
            stroked(fall2, stroke * 0.8f)
        }
        "mt_雁荡山" -> { // 雁阵
            mountain()
            listOf(0.22f to 0.16f, 0.44f to 0.10f).forEach { (cx, cy) ->
                stroked(path(cx to cy, (cx + 0.07f) to (cy - 0.05f), (cx + 0.14f) to cy), stroke * 0.8f)
            }
        }

        // ---- 知名打卡景点 ----
        "sp_xihu" -> {  // 三潭印月
            val wave = Path().apply {
                moveTo(w * 0.08f, h * 0.72f)
                quadraticBezierTo(w * 0.26f, h * 0.64f, w * 0.46f, h * 0.72f)
                quadraticBezierTo(w * 0.66f, h * 0.80f, w * 0.92f, h * 0.70f)
            }
            stroked(wave, stroke * 0.9f)
            listOf(0.28f, 0.50f, 0.72f).forEach { x ->
                line(x, 0.30f, x, 0.58f, stroke * 0.9f)
                drawCircle(fg, radius = 3.2.dp.toPx(), center = P(x, 0.30f))
                drawCircle(fg, radius = 2.2.dp.toPx(), center = P(x, 0.40f))
            }
        }
        "sp_bund" -> {  // 天际线
            filled(path(0.12f to 0.78f, 0.12f to 0.42f, 0.34f to 0.42f, 0.34f to 0.78f, close = true), fg, 0.55f)
            filled(path(0.40f to 0.78f, 0.40f to 0.30f, 0.62f to 0.30f, 0.62f to 0.78f, close = true), fg, 0.80f)
            filled(path(0.68f to 0.78f, 0.68f to 0.50f, 0.90f to 0.50f, 0.90f to 0.78f, close = true), fg, 0.55f)
            filled(path(0.42f to 0.30f, 0.51f to 0.14f, 0.60f to 0.30f, close = true))
            line(0.51f, 0.14f, 0.51f, 0.06f, stroke * 0.7f)
        }
        "sp_gugong" -> { // 城楼
            filled(path(0.16f to 0.80f, 0.20f to 0.56f, 0.80f to 0.56f, 0.84f to 0.80f, close = true), fg, 0.30f)
            // 双层檐（端部上翘）
            stroked(path(0.14f to 0.50f, 0.50f to 0.42f, 0.86f to 0.50f), stroke * 1.1f)
            stroked(path(0.22f to 0.34f, 0.50f to 0.28f, 0.78f to 0.34f), stroke)
            // 门洞
            val arch = Path().apply {
                moveTo(w * 0.44f, h * 0.80f)
                lineTo(w * 0.44f, h * 0.66f)
                quadraticBezierTo(w * 0.50f, h * 0.56f, w * 0.56f, h * 0.66f)
                lineTo(w * 0.56f, h * 0.80f)
            }
            stroked(arch, stroke * 0.9f)
        }
        "sp_badaling" -> { // 长城垛口
            val wall = path(0.06f to 0.84f, 0.22f to 0.52f, 0.94f to 0.52f, 0.94f to 0.84f, close = true)
            filled(wall, fg, 0.28f)
            stroked(wall)
            // 垛口
            listOf(0.30f, 0.48f, 0.66f, 0.82f).forEach { x ->
                filled(path(x to 0.52f, x to 0.40f, (x + 0.08f) to 0.40f, (x + 0.08f) to 0.52f, close = true))
            }
            line(0.22f, 0.66f, 0.94f, 0.66f, stroke * 0.6f)
        }
        "sp_gulangyu" -> { // 钢琴之岛
            val piano = path(0.18f to 0.34f, 0.82f to 0.34f, 0.82f to 0.50f, 0.18f to 0.50f, close = true)
            filled(piano, fg, 0.75f)
            // 琴键
            for (i in 0 until 7) {
                val x = 0.20f + i * 0.088f
                line(x, 0.50f, x, 0.62f, stroke * 0.7f)
            }
            line(0.18f, 0.62f, 0.82f, 0.62f, stroke * 0.8f)
            val wave = Path().apply {
                moveTo(w * 0.10f, h * 0.80f)
                quadraticBezierTo(w * 0.30f, h * 0.72f, w * 0.50f, h * 0.80f)
                quadraticBezierTo(w * 0.70f, h * 0.88f, w * 0.90f, h * 0.78f)
            }
            stroked(wave, stroke * 0.8f)
        }
        "sp_erhai" -> {  // 月牙洱海 + 苍山
            stroked(path(0.10f to 0.34f, 0.34f to 0.16f, 0.58f to 0.30f, 0.88f to 0.18f), stroke)
            val moon = Path().apply {
                moveTo(w * 0.14f, h * 0.56f)
                quadraticBezierTo(w * 0.50f, h * 0.40f, w * 0.86f, h * 0.56f)
                quadraticBezierTo(w * 0.50f, h * 0.76f, w * 0.14f, h * 0.56f)
                close()
            }
            filled(moon, fg, 0.45f)
            stroked(moon, stroke * 0.8f)
        }
        "sp_zhangjiajie" -> { // 峰林石柱
            listOf(
                Triple(0.14f, 0.30f, 0.46f), Triple(0.34f, 0.18f, 0.58f),
                Triple(0.54f, 0.36f, 0.48f), Triple(0.72f, 0.24f, 0.52f)
            ).forEach { (x, top, bottom) ->
                val col = path(x to bottom, x to top, (x + 0.12f) to top, (x + 0.12f) to bottom, close = true)
                filled(col, fg, 0.55f)
                stroked(col, stroke * 0.7f)
            }
            line(0.06f, 0.82f, 0.94f, 0.82f, stroke * 0.9f)
        }
        "sp_yangshuo" -> { // 喀斯特山水 + 竹筏
            val hill1 = path(0.10f to 0.58f, 0.30f to 0.20f, 0.50f to 0.58f, close = true)
            filled(hill1, fg, 0.45f)
            val hill2 = path(0.46f to 0.58f, 0.64f to 0.30f, 0.86f to 0.58f, close = true)
            filled(hill2, fg, 0.28f)
            stroked(hill1, stroke * 0.8f); stroked(hill2, stroke * 0.7f)
            val wave = Path().apply {
                moveTo(w * 0.08f, h * 0.72f)
                quadraticBezierTo(w * 0.30f, h * 0.66f, w * 0.52f, h * 0.72f)
                quadraticBezierTo(w * 0.72f, h * 0.77f, w * 0.92f, h * 0.70f)
            }
            stroked(wave, stroke * 0.8f)
            line(0.28f, 0.84f, 0.72f, 0.84f, stroke * 1.1f)  // 竹筏
        }
    }
}

/** 年度报告：全屏详情页（年度大数字 + 月度柱状图 + 类型占比） */
@Composable
fun YearlyReportScreen(app: RideApp, onBack: () -> Unit) {
    val rides by app.db.dao().rides().collectAsState(initial = emptyList())
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val yr = rides.filter {
        val c = Calendar.getInstance()
        c.timeInMillis = it.startTime
        c.get(Calendar.YEAR) == year
    }

    val yrKm = yr.sumOf { it.distanceM } / 1000.0
    val yrH = yr.sumOf { it.movingS } / 3600.0
    val yrKcal = yr.sumOf { it.kcal }.toInt()
    val longest = yr.maxByOrNull { it.distanceM }

    // 按骑行 / 徒步分类
    val rideList = yr.filter {
        try { com.altivane.app.domain.SportType.valueOf(it.sportType) == com.altivane.app.domain.SportType.RIDE } catch (_: Exception) { false }
    }
    val hikeList = yr.filter {
        try { com.altivane.app.domain.SportType.valueOf(it.sportType) == com.altivane.app.domain.SportType.HIKE } catch (_: Exception) { false }
    }
    fun monthlyOf(list: List<RideEntity>): FloatArray {
        val arr = FloatArray(12)
        list.forEach { r ->
            val c = Calendar.getInstance()
            c.timeInMillis = r.startTime
            arr[c.get(Calendar.MONTH)] += (r.distanceM / 1000.0).toFloat()
        }
        return arr
    }
    val monthlyRide = monthlyOf(rideList)
    val monthlyHike = monthlyOf(hikeList)
    val maxM = maxOf(monthlyRide.max(), monthlyHike.max())

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column {
                Text(stringResource(R.string.report_year_fmt, year), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.report_sub), style = MaterialTheme.typography.labelMedium,
                    color = AvColors.cyan)
            }
        }

        if (yr.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .glassCard(isDark(), null),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.report_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        // 年度大数字
        Box(
            Modifier
                .fillMaxWidth()
                .glassCard(isDark(), null)
                .padding(16.dp)
        ) {
            Column {
                Text(stringResource(R.string.report_km_fmt, year), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.0f".format(yrKm), fontSize = 64.sp, fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("  km", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 四宫格统计
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(stringResource(R.string.stat_workouts), "${yr.size}", stringResource(R.string.unit_count), AvColors.cyan, Modifier.weight(1f))
            StatCard(stringResource(R.string.stat_total_dur), "%.0f".format(yrH), stringResource(R.string.unit_hours), AvColors.blue, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(stringResource(R.string.lbl_total_cals), "$yrKcal", "kcal", AvColors.pink, Modifier.weight(1f))
            StatCard(stringResource(R.string.lbl_per_km), "%.1f".format(yrKm / 12), "km", AvColors.violet, Modifier.weight(1f))
        }

        // 骑行 / 徒步分类统计（月度里程双色彩条）
        listOf(
            Triple(stringResource(R.string.sport_ride), rideList, monthlyRide) to AvColors.cyan,
            Triple(stringResource(R.string.sport_hike), hikeList, monthlyHike) to AvColors.green
        ).forEach { (data, accent) ->
            val (name, list, monthly) = data
            val km = list.sumOf { it.distanceM } / 1000.0
            val h = list.sumOf { it.movingS } / 3600.0
            val kcal = list.sumOf { it.kcal }.toInt()
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassCard(isDark(), accent)
                    .padding(14.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.weight(1f))
                        Text("${list.size} ${stringResource(R.string.unit_count)}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "%.1f km · %.0f %s · $kcal kcal".format(km, h, stringResource(R.string.unit_hours)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        val w = size.width
                        val h2 = size.height
                        if (maxM > 0f) {
                            val bw = w / 30f
                            for (i in 0 until 12) {
                                val x = (i + 0.5f) * w / 12f
                                val rh = (h2 * monthly[i] / maxM).coerceAtLeast(if (monthly[i] > 0f) 2.dp.toPx() else 0f)
                                if (rh > 0f) {
                                    drawRoundRect(
                                        accent,
                                        topLeft = Offset(x - bw, h2 - rh),
                                        size = Size(bw, rh),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(R.string.month_1, R.string.month_4, R.string.month_7, R.string.month_10, R.string.month_12).forEach { m ->
                        Text(stringResource(m), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    }
                }
            }
        }

        // 最佳一次
        if (longest != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassCard(isDark(), null)
                    .padding(14.dp)
            ) {
                Column {
                    Text(stringResource(R.string.yearly_best), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "%.2f km · %s".format(
                            longest.distanceM / 1000.0,
                            Coach.fmtDur(longest.movingS * 1000L)
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
