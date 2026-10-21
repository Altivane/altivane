package com.altivane.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 「暗夜竞速」卡片：平色卡底 + 发丝描边（中性色，无边框着色）。
 * 不做渐变 / blur（性能红线）。
 */
fun Modifier.glassCard(isDark: Boolean, accent: Color? = null): Modifier = this
    .clip(RoundedCornerShape(24.dp))
    .background(if (isDark) Color(0xFF1A2230) else Color.White)
    .border(
        0.5.dp,
        if (isDark) Color(0xFF2A3245) else Color(0xFFE2E5E1),
        RoundedCornerShape(24.dp)
    )

@Composable
fun isDark(): Boolean = AppTheme.dark()

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit
) {
    val dark = isDark()
    Box(
        modifier
            .fillMaxWidth()
            .glassCard(dark, accent)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) { content() }
}

/** 卡片标题行：accent 色条 + 标签（+ 可选蓝牙徽标） */
@Composable
fun CardTitleRow(label: String, accent: Color, badge: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 4.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                maxLines = 1
            )
        }
        if (badge) {
            Spacer(Modifier.width(5.dp))
            BleIcon(Modifier.size(12.dp), tint = AvColors.blue)
        }
    }
}

/** 数据卡：accent 色条 + 标签 + 大数值。
 *  fill=true（首页小卡）：上标题、中数值、下专属图表（chart），数值自适应字号。 */
@Composable
fun StatCard(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
    valueSize: Int = 30,
    fill: Boolean = false,
    chart: CardChart? = null,
    bleBadge: Boolean = false
) {
    val dark = isDark()
    if (!fill) {
        Column(
            modifier
                .fillMaxWidth()
                .glassCard(dark, accent)
                .padding(14.dp)
        ) {
            CardTitleRow(label, accent)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = valueSize.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1
                )
                if (unit.isNotEmpty()) {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    // fill 模式（参考布局）：左对齐 —— 标题 / 大数值（单位随行）/ 图表贴底
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .glassCard(dark, accent)
    ) {
        val hasChart = chart != null && (chart.series.size >= 2 || (chart.kind == ChartKind.ENERGY && chart.split.sum() > 0.0))
        val pad = 13.dp
        val availW = maxWidth - pad * 2
        val titleH = 20.dp
        val chartH = if (hasChart) (maxHeight * 0.42f).coerceIn(30.dp, 56.dp) else 0.dp
        val unitH = if (unit.isNotEmpty()) 16.dp else 0.dp
        val availH = maxHeight - 22.dp - titleH - 4.dp - unitH - 6.dp - chartH
        val chars = value.sumOf { if (it.code > 0x2E80) 1.0 else 0.56 }.toFloat().coerceAtLeast(1f)
        val byH = availH.value * 0.94f
        val byW = availW.value / (chars * 1.14f)
        val cap = if (hasChart) 36f else 50f
        val fs = minOf(byH, byW).coerceIn(15f, cap)

        Column(
            Modifier
                .fillMaxSize()
                .padding(start = pad, end = pad, top = 11.dp, bottom = 11.dp)
        ) {
            CardTitleRow(label, accent, bleBadge)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = fs.sp,
                    lineHeight = (fs * 1.06f).sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
                if (unit.isNotEmpty()) {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (hasChart && chart != null) {
                CardChartView(
                    chart, accent = accent, mini = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartH)
                )
            }
        }
    }
}

/** 航向 → 8 向方位名（中文 + 英文缩写），供罗盘各处显示 */
fun headingName(h: Float): String {
    val cn = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    val en = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val i = (((h % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8
    return "${cn[i]} ${en[i]}"
}

/**
 * 罗盘（量子环版）：外圈青色扫描弧 + 细密刻度环 + 主刻度环 + 直立方位字母（N 红）
 * + 红色 N 标随盘转 + 顶部固定青色索引线 + 中心航向数字/方位名。
 * showCenter=false 时为 mini 盘（无中心读数，用于扁卡右侧）。
 */
@Composable
fun CompassDial(heading: Float, modifier: Modifier = Modifier, showCenter: Boolean = true) {
    val fg = MaterialTheme.colorScheme.onBackground
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val names = listOf("N", "E", "S", "W")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 2.dp.toPx()
            // 外圈青色扫描弧（装饰段）
            val ext = 2.dp.toPx()
            drawArc(
                AvColors.cyan, -60f, 70f, false,
                topLeft = Offset(c.x - r - ext, c.y - r - ext),
                size = Size((r + ext) * 2f, (r + ext) * 2f),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            )
            rotate(degrees = -heading, pivot = c) {
                // 细密刻度环（72）
                for (i in 0 until 72) {
                    val a = Math.toRadians((i * 5f).toDouble())
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    drawLine(
                        dim, c + dir * r, c + dir * (r - r * 0.055f),
                        strokeWidth = 0.8.dp.toPx(), cap = StrokeCap.Round
                    )
                }
                // 主刻度环（12）
                for (i in 0 until 12) {
                    val a = Math.toRadians((i * 30f).toDouble())
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    drawLine(
                        fg, c + dir * (r * 0.86f), c + dir * (r * 0.74f),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                    )
                }
                // 红色 N 标（尖朝外的三角，随盘转）
                val tri = Path().apply {
                    moveTo(c.x, c.y - r * 0.94f)
                    lineTo(c.x - 3.2.dp.toPx(), c.y - r * 0.94f + 6.5.dp.toPx())
                    lineTo(c.x + 3.2.dp.toPx(), c.y - r * 0.94f + 6.5.dp.toPx())
                    close()
                }
                drawPath(tri, AvColors.red)
            }
            // 顶部固定青色索引线（当前朝向）
            drawLine(
                AvColors.cyan, Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.90f),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
        }
        // 方位字母：按角度定位（位置随 heading 转，字形保持直立）
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val rr = (minOf(maxWidth, maxHeight) * 0.30f)
            names.forEachIndexed { idx, n ->
                val a = Math.toRadians((idx * 90f - heading).toDouble())
                Text(
                    n,
                    Modifier
                        .align(Alignment.Center)
                        .offset {
                            IntOffset(
                                (sin(a) * rr.toPx()).toInt(),
                                (-cos(a) * rr.toPx()).toInt()
                            )
                        },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (idx == 0) Color(0xFFFF5252) else dim
                )
            }
        }
        if (showCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${heading.toInt()}°",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 20.sp, fontWeight = FontWeight.Black
                    ),
                    color = fg
                )
                Text(headingName(heading), fontSize = 9.sp, color = dim)
            }
        }
    }
}

/** 曲线彩条调色板：低 → 高 */
private val CurvePalette = listOf(
    Color(0xFF5B9DFF), Color(0xFF40E0FF), Color(0xFFA3E635),
    Color(0xFFFFD84D), Color(0xFFFF9F45), Color(0xFFFF6B8A)
)

/** 数值归一化 → 彩条颜色 */
fun curveColor(norm: Float): Color {
    val t = norm.coerceIn(0f, 1f) * (CurvePalette.size - 1)
    val i = t.toInt().coerceAtMost(CurvePalette.size - 2)
    return androidx.compose.ui.graphics.lerp(CurvePalette[i], CurvePalette[i + 1], t - i)
}

/** 曲线 sparkline：按数值高低逐段彩条着色（低=蓝、高=红） */
@Composable
fun Sparkline(data: List<Double>, accent: Color = AvColors.cyan, modifier: Modifier = Modifier, showScale: Boolean = false) {
    Canvas(modifier) {
        if (data.size < 2) return@Canvas
        val min = data.min()
        val max = data.max()
        val span = (max - min).takeIf { it > 1.0 } ?: 1.0
        val w = size.width
        val h = size.height
        var prev: Offset? = null
        // 标尺参考线：最高/最低两条点划线（配合卡片头部的数值标注）
        if (showScale) {
            val dim = accent.copy(alpha = 0.30f)
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            val yMax = (h * 0.04f)
            val yMin = (h * 0.96f)
            drawLine(dim, Offset(0f, yMax), Offset(w, yMax), 1.dp.toPx(), pathEffect = dash)
            drawLine(dim, Offset(0f, yMin), Offset(w, yMin), 1.dp.toPx(), pathEffect = dash)
        }
        data.forEachIndexed { i, v ->
            val x = w * i / (data.size - 1)
            val y = (h * (1.0 - (v - min) / span)).toFloat()
            val cur = Offset(x, y)
            prev?.let { p ->
                drawLine(
                    curveColor(((v - min) / span).toFloat()), p, cur,
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                )
            }
            prev = cur
        }
    }
}

/**
 * 海拔剖面（立体感）：曲线下方渐变填充到谷底，线用 accent。
 * 高度归一化带最小可视幅度，平地不至于塌成直线。
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawElevationProfile(
    data: List<Double>,
    accent: Color,
    lineW: Float
) {
    if (data.size < 2) return
    val min = data.min()
    val max = data.max()
    val span = (max - min).coerceAtLeast(8.0)
    val w = size.width
    val h = size.height
    val line = Path()
    data.forEachIndexed { i, v ->
        val x = w * i / (data.size - 1)
        val y = (h * 0.88f * (1.0 - (v - min) / span)).toFloat()
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    val fill = Path().apply {
        addPath(line)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(
        fill,
        androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f)),
            startY = 0f, endY = h
        )
    )
    drawPath(
        line,
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(Color(0xFF40E0FF), Color(0xFFA3E635), Color(0xFFFFD84D), Color(0xFFFF9F45)),
            startX = 0f, endX = w
        ),
        style = Stroke(lineW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

@Composable
fun ElevationSpark(data: List<Double>, accent: Color = AvColors.orange, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawElevationProfile(data, accent, 1.8.dp.toPx()) }
}

/** 蓝牙图标（自绘 rune） */
@Composable
fun BleIcon(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onBackground) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.30f, h * 0.25f)
            lineTo(w * 0.70f, h * 0.75f)
            lineTo(w * 0.50f, h * 0.92f)
            lineTo(w * 0.50f, h * 0.08f)
            lineTo(w * 0.70f, h * 0.25f)
            lineTo(w * 0.30f, h * 0.75f)
        }
        drawPath(p, tint, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

data class TrackPt(val lat: Double, val lon: Double)
data class WpPt(val lat: Double, val lon: Double, val kind: String, val label: String, val photo: String? = null)

/**
 * 彩虹轨迹：速度 → AvColors.rainbow 逐段着色；打卡点画圆点。
 * 绘制前统一走 TrackGeom.prepareIndices 平滑管道（滑窗平滑 + 距离等距重采样 + DP 简化），
 * 长轨迹（15km+ 数千点）自适应压缩到 ≤maxPts，任意画布全程显示不卡顿。
 */
object TrackGeom {
    data class Fit(
        val latMin: Double, val latMax: Double,
        val lonMin: Double, val lonMax: Double, val kx: Double
    ) {
        fun toXY(lat: Double, lon: Double): Pair<Double, Double> =
            ((lon - lonMin) * kx) to (latMax - lat)
    }

    fun fit(pts: List<TrackPt>): Fit? {
        if (pts.size < 2) return null
        val latMin = pts.minOf { it.lat }; val latMax = pts.maxOf { it.lat }
        val lonMin = pts.minOf { it.lon }; val lonMax = pts.maxOf { it.lon }
        val midLat = Math.toRadians((latMin + latMax) / 2.0)
        return Fit(latMin, latMax, lonMin, lonMax, cos(midLat))
    }

    /**
     * 渲染预处理：返回保留的原始点下标（升序）。
     * ① 滑动窗口平均（窗口5）压 GPS 抖动；
     * ② 按距离等距重采样到 ≤maxPts（快路段不丢形）；
     * ③ Douglas-Peucker 简化（容差=轨迹尺寸 0.35%）去冗余。
     */
    fun prepareIndices(pts: List<TrackPt>, maxPts: Int = 320): List<Int> {
        if (pts.size < 3) return pts.indices.toList()
        val fit = fit(pts) ?: return pts.indices.toList()

        // ① 滑窗平滑（保持点数）
        val sm = ArrayList<TrackPt>(pts.size)
        for (i in pts.indices) {
            var la = 0.0; var lo = 0.0; var n = 0
            for (j in maxOf(0, i - 2)..minOf(pts.size - 1, i + 2)) {
                la += pts[j].lat; lo += pts[j].lon; n++
            }
            sm.add(TrackPt(la / n, lo / n))
        }

        // ② 距离等距重采样：目标步长 = 轨迹总长 / maxPts
        fun planar(i: Int, j: Int): Double {
            val a = sm[i]; val b = sm[j]
            val dx = (b.lon - a.lon) * fit.kx
            val dy = b.lat - a.lat
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val total = (1 until sm.size).sumOf { planar(it - 1, it) }
        val picked = ArrayList<Int>(maxPts)
        if (total <= 0.0) return listOf(0, pts.size - 1)
        val step = total / maxPts
        picked.add(0)
        var acc = 0.0
        var next = step
        for (i in 1 until sm.size) {
            acc += planar(i - 1, i)
            while (acc >= next && picked.size < maxPts) {
                picked.add(i)
                next += step
            }
        }
        if (picked.last() != pts.size - 1) picked.add(pts.size - 1)

        // ③ DP 简化（对已抽稀的子集，容差 = 轨迹尺寸 * 0.0035）
        val diag = kotlin.math.sqrt(
            Math.pow((fit.latMax - fit.latMin), 2.0) +
                    Math.pow((fit.lonMax - fit.lonMin) * fit.kx, 2.0)
        )
        val eps = (diag * 0.0035).coerceAtLeast(1e-7)
        fun perpDist(p: TrackPt, a: TrackPt, b: TrackPt): Double {
            val px = (p.lon - a.lon) * fit.kx; val py = p.lat - a.lat
            val vx = (b.lon - a.lon) * fit.kx; val vy = b.lat - a.lat
            val len2 = vx * vx + vy * vy
            if (len2 <= 0.0) return kotlin.math.sqrt(px * px + py * py)
            var t = (px * vx + py * vy) / len2
            t = t.coerceIn(0.0, 1.0)
            val dx = px - t * vx; val dy = py - t * vy
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        fun dp(sub: List<Int>): List<Int> {
            if (sub.size <= 2) return sub
            val a = sm[sub.first()]; val b = sm[sub.last()]
            var maxD = -1.0; var maxIdx = 0
            for (k in 1 until sub.size - 1) {
                val d = perpDist(sm[sub[k]], a, b)
                if (d > maxD) { maxD = d; maxIdx = k }
            }
            return if (maxD > eps) {
                dp(sub.subList(0, maxIdx + 1)).dropLast(1) + dp(sub.subList(maxIdx, sub.size))
            } else listOf(sub.first(), sub.last())
        }
        return dp(picked)
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRainbowTrack(
    pts: List<TrackPt>,
    speeds: List<Float>,
    maxSpeed: Double,
    fg: Color,
    pad: Float,
    trackW: Float,
    waypoints: List<WpPt> = emptyList(),
    dim: Color = Color.Gray,
    eles: List<Double>? = null
) {
    // 平滑管道：数千点 → ≤320 点平滑轨迹（全程保留，速度列表同步裁剪）
    val keep = TrackGeom.prepareIndices(pts)
    val sPts = keep.map { pts[it] }
    val sSpd = keep.map { speeds.getOrElse(it) { _ -> 0f } }
    // 海拔子序列（与抽稀点同步裁剪 + 滑窗平滑，压 GPS 高程抖动）
    val eleSm = eles?.takeIf { it.size == pts.size }?.let { e ->
        val raw = keep.map { e[it] }
        raw.indices.map { i ->
            var s = 0.0; var n = 0
            for (j in maxOf(0, i - 2)..minOf(raw.size - 1, i + 2)) { s += raw[j]; n++ }
            s / n
        }
    }
    val eleLo = eleSm?.minOrNull() ?: 0.0
    val eleHi = eleSm?.maxOrNull() ?: 0.0
    val fit = TrackGeom.fit(sPts) ?: run {
        drawLine(dim, Offset(size.width * 0.2f, size.height / 2f), Offset(size.width * 0.8f, size.height / 2f), trackW)
        return
    }
    val bw = ((fit.lonMax - fit.lonMin) * fit.kx).coerceAtLeast(1e-6)
    val bh = (fit.latMax - fit.latMin).coerceAtLeast(1e-6)
    val availW = size.width - pad * 2
    val availH0 = size.height - pad * 2
    // 抬升线比例尺：等比例×3（高差像素 = 高差m / mPerPx × 3），钳制 8%~38% 可用高。
    // 纯固定 34% 会"几米小坡画成大山"；等比例才能在大落差（爬 5000m 山）与小起伏间都保真。
    val lift = if (eleSm != null && eleHi - eleLo > 3.0) {
        val scale0 = minOf(availW / bw.toFloat(), availH0 / bh.toFloat())
        val mPerPx = 111320.0 / scale0   // 1 纬度归一单位 ≈ 111320 m
        ((eleHi - eleLo) / mPerPx * 3.0).toFloat().coerceIn(availH0 * 0.08f, availH0 * 0.38f)
    } else 0f
    val availH = availH0 - lift
    val scale = minOf(availW / bw.toFloat(), availH / bh.toFloat())
    val offX = pad + (availW - bw.toFloat() * scale) / 2f
    // 包络整体居中：抬升曲线占据轨迹上方的 lift 空间
    val offY = pad + lift + (availH - bh.toFloat() * scale) / 2f
    fun map(p: TrackPt): Offset {
        val (x, y) = fit.toXY(p.lat, p.lon)
        return Offset(offX + x.toFloat() * scale, offY + y.toFloat() * scale)
    }
    val p2 = sPts.map { map(it) }
    // ---- 爬升/下降抬升曲线：与轨迹同点序、同 x，逐点垂直抬升 ----
    if (eleSm != null && lift > 0f) {
        fun eleY(i: Int): Float {
            val t = ((eleSm[i] - eleLo) / (eleHi - eleLo)).toFloat()
            return p2[i].y - t.coerceIn(0f, 1f) * lift
        }
        // 轨迹 ↔ 曲线间淡填充（山体剪影，暗示两线同源同步）
        val ridge = Path().apply {
            moveTo(p2.first().x, p2.first().y)
            for (i in p2.indices) lineTo(p2[i].x, eleY(i))
            for (i in p2.indices.reversed()) lineTo(p2[i].x, p2[i].y)
            close()
        }
        drawPath(ridge, AvColors.green.copy(alpha = 0.10f))
        // 坡度分段色线：爬升橙红 / 下降电光青
        for (i in 0 until p2.size - 1) {
            val c = if (eleSm[i + 1] >= eleSm[i]) Color(0xFFFF8A3C) else Color(0xFF22D3EE)
            drawLine(
                c, Offset(p2[i].x, eleY(i)), Offset(p2[i + 1].x, eleY(i + 1)),
                strokeWidth = trackW * 0.85f, cap = StrokeCap.Round
            )
        }
        // 曲线两端小标记（最低/最高）
        val loIdx = eleSm.indices.minBy { eleSm[it] }
        val hiIdx = eleSm.indices.maxBy { eleSm[it] }
        drawCircle(Color(0xFF22D3EE), radius = trackW * 0.8f, center = Offset(p2[loIdx].x, eleY(loIdx)))
        drawCircle(Color(0xFFFF8A3C), radius = trackW * 0.8f, center = Offset(p2[hiIdx].x, eleY(hiIdx)))
    }
    // 逐段彩虹（速度分色，俯视轨迹主线）
    for (i in 0 until sPts.size - 1) {
        val c = AvColors.rainbow(sSpd[i].toDouble(), maxSpeed)
        drawLine(
            c, p2[i], p2[i + 1],
            strokeWidth = trackW, cap = StrokeCap.Round
        )
    }
    // 打卡点
    for (wp in waypoints) {
        val o = map(TrackPt(wp.lat, wp.lon))
        drawCircle(Color.White, radius = trackW * 1.6f, center = o)
        drawCircle(
            when (wp.kind) {
                "M" -> Color(0xFFFDE047)
                "K" -> Color(0xFF38BDF8)
                "C" -> Color(0xFFA3E635)
                else -> Color(0xFF9D7BFF)
            },
            radius = trackW * 1.2f, center = o
        )
    }
    // 起点/终点
    val s0 = map(sPts.first())
    val sN = map(sPts.last())
    drawCircle(Color(0xFFA3E635), radius = trackW * 1.8f, center = s0)
    drawCircle(fg, radius = trackW * 0.8f, center = s0)
    drawCircle(Color(0xFFFF6B81), radius = trackW * 1.4f, center = sN, style = Stroke(trackW * 0.7f))
}

/** 打卡照片解码（IO 线程降采样，防 OOM） */
@Composable
fun rememberPhotoBitmap(path: String?, targetPx: Int = 240): ImageBitmap? {
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path == null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / targetPx)
                android.graphics.BitmapFactory.decodeFile(
                    path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

/**
 * 爬升/下降曲线（详情页）：与轨迹同点序的海拔轮廓，爬升段橙红、下降段电光青，
 * 底部渐变填充山体感；与轨迹卡抬升线同一套颜色语义，点对点同步。
 */
@Composable
fun ElevationGainSpark(eles: List<Double>, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        if (eles.size < 2) {
            drawLine(
                dim.copy(alpha = 0.5f),
                Offset(size.width * 0.08f, size.height * 0.7f),
                Offset(size.width * 0.92f, size.height * 0.7f),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
            return@Canvas
        }
        // 3 点滑窗平滑压 GPS 高程抖动
        val sm = eles.indices.map { i ->
            var s = 0.0; var n = 0
            for (j in maxOf(0, i - 2)..minOf(eles.size - 1, i + 2)) { s += eles[j]; n++ }
            s / n
        }
        val lo = sm.min()
        val hi = sm.max()
        val range = (hi - lo).coerceAtLeast(1.0)
        val pad = 8.dp.toPx()
        val h2 = size.height - pad * 2
        val step = size.width / (sm.size - 1).coerceAtLeast(1)
        fun xy(i: Int): Offset {
            val t = ((sm[i] - lo) / range).toFloat().coerceIn(0f, 1f)
            return Offset(i * step, pad + (1f - t) * h2)
        }
        // 山体渐变填充（低=青雾，高=橙光）
        val fill = Path().apply {
            moveTo(0f, size.height)
            sm.indices.forEach { lineTo(xy(it).x, xy(it).y) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(
            fill,
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color(0xFFFF8A3C).copy(alpha = 0.26f), Color(0xFF22D3EE).copy(alpha = 0.08f))
            )
        )
        // 坡度分段色线
        for (i in 0 until sm.size - 1) {
            val c = if (sm[i + 1] >= sm[i]) Color(0xFFFF8A3C) else Color(0xFF22D3EE)
            drawLine(c, xy(i), xy(i + 1), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        // 最高点标记
        val hiIdx = sm.indices.maxBy { sm[it] }
        drawCircle(Color(0xFFFF8A3C), radius = 4.dp.toPx(), center = xy(hiIdx))
        drawCircle(Color.White, radius = 1.8.dp.toPx(), center = xy(hiIdx))
    }
}

/** 爬升/下降统计：累计爬升 ↑ 与累计下降 ↓（谷-峰水位跟踪口径） */
fun elevGainLoss(eles: List<Double>): Pair<Double, Double> {
    var lo = Double.MAX_VALUE
    var gain = 0.0
    var loss = 0.0
    for (e in eles) {
        if (e < lo) lo = e
        else if (e > lo + 3.0) { gain += e - lo; lo = e }
    }
    // 下降对称算一遍
    var hi = -Double.MAX_VALUE
    for (e in eles.asReversed()) {
        if (e > hi) hi = e
        else if (e < hi - 3.0) { loss += hi - e; hi = e }
    }
    return gain to loss
}

/**
 * 彩虹轨迹卡（详情页）：速度分色轨迹 + 爬升/下降抬升曲线叠加 + 打卡照片钉在轨迹对应位置（圆形缩略图，点击放大）。
 */
@Composable
fun TrackRainbowCard(
    pts: List<TrackPt>,
    speeds: List<Float>,
    maxSpeed: Double,
    waypoints: List<WpPt>,
    modifier: Modifier = Modifier,
    onPhotoTap: (String) -> Unit = {},
    eles: List<Double>? = null
) {
    val dark = isDark()
    val fg = if (dark) Color.White else Color.Black
    val dimC = MaterialTheme.colorScheme.onSurfaceVariant
    val padPx = with(LocalDensity.current) { 30.dp.toPx() }
    var canvas by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .glassCard(dark)
            .onGloballyPositioned { canvas = it.size }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRainbowTrack(
                pts, speeds, maxSpeed, fg,
                pad = padPx, trackW = 3.dp.toPx(), waypoints = waypoints, dim = dimC,
                eles = eles
            )
        }
        // 打卡照片钉在轨迹上
        val fit = TrackGeom.fit(pts)
        if (fit != null && canvas != IntSize.Zero) {
            val bw = ((fit.lonMax - fit.lonMin) * fit.kx).coerceAtLeast(1e-6)
            val bh = (fit.latMax - fit.latMin).coerceAtLeast(1e-6)
            // 与 drawRainbowTrack 相同的抬升偏移（保证照片钉点与轨迹精确对齐）：
            // 等比例×3 + 8%~38% 可用高钳制，公式必须与主绘制完全一致
            val eleRange = eles?.takeIf { it.size == pts.size }?.let { l ->
                if (l.isEmpty()) 0.0 else l.max() - l.min()
            } ?: 0.0
            val availW = canvas.width - padPx * 2
            val availH0 = canvas.height - padPx * 2
            val liftPx = if (eleRange > 3.0) {
                val scale0 = minOf(availW / bw.toFloat(), availH0 / bh.toFloat())
                val mPerPx = 111320.0 / scale0
                (eleRange / mPerPx * 3.0).toFloat().coerceIn(availH0 * 0.08f, availH0 * 0.38f)
            } else 0f
            val availH = availH0 - liftPx
            val scale = minOf(availW / bw.toFloat(), availH / bh.toFloat())
            val offX = padPx + (availW - bw.toFloat() * scale) / 2f
            val offY = padPx + liftPx + (availH - bh.toFloat() * scale) / 2f
            val r = with(LocalDensity.current) { 15.dp.toPx() }
            waypoints.filter { !it.photo.isNullOrEmpty() }.forEach { wp ->
                val (x, y) = fit.toXY(wp.lat, wp.lon)
                val cx = offX + x.toFloat() * scale
                val cy = offY + y.toFloat() * scale
                val bmp = rememberPhotoBitmap(wp.photo)
                val thumb = Modifier
                    .offset { IntOffset((cx - r).roundToInt(), (cy - r).roundToInt()) }
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(2.dp, fg, CircleShape)
                    .clickable { wp.photo?.let(onPhotoTap) }
                if (bmp != null) {
                    Image(bmp, null, thumb, contentScale = ContentScale.Crop)
                } else {
                    Box(
                        thumb.background(AvColors.cyan.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("照", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * 罗盘大盘（详情页，量子环版）：外圈水平仪弧 + 细密刻度环 + 主刻度环
 * + 直立方位字母（N 红）+ 红色 N 标随盘转 + 顶部固定索引线 + 中心大数字/方位名。
 * 外圈弧 = 水平仪语义：手机平放（<1°）不显示；倾斜时弧出现在低侧方向
 * （屏幕坐标系，不随罗盘转——平放后向东北/西北/东南/西南倾斜，弧出现在对应侧），
 * 跨度随倾角增大，颜色 cyan（<15°）→ orange（<30°）→ red（≥30°）渐进警示。
 */
@Composable
fun CompassBig(heading: Float, tilt: Float = 0f, tiltDir: Float = 0f, modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onBackground
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 8.dp.toPx()

            // 外圈水平仪弧：中心 = 低侧方向（屏幕角 0=上 → drawArc 0°=3 点钟，差 -90°）
            val ext = 4.dp.toPx()
            val span = tilt.coerceIn(0f, 45f) * 1.6f
            if (span >= 1.2f) {
                val tiltColor = when {
                    tilt < 15f -> AvColors.cyan
                    tilt < 30f -> AvColors.orange
                    else -> AvColors.red
                }
                drawArc(
                    tiltColor, tiltDir - 90f - span, span * 2f, false,
                    topLeft = Offset(c.x - r - ext, c.y - r - ext),
                    size = Size((r + ext) * 2f, (r + ext) * 2f),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            rotate(degrees = -heading, pivot = c) {
                // 细密刻度环（72）
                for (i in 0 until 72) {
                    val a = Math.toRadians((i * 5f).toDouble())
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    drawLine(
                        dim.copy(alpha = 0.85f), c + dir * r, c + dir * (r - r * 0.05f),
                        strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round
                    )
                }
                // 主刻度环（12）
                for (i in 0 until 12) {
                    val a = Math.toRadians((i * 30f).toDouble())
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    drawLine(
                        fg, c + dir * (r * 0.86f), c + dir * (r * 0.73f),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
                    )
                }
                // 红色 N 标（尖朝外的三角，随盘转）
                val tri = Path().apply {
                    moveTo(c.x, c.y - r * 0.95f)
                    lineTo(c.x - 4.5.dp.toPx(), c.y - r * 0.95f + 9.dp.toPx())
                    lineTo(c.x + 4.5.dp.toPx(), c.y - r * 0.95f + 9.dp.toPx())
                    close()
                }
                drawPath(tri, AvColors.red)
            }

            // 顶部固定索引线（当前朝向；v0.8.40 缩短避让 °标尺数字带）
            drawLine(
                AvColors.cyan, Offset(c.x, c.y - r), Offset(c.x, c.y - r * 0.94f),
                strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round
            )
            // 中心轴帽
            drawCircle(AvColors.cyan.copy(alpha = 0.35f), 5.dp.toPx(), c)
            drawCircle(AvColors.cyan, 2.dp.toPx(), c)
        }
        // 方位字母（N 红）+ 外圈 °数标尺：位置随盘转，字形直立
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val rr = (minOf(maxWidth, maxHeight) * 0.29f)
            listOf("N", "E", "S", "W").forEachIndexed { idx, n ->
                val a = Math.toRadians((idx * 90f - heading).toDouble())
                Text(
                    n,
                    Modifier
                        .align(Alignment.Center)
                        .offset {
                            IntOffset(
                                (sin(a) * rr.toPx()).toInt(),
                                (-cos(a) * rr.toPx()).toInt()
                            )
                        },
                    fontSize = if (idx == 0) 17.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (idx == 0) Color(0xFFFF5252) else fg.copy(alpha = 0.85f)
                )
            }
            // °数标尺（v0.8.40）：外圈每 30° 标注，主刻度环与细刻度环之间空带，随盘转、字形直立
            val rrDeg = (minOf(maxWidth, maxHeight) * 0.415f)
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30f - heading).toDouble())
                Text(
                    (i * 30).toString(),
                    Modifier
                        .align(Alignment.Center)
                        .offset {
                            IntOffset(
                                (sin(a) * rrDeg.toPx()).toInt(),
                                (-cos(a) * rrDeg.toPx()).toInt()
                            )
                        },
                    fontSize = 9.sp,
                    color = dim.copy(alpha = 0.9f)
                )
            }
        }
        // 中心读数：大数字（v0.8.40 删度数下方的汉字/字母方位，数字居中）
        Text(
            heading.toInt().toString(),
            fontSize = 46.sp, fontWeight = FontWeight.Black, color = fg
        )
    }
}

/** GPS 信号强度条（📶）：4 格竖条，按定位精度分级点亮；-1 = 无定位全灰 */
@Composable
fun GpsBars(accM: Float, modifier: Modifier = Modifier) {
    val level = when {
        accM < 0f -> 0          // 无定位
        accM <= 5f -> 4         // 极佳
        accM <= 10f -> 3        // 良好
        accM <= 20f -> 2        // 一般
        else -> 1               // 弱
    }
    val lit = when (level) {
        4 -> AvColors.green
        3 -> AvColors.cyan
        2 -> AvColors.yellow
        1 -> AvColors.orange
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.12f
        val bw = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * (0.34f + 0.22f * i)
            val x = i * (bw + gap)
            drawRoundRect(
                if (level >= i + 1) lit else lit.copy(alpha = 0.22f),
                topLeft = Offset(x, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f, bw / 2f)
            )
        }
    }
}
