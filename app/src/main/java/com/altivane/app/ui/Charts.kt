package com.altivane.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 卡片图表模型：每种卡片配专属图型（参考健康 App 视觉）。
 * HR_WAVE 心率波动线 / PACE_BARS 配速竖条（含踏频）/ AREA 平滑面积图 / ENERGY 强度分段条。
 */
enum class ChartKind { AREA, HR_WAVE, PACE_BARS, ENERGY, SPEED_BARS, ELEV, CUMUL_RAINBOW, STEP_WARM, HOURLY_BARS, MOVE_REST, LADDER, TRACK_SHAPE, TIME_BLOCKS }

data class CardChart(
    val kind: ChartKind,
    val series: List<Double> = emptyList(),
    /** ENERGY 专用：低/中/高强度 kcal 三段 */
    val split: List<Double> = emptyList(),
    /** 面积图右缘数字档位标尺 */
    val scale: Boolean = false,
    /** 竖条图绝对阈值（km/h）：≥gMax 紫、≥yMax 绿、否则黄；0 = 相对归一分档 */
    val yMax: Float = 0f,
    val gMax: Float = 0f,
    /** MOVE_REST / TIME_BLOCKS 专用：总时长 ms（停留标注换算分钟） */
    val durationMs: Long = 0L,
    /** MOVE_REST / TIME_BLOCKS 专用：与 series 对齐的时间戳（ms），提供时按时间轴分桶 */
    val timestamps: List<Long> = emptyList(),
    /** HOURLY_BARS 专用：当前小时（时间轴"现在"标签） */
    val nowHour: Int = -1,
    /** TRACK_SHAPE 专用：轨迹 [lat, lon] 对（series 复用为海拔序列） */
    val latLon: List<List<Double>> = emptyList()
)

/** 三色强度分档：黄（慢）→ 绿（快）→ 紫（极限），阈值 60% / 85% */
private fun triColor(t: Float): Color = when {
    t >= 0.85f -> AvColors.purple
    t >= 0.60f -> AvColors.green
    else -> AvColors.yellow
}

/** 序列降采样：均分桶取均值，压到 n 点以内 */
fun resampleSeries(data: List<Double>, n: Int): List<Double> {
    if (data.size <= n) return data
    val out = ArrayList<Double>(n)
    val step = data.size / n.toDouble()
    for (i in 0 until n) {
        val a = (i * step).toInt()
        val b = minOf(data.size, ((i + 1) * step).toInt().coerceAtLeast(a + 1))
        var s = 0.0
        var c = 0
        for (j in a until b) { s += data[j]; c++ }
        out.add(if (c > 0) s / c else data[a])
    }
    return out
}

/** 确定性伪随机（0..1）：同输入同输出，避免重组闪动 */
private fun hash01(i: Int): Float {
    val x = kotlin.math.sin(i * 12.9898f) * 43758.5453f
    return x - kotlin.math.floor(x)
}

/**
 * 心率波动线（参考健康 App 心脏健康卡）：
 * 真实样本为锚点，样本间用双频正弦 + 确定性噪声插值出致密波形，
 * 线下渐变填充淡出。样本稀疏（BLE 1s 一个）也能呈现连续波动。
 *
 * mini=true（主页小卡）：右缘带刻度尺（值域取整到 10）+ 当前心率位置点。
 */
@Composable
fun HeartRateWave(
    data: List<Double>,
    modifier: Modifier = Modifier,
    accent: Color = AvColors.pink,
    mini: Boolean = false
) {
    val fg = MaterialTheme.colorScheme.onBackground
    Canvas(modifier) {
        drawHrWave(data, accent, fg, mini)
    }
}

private fun DrawScope.drawHrWave(data: List<Double>, accent: Color, fallback: Color, mini: Boolean) {
    if (data.isEmpty()) return
    val lineC = accent
    // 锚点：真实样本
    val anchors = data.map { it.coerceAtLeast(0.0) }
    val aMin = anchors.min()
    val aMax = anchors.max()
    // mini 模式：值域取整到 10，画右缘数字档位标尺
    var waveW = size.width
    var lo = aMin
    var hi = aMax
    if (mini) {
        lo = kotlin.math.floor(aMin / 10.0) * 10.0
        hi = kotlin.math.ceil(aMax / 10.0).coerceAtLeast(lo / 10.0 + 1) * 10.0
        waveW = size.width - 34.dp.toPx()
    }
    val span = (hi - lo).coerceAtLeast(6.0)
    // 合成致密波形：72 点，锚点间线性插值 + 自然波动 + 周期性 QRS 心跳尖峰
    val n = 72
    val pts = ArrayList<Offset>(n)
    val period = 13.0
    for (i in 0 until n) {
        val pos = i / (n - 1.0) * (anchors.size - 1)
        val i0 = pos.toInt().coerceAtMost(anchors.size - 1)
        val i1 = (i0 + 1).coerceAtMost(anchors.size - 1)
        val f = pos - i0
        val base = anchors[i0] + (anchors[i1] - anchors[i0]) * f
        val wave = span * 0.055 * kotlin.math.sin(i * 0.92) +
                span * 0.038 * kotlin.math.sin(i * 0.41 + 1.7) +
                span * 0.045 * (hash01(i * 7 + 3) - 0.5)
        // 心电图 QRS：Q 小下探 → R 高尖峰 → S 快速回落 → 恢复
        val phase = (i % period) / period
        val spike = when {
            phase < 0.06 -> -span * 0.10 * (phase / 0.06)
            phase < 0.10 -> span * 0.30 * ((phase - 0.06) / 0.04)
            phase < 0.15 -> span * 0.30 * (1.0 - (phase - 0.10) / 0.05) - span * 0.16 * ((phase - 0.10) / 0.05)
            phase < 0.26 -> -span * 0.16 * (1.0 - (phase - 0.15) / 0.11)
            else -> 0.0
        }
        // 端点少加波动，贴合真实首尾值
        val edge = minOf(i, n - 1 - i).coerceAtMost(6) / 6.0
        val v = base + wave * edge + spike * edge
        val x = waveW * i / (n - 1f)
        val y = (size.height * 0.86f * (1.0 - (v - lo) / span) + size.height * 0.07f).toFloat()
        pts.add(Offset(x, y))
    }
    val path = Path()
    pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    val fill = Path().apply {
        addPath(path)
        lineTo(waveW, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(
        fill,
        Brush.verticalGradient(
            listOf(lineC.copy(alpha = 0.30f), lineC.copy(alpha = 0.02f)),
            startY = 0f, endY = size.height
        )
    )
    drawPath(
        path, lineC,
        style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    if (mini) {
        // 右缘数字档位标尺：3 档数字（顶/中/底，BPM）+ 刻度线 + 当前心率位置点
        val dim = fallback.copy(alpha = 0.45f)
        val rx = waveW + 1.dp.toPx()
        val ticks = 4
        for (k in 0 until ticks) {
            val frac = k / (ticks - 1.0)
            val y = (size.height * 0.86f * (1.0 - frac) + size.height * 0.07f).toFloat()
            drawLine(dim, Offset(rx, y), Offset(rx + 4.dp.toPx(), y), 1.dp.toPx())
        }
        val np = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                (fallback.alpha * 255).toInt(),
                (fallback.red * 255).toInt(), (fallback.green * 255).toInt(), (fallback.blue * 255).toInt()
            )
            textSize = 9.sp.toPx()
            textAlign = android.graphics.Paint.Align.LEFT
        }
        fun label(frac: Double): String =
            ((kotlin.math.round((lo + span * frac) / 10.0) * 10.0)).toInt().toString()
        val rows = listOf(0.0 to 1.0, 0.5 to 0.5, 1.0 to 0.0)
        rows.forEach { (frac, _) ->
            val y = (size.height * 0.86f * (1.0 - frac) + size.height * 0.07f).toFloat()
            drawContext.canvas.nativeCanvas.drawText(
                label(frac), rx + 6.dp.toPx(), y + 3.dp.toPx(), np
            )
        }
        // 当前心率（最后样本）位置点
        val cur = anchors.last()
        val cy = (size.height * 0.86f * (1.0 - ((cur - lo) / span)) + size.height * 0.07f).toFloat()
        drawCircle(lineC, radius = 2.4.dp.toPx(), center = Offset(rx + 3.dp.toPx(), cy))
    }
}

/**
 * 配速/踏频竖条图（参考健康 App 血氧饱和度卡）：
 * 灰色细条栅格打底，数据条按时间桶叠加；速度越快条越高，
 * 达到峰值 75% 以上转为高强度色（橙）。
 */
@Composable
fun PaceBars(
    data: List<Double>,
    modifier: Modifier = Modifier,
    accent: Color = AvColors.green,
    high: Color = AvColors.orange,
    yMax: Float = 0f,
    gMax: Float = 0f
) {
    val fg = MaterialTheme.colorScheme.onBackground
    Canvas(modifier) {
        val slots = 26
        val buckets = resampleSeries(data, slots)
        val vRef = (buckets.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val slotW = size.width / slots
        val barW = (slotW * 0.42f).coerceAtLeast(2.dp.toPx())
        // 灰条栅格（全高）
        val gridC = fg.copy(alpha = 0.10f)
        for (i in 0 until slots) {
            val cx = slotW * i + slotW / 2f
            drawRoundRect(
                gridC,
                topLeft = Offset(cx - barW / 2f, 0f),
                size = Size(barW, size.height),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
        // 数据条：绝对阈值（km/h）或相对归一三色分档（黄慢/绿快/紫极限）
        buckets.forEachIndexed { i, v ->
            val t = (v / vRef).toFloat().coerceIn(0f, 1f)
            if (t < 0.04f) return@forEachIndexed
            val bh = (size.height * t).coerceAtLeast(barW)
            val cx = slotW * i + slotW / 2f
            val c = if (yMax > 0f) {
                if (v >= gMax) AvColors.purple
                else if (v >= yMax) AvColors.green
                else AvColors.yellow
            } else triColor(t)
            drawRoundRect(
                c,
                topLeft = Offset(cx - barW / 2f, size.height - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

/**
 * 能量三竖柱：低/中/高强度各一根圆角柱（高度按 kcal 占 max 归一），
 * 黄/绿/橙三色，柱顶数值，下方图例。替代过细的分段横条。
 */
@Composable
fun EnergySplitBar(
    split: List<Double>,
    modifier: Modifier = Modifier,
    low: Color = AvColors.yellow,
    mid: Color = AvColors.green,
    high: Color = AvColors.orange,
    barDp: Int = 7,
    showLegend: Boolean = true
) {
    val colors = listOf(low, mid, high)
    val names = listOf(stringResource(R.string.int_low), stringResource(R.string.int_mid), stringResource(R.string.int_high))
    val total = split.sum()
    val vMax = (split.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            split.forEachIndexed { i, v ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (v > 0.0 && showLegend) {
                        Text(
                            "%.0f".format(v),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors[i]
                        )
                    }
                    Box(
                        Modifier
                            .padding(horizontal = 10.dp)
                            .fillMaxWidth()
                            .height((18f + 44f * (v / vMax).toFloat()).dp)
                            .background(colors[i], RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    )
                    if (showLegend) {
                        Text(names[i], style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 平滑面积图：二次贝塞尔中点法平滑曲线 + 垂直渐变填充。
 * 用于 心率 / 里程累计 / 时长 / 速度 / 气压 等连续量。
 * scale=true：右缘数字档位标尺（顶/中/底三档，心率卡用）。
 */
@Composable
fun AreaSpark(
    data: List<Double>,
    modifier: Modifier = Modifier,
    accent: Color = AvColors.cyan,
    scale: Boolean = false,
    unit: String = ""
) {
    Row(modifier) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val fg = MaterialTheme.colorScheme.onBackground
            Canvas(Modifier.fillMaxSize()) {
                if (data.size < 2) return@Canvas
                val d = resampleSeries(data, 80)
                val min = d.min()
                val max = d.max()
                val span = (max - min).takeIf { it > 1e-6 } ?: 1.0
                val pts = d.mapIndexed { i, v ->
                    Offset(
                        size.width * i / (d.size - 1f),
                        (size.height * 0.92f * (1.0 - (v - min) / span)).toFloat()
                    )
                }
                // 中点平滑
                val line = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size - 1) {
                        val mid = Offset((pts[i].x + pts[i + 1].x) / 2f, (pts[i].y + pts[i + 1].y) / 2f)
                        quadraticBezierTo(pts[i].x, pts[i].y, mid.x, mid.y)
                    }
                    lineTo(pts.last().x, pts.last().y)
                }
                val fill = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    fill,
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.02f)),
                        startY = 0f, endY = size.height
                    )
                )
                drawPath(
                    line, accent,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        if (scale && data.isNotEmpty()) {
            // 数字档位标尺：顶/中/底三档（心率：BPM 取整到 10）
            val step = 10.0
            fun fmt(v: Double) = (kotlin.math.round(v / step) * step).toInt().toString()
            val lo = data.min(); val hi = data.max()
            Column(
                Modifier.fillMaxHeight().padding(start = 3.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                ScaleTick(fmt(hi), accent, unit)
                ScaleTick(fmt((lo + hi) / 2), accent.copy(alpha = 0.6f), unit)
                ScaleTick(fmt(lo), MaterialTheme.colorScheme.onSurfaceVariant, unit)
            }
        }
    }
}

/** 标尺档位：数字 + 竖线 */
@Composable
private fun ScaleTick(v: String, c: Color, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (unit.isEmpty()) v else "$v$unit",
            style = MaterialTheme.typography.labelSmall, color = c
        )
        Box(
            Modifier.padding(start = 2.dp).width(4.dp).height(1.dp).background(c)
        )
    }
}

/** 气压/海拔：圆角粗柱阵列 + 柱顶横杠（卡片天空蓝 violet），最高/最低自动标注 */
@Composable
fun ElevArea(
    data: List<Double>,
    modifier: Modifier = Modifier,
    accent: Color = AvColors.violet,
    mini: Boolean = false
) {
    val fg = MaterialTheme.colorScheme.onBackground
    Canvas(modifier.fillMaxSize()) {
        if (data.size < 2) return@Canvas
        // 桶数不得超过数据量：resampleSeries 在数据不足时原样返回，
        // 若循环仍按 14 跑会越界闪退（crash.txt 10-15：Index 2 out of bounds for length 2）
        val n = minOf(14, data.size)
        val d = resampleSeries(data, n)
        val min = d.min(); val max = d.max()
        val span = (max - min).takeIf { it > 1e-6 } ?: 1.0
        val slotW = size.width / n
        val barW = slotW * 0.58f
        val gap = (slotW - barW) / 2f
        val barH = size.height * 0.70f
        val baseY = size.height * 0.90f
        val capH = 3.dp.toPx()
        val cr = CornerRadius(barW / 3f, barW / 3f)
        for (i in 0 until n) {
            val frac = (((d[i] - min) / span).toFloat()).coerceIn(0.05f, 1f)
            val h = barH * frac
            val x = slotW * i + gap
            // 柱体（半透）
            drawRoundRect(
                accent.copy(alpha = 0.28f),
                topLeft = Offset(x, baseY - h), size = Size(barW, h),
                cornerRadius = cr
            )
            // 柱顶横杠（实色）
            drawRoundRect(
                accent,
                topLeft = Offset(x, baseY - h - capH / 2f), size = Size(barW, capH),
                cornerRadius = CornerRadius(capH / 2f, capH / 2f)
            )
        }
        // 最高/最低数值标注
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                (fg.alpha * 255).toInt(),
                (fg.red * 255).toInt(), (fg.green * 255).toInt(), (fg.blue * 255).toInt()
            )
            textSize = (if (mini) 8.sp else 10.sp).toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        fun mark(i: Int, v: Double) {
            if (i !in 0 until n) return
            val frac = (((d[i] - min) / span).toFloat()).coerceIn(0.05f, 1f)
            val cx = slotW * i + slotW / 2f
            val topY = baseY - barH * frac - capH
            drawContext.canvas.nativeCanvas.drawText("%.0f".format(v), cx, topY - 2.dp.toPx(), p)
        }
        mark(d.indices.maxBy { d[it] }, max)
        mark(d.indices.minBy { d[it] }, min)
    }
}

/** 里程累计曲线（A 方案）：进度彩虹描边（同轨迹色相语义 紫→红）+ 里程碑点，详情页带数字 */
@Composable
fun CumulRainbow(
    data: List<Double>,
    modifier: Modifier = Modifier,
    accent: Color = AvColors.blue,
    mini: Boolean = false
) {
    val fg = MaterialTheme.colorScheme.onBackground
    Canvas(modifier.fillMaxSize()) {
        if (data.size < 2) return@Canvas
        val d = resampleSeries(data, 80)
        val max = d.max().coerceAtLeast(1e-6)
        val pts = d.mapIndexed { i, v ->
            Offset(
                size.width * i / (d.size - 1f),
                (size.height * 0.90f * (1.0 - v / max)).toFloat()
            )
        }
        fun pr(t: Float): Color = Color.hsv(270f - 270f * t.coerceIn(0f, 1f), 0.80f, 0.98f)
        val fill = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(fill, Brush.horizontalGradient((0..6).map { pr(it / 6f).copy(alpha = 0.20f) }))
        for (i in 0 until pts.size - 1) {
            drawLine(
                pr(i / (pts.size - 1f)), pts[i], pts[i + 1],
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
        }
        // 里程碑：整点 km 自适应步长，点色随进度彩虹
        val nice = when {
            max < 1.5 -> 0.25; max < 3.0 -> 0.5; max < 8.0 -> 1.0
            max < 20.0 -> 2.0; max < 50.0 -> 5.0; else -> 10.0
        }
        var m = nice
        while (m < max - 1e-9) {
            val t = (m / max).toFloat()
            val idxF = t * (d.size - 1)
            val i0 = idxF.toInt().coerceIn(0, d.size - 2)
            val f = (idxF - i0).toFloat()
            val y = pts[i0].y + (pts[i0 + 1].y - pts[i0].y) * f
            val x = size.width * t
            drawCircle(pr(t), 3.2.dp.toPx(), Offset(x, y))
            drawCircle(fg.copy(alpha = 0.9f), 1.2.dp.toPx(), Offset(x, y))
            if (!mini) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        (fg.alpha * 255).toInt(),
                        (fg.red * 255).toInt(), (fg.green * 255).toInt(), (fg.blue * 255).toInt()
                    )
                    textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                }
                val label = if (nice < 1.0) "%.1f".format(m) else "${m.toInt()}"
                drawContext.canvas.nativeCanvas.drawText(label, x, y - 7.dp.toPx(), p)
            }
            m += nice
        }
    }
}

/** 时长台阶面积（B 方案，每分钟一格量化 ≤30 格）：黄→橙→红 渐变填充与顶线 */
@Composable
fun StepWarm(
    data: List<Double>,
    modifier: Modifier = Modifier,
    accent: Color = AvColors.yellow
) {
    Canvas(modifier.fillMaxSize()) {
        if (data.size < 2) return@Canvas
        val n = data.size
        val total = data.last().coerceAtLeast(1e-6)
        val stepW = size.width / n
        val top = 0.90f
        fun yAt(i: Int) = (size.height * top * (1f - (data[i] / total).toFloat())).toFloat()
        val path = Path().apply {
            moveTo(0f, size.height)
            for (i in 0 until n) {
                lineTo(stepW * i, yAt(i))
                lineTo(stepW * (i + 1), yAt(i))
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                listOf(
                    AvColors.yellow.copy(alpha = 0.30f),
                    AvColors.orange.copy(alpha = 0.30f),
                    AvColors.red.copy(alpha = 0.30f)
                )
            )
        )
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val c = if (t < 0.5f) androidx.compose.ui.graphics.lerp(AvColors.yellow, AvColors.orange, t * 2f)
            else androidx.compose.ui.graphics.lerp(AvColors.orange, AvColors.red, (t - 0.5f) * 2f)
            drawLine(c, Offset(stepW * i, yAt(i)), Offset(stepW * (i + 1), yAt(i)), 2.dp.toPx(), cap = StrokeCap.Round)
            if (i < n - 1) drawLine(c, Offset(stepW * (i + 1), yAt(i)), Offset(stepW * (i + 1), yAt(i + 1)), 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * 里程小时柱（Apple 活动风格）：24 桶（0-23 时）每小时 km。
 * 灰 stub 栅格打底 + 蓝数据柱 + 峰值小时青色高亮并标注 km 数；
 * 下缘时间轴 0时/6时/12时/现在（nowHour<0 时标 18时）。
 */
@Composable
fun HourBars(
    series: List<Double>,
    modifier: Modifier = Modifier,
    accent: Color = AvColors.blue,
    peak: Color = AvColors.cyan,
    nowHour: Int = -1
) {
    val fg = MaterialTheme.colorScheme.onBackground
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = 24
            val slotW = size.width / n
            val barW = (slotW * 0.46f).coerceAtLeast(1.6.dp.toPx())
            val maxV = (series.maxOrNull() ?: 0.0).coerceAtLeast(0.1)
            val gridC = fg.copy(alpha = 0.08f)
            val cr = CornerRadius(barW / 2f, barW / 2f)
            // 灰 stub 全高栅格
            for (i in 0 until n) {
                val cx = slotW * i + slotW / 2f
                drawRoundRect(gridC, topLeft = Offset(cx - barW / 2f, 0f),
                    size = Size(barW, size.height), cornerRadius = cr)
            }
            // 数据柱
            val peakIdx = series.indices.maxByOrNull { series[it] } ?: -1
            series.forEachIndexed { i, v ->
                if (v <= 0.0) return@forEachIndexed
                val bh = (size.height * (v / maxV).toFloat()).coerceAtLeast(barW)
                val cx = slotW * i + slotW / 2f
                drawRoundRect(
                    if (i == peakIdx) peak else accent,
                    topLeft = Offset(cx - barW / 2f, size.height - bh),
                    size = Size(barW, bh), cornerRadius = cr
                )
            }
            // 峰值标注：柱顶上方 "x.x km"
            if (peakIdx >= 0 && series[peakIdx] > 0.0) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        (peak.alpha * 255).toInt(),
                        (peak.red * 255).toInt(), (peak.green * 255).toInt(), (peak.blue * 255).toInt()
                    )
                    textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                }
                val bh = (size.height * (series[peakIdx] / maxV).toFloat()).coerceAtLeast(barW)
                val cx = (slotW * peakIdx + slotW / 2f)
                    .coerceIn(14.dp.toPx(), size.width - 14.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(
                    "%.1f km".format(series[peakIdx]), cx, size.height - bh - 3.dp.toPx(), p
                )
            }
        }
        // 时间轴：0时 / 6时 / 12时 / 现在(或18时)
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            val dim = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(stringResource(R.string.hour_0), style = dim)
            Box(Modifier.weight(1f)) { Text(stringResource(R.string.hour_6), style = dim, modifier = Modifier.align(Alignment.CenterStart)) }
            Box(Modifier.weight(1f)) { Text(stringResource(R.string.hour_12), style = dim, modifier = Modifier.align(Alignment.CenterStart)) }
            Box(Modifier.weight(1f)) {
                Text(if (nowHour in 13..23) stringResource(R.string.now_label) else stringResource(R.string.hour_18), style = dim, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/**
 * 时长移动/停留时间带：横向分段带。
 * 提供时间戳时按【时间轴】分桶——停留期静止不采点/慢速桶都是停留，画成【空白缺口】
 * 切断蓝条（露卡底 + 底缘细基线），<20s 的短停等判为抖动不断开；
 * 无时间戳回退按样本序号均分（旧逻辑）。最长停留段上方标注"停留 X分"。
 */
@Composable
fun MoveRestBand(
    series: List<Double>,
    modifier: Modifier = Modifier,
    durationMs: Long = 0L,
    timestamps: List<Long> = emptyList(),
    moving: Color = AvColors.cyan,
    threshold: Double = 1.0,
    mini: Boolean = false
) {
    val fg = MaterialTheme.colorScheme.onBackground
    val stayWord = stringResource(R.string.lbl_stay)
    val minWord = stringResource(R.string.unit_min_short)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (series.isEmpty()) return@Canvas
            val segs = 120
            val bandTop = size.height * 0.40f
            val bandH = size.height * 0.30f
            // 分桶：时间轴模式（停留=空白缺口）或按样本均分兜底
            var spanMs = durationMs
            val flags: List<Boolean> = if (
                timestamps.size == series.size && timestamps.size >= 2 &&
                timestamps.last() > timestamps.first()
            ) {
                val t0 = timestamps.first()
                spanMs = timestamps.last() - t0
                val sums = DoubleArray(segs)
                val counts = IntArray(segs)
                for (i in series.indices) {
                    val b = (((timestamps[i] - t0) * segs / spanMs).toInt()).coerceIn(0, segs - 1)
                    sums[b] += series[i]; counts[b]++
                }
                val raw = (0 until segs).map { b -> counts[b] == 0 || sums[b] / counts[b] < threshold }
                // <20s 的连续停留判为抖动（等红灯/GPS 漂移），不断开
                val bucketMs = spanMs / segs.toDouble()
                val minRun = (20_000.0 / bucketMs).coerceAtLeast(1.0)
                val out = raw.toBooleanArray()
                var i = 0
                while (i < segs) {
                    if (out[i]) {
                        var j = i
                        while (j < segs && out[j]) j++
                        if (j - i < minRun) for (k in i until j) out[k] = false
                        i = j
                    } else i++
                }
                out.toList()
            } else {
                resampleSeries(series, segs).map { it < threshold }
            }
            val segW = size.width / segs
            // 缺口基线：带底缘细线保持时间轴感
            drawLine(
                fg.copy(alpha = 0.10f),
                Offset(0f, bandTop + bandH), Offset(size.width, bandTop + bandH),
                1.dp.toPx()
            )
            // 移动桶 = 青块；停留桶 = 不画（空白缺口）
            flags.forEachIndexed { i, rest ->
                if (!rest) drawRect(
                    moving,
                    topLeft = Offset(segW * i, bandTop),
                    size = Size(segW + 0.5f, bandH)
                )
            }
            // 最长停留段：上方标注（按时间跨度换算分钟）
            var bestLen = 0; var bestStart = -1
            var curLen = 0; var curStart = 0
            flags.forEachIndexed { i, rest ->
                if (rest) {
                    if (curLen == 0) curStart = i
                    curLen++
                    if (curLen > bestLen) { bestLen = curLen; bestStart = curStart }
                } else curLen = 0
            }
            if (bestStart >= 0 && spanMs > 0) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        (fg.copy(alpha = 0.75f).alpha * 255).toInt(),
                        (fg.red * 255).toInt(), (fg.green * 255).toInt(), (fg.blue * 255).toInt()
                    )
                    textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                }
                val cx = (segW * bestStart + segW * bestLen / 2f)
                    .coerceIn(18.dp.toPx(), size.width - 18.dp.toPx())
                val mins = (spanMs * bestLen.toDouble() / segs / 60000.0).let {
                    if (it >= 1.0) "${it.toInt()}$minWord" else "<1$minWord"
                }
                drawContext.canvas.nativeCanvas.drawText("$stayWord$mins", cx, bandTop - 3.dp.toPx(), p)
            }
        }
        if (!mini) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(moving, CircleShape))
                Text(
                    stringResource(R.string.lbl_moving), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Box(
                    Modifier.padding(start = 12.dp).size(7.dp)
                        .border(0.75.dp, fg.copy(alpha = 0.35f), CircleShape)
                )
                Text(
                    stringResource(R.string.lbl_stay), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/** 详情页图型统一入口：kind → 对应组件（mini=true 时心率波动线带右缘刻度尺） */
@Composable
fun CardChartView(
    chart: CardChart,
    modifier: Modifier = Modifier,
    accent: Color = AvColors.cyan,
    mini: Boolean = false
) {
    when (chart.kind) {
        ChartKind.HR_WAVE -> HeartRateWave(chart.series, modifier, accent, mini)
        ChartKind.PACE_BARS -> PaceBars(chart.series, modifier, accent, yMax = chart.yMax, gMax = chart.gMax)
        ChartKind.SPEED_BARS -> PaceBars(chart.series, modifier, accent)
        ChartKind.ENERGY -> EnergySplitBar(chart.split, modifier, showLegend = !mini)
        ChartKind.AREA -> AreaSpark(chart.series, modifier, accent, scale = chart.scale)
        ChartKind.CUMUL_RAINBOW -> CumulRainbow(chart.series, modifier, accent, mini)
        ChartKind.STEP_WARM -> StepWarm(chart.series, modifier, accent)
        ChartKind.ELEV -> ElevArea(chart.series, modifier, accent, mini)
        ChartKind.HOURLY_BARS -> HourBars(chart.series, modifier, accent = AvColors.blue, nowHour = chart.nowHour)
        ChartKind.MOVE_REST -> MoveRestBand(
            chart.series, modifier, durationMs = chart.durationMs,
            timestamps = chart.timestamps, mini = mini
        )
        ChartKind.LADDER -> LadderBars(chart.series, modifier, accent = AvColors.blue)
        ChartKind.TRACK_SHAPE -> TrackShape(chart.latLon, chart.series, modifier)
        ChartKind.TIME_BLOCKS -> TimeBlocks(
            chart.series, modifier, timestamps = chart.timestamps, durationMs = chart.durationMs
        )
    }
}

/** 里程百米云梯（v0.8.43）：横杠自下而上为里程刻度，灰杠打底、蓝色完成部分，
 *  级距自适应（0.05/0.1/0.2/0.5/1/2/5/10 km 保证 2~6 级），当前位置呼吸点 + 右缘小刻度。满高。 */
@Composable
fun LadderBars(series: List<Double>, modifier: Modifier = Modifier, accent: Color = AvColors.blue) {
    val fg = MaterialTheme.colorScheme.onBackground
    val dimC = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val total = series.lastOrNull() ?: return@Canvas
        if (total <= 0.0) return@Canvas
        val steps = listOf(0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0)
        val step = steps.firstOrNull { total / it <= 5.5 } ?: 10.0
        val levels = Math.ceil(total / step).toInt().coerceIn(1, 6)
        val padR = 26.dp.toPx()
        val rungW = size.width - padR
        val gap = size.height / levels
        val barH = (gap * 0.40f).coerceAtMost(9.dp.toPx())
        val cr = CornerRadius(barH / 2f, barH / 2f)
        val done = total / step
        for (i in 0 until levels) {
            val y = size.height - gap * (i + 0.5f)
            drawRoundRect(fg.copy(alpha = 0.10f), Offset(0f, y - barH / 2f), Size(rungW, barH), cr)
            val f = (done - i).coerceIn(0.0, 1.0)
            if (f > 0f) {
                val w = (rungW * f).toFloat().coerceAtLeast(barH)
                drawRoundRect(
                    if (f >= 1.0) accent else accent.copy(alpha = 0.72f),
                    Offset(0f, y - barH / 2f), Size(w, barH), cr
                )
            }
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(
                    (dimC.copy(alpha = 0.95f).alpha * 255).toInt(),
                    (dimC.red * 255).toInt(), (dimC.green * 255).toInt(), (dimC.blue * 255).toInt()
                )
                textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.RIGHT
            }
            drawContext.canvas.nativeCanvas.drawText(
                if (step >= 1.0) "%.0f".format(step * (i + 1)) else "%.1f".format(step * (i + 1)),
                size.width, y + 3.5.dp.toPx(), p
            )
        }
        val li = (done - 1).toInt().coerceIn(0, levels - 1)
        val lf = (done - li).coerceIn(0.0, 1.0)
        val yCur = size.height - gap * (li + 0.5f)
        val xCur = (rungW * lf).toFloat()
        drawCircle(accent.copy(alpha = 0.16f), barH * 1.2f, Offset(xCur, yCur))
        drawCircle(Color.White, barH * 0.52f, Offset(xCur, yCur))
        drawCircle(accent, barH * 0.38f, Offset(xCur, yCur))
    }
}

/** 轨迹足迹卡（v0.8.43）：经纬度形状线（彩虹水平渐变、等比缩放居中）+ 起蓝终红点
 *  + 下缘海拔面积剪影（浅绿）。满高。 */
@Composable
fun TrackShape(latLon: List<List<Double>>, eles: List<Double>, modifier: Modifier = Modifier) {
    val startC = Color(0xFF378ADD); val endC = Color(0xFFE24B4A)
    val fillC = AvColors.green.copy(alpha = 0.28f)
    Canvas(modifier) {
        if (latLon.size < 2) return@Canvas
        val n = latLon.size
        val stepI = if (n > 300) n / 300 + 1 else 1
        val idx = (0 until n step stepI).toList() + listOf(n - 1)
        val lats = latLon.map { it[0] }; val lons = latLon.map { it[1] }
        val latMin = lats.min(); val latMax = lats.max()
        val lonMin = lons.min(); val lonMax = lons.max()
        val mLat = kotlin.math.cos(Math.toRadians((latMin + latMax) / 2)).coerceIn(0.3, 1.0)
        val w1 = (((lonMax - lonMin) * mLat).takeIf { it > 1e-9 } ?: 1e-9).toFloat()
        val h1 = ((latMax - latMin).takeIf { it > 1e-9 } ?: 1e-9).toFloat()
        val shapeTop = 5.dp.toPx(); val shapeH = size.height * 0.60f
        val scale = minOf((size.width * 0.86f) / w1, shapeH / h1)
        val offX = (size.width - w1 * scale) / 2f
        fun px(i: Int): Float = offX + ((lons[i] - lonMin) * mLat * scale).toFloat()
        fun py(i: Int): Float = shapeTop + shapeH - ((lats[i] - latMin) * scale).toFloat()
        val path = Path().apply {
            moveTo(px(0), py(0))
            idx.drop(1).forEach { lineTo(px(it), py(it)) }
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                listOf(Color(0xFF534AB7), Color(0xFF378ADD), Color(0xFF0F6E56), Color(0xFFBA7517))
            ),
            style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(startC, 4.dp.toPx(), Offset(px(0), py(0)))
        drawCircle(endC, 4.dp.toPx(), Offset(px(n - 1), py(n - 1)))
        val hasEle = eles.size >= 2 && eles.any { it > 0.0 }
        if (hasEle) {
            val e = eles.filter { it > 0.0 }
            val eMin = e.min(); val eMax = e.max()
            if (eMax - eMin > 3.0) {
                val base = size.height - 2.dp.toPx()
                val area = size.height * 0.24f
                val ep = Path().apply {
                    moveTo(0f, base)
                    idx.forEach { i ->
                        if (eles[i] > 0.0) {
                            val v = ((eles[i] - eMin) / (eMax - eMin)).coerceIn(0.0, 1.0)
                            lineTo(size.width * i / (n - 1f), base - (v * area).toFloat())
                        }
                    }
                    lineTo(size.width, base); close()
                }
                drawPath(ep, fillC)
            }
        }
    }
}

/** 时长时间方块（v0.8.43）：格长自适应（5/10/15/20/30/60 分保证 ≤10 格），
 *  实心=移动、空心=停留、最后格半透明=进行中，方格按宽高比排布。满高。 */
@Composable
fun TimeBlocks(
    series: List<Double>,
    modifier: Modifier = Modifier,
    timestamps: List<Long> = emptyList(),
    durationMs: Long = 0L,
    moving: Color = AvColors.cyan
) {
    val fg = MaterialTheme.colorScheme.onBackground
    Canvas(modifier) {
        if (series.isEmpty()) return@Canvas
        val hasT = timestamps.size == series.size && timestamps.size >= 2 && timestamps.last() > timestamps.first()
        val spanMs = if (hasT) timestamps.last() - timestamps.first() else durationMs
        if (spanMs <= 0) return@Canvas
        val totalMin = spanMs / 60000.0
        val cellOpts = listOf(5.0, 10.0, 15.0, 20.0, 30.0, 60.0)
        val cellMin = cellOpts.firstOrNull { totalMin / it <= 10.0 } ?: 120.0
        val cells = Math.ceil(totalMin / cellMin).toInt().coerceIn(1, 40)
        val cols = Math.ceil(Math.sqrt(cells * size.width / size.height.coerceAtLeast(1f).toDouble()))
            .toInt().coerceIn(1, cells)
        val rows = Math.ceil(cells / cols.toDouble()).toInt()
        val gap = 4.dp.toPx()
        val cw = (size.width - gap * (cols - 1)) / cols
        val chh = (size.height - gap * (rows - 1)) / rows
        val cellMs = spanMs / cells.toDouble()
        val cr = CornerRadius(cw * 0.24f, cw * 0.24f)
        val t0 = if (hasT) timestamps.first() else 0L
        for (i in 0 until cells) {
            val r = i / cols; val c = i % cols
            val x = c * (cw + gap); val y = r * (chh + gap)
            var sum = 0.0; var cnt = 0
            for (k in series.indices) {
                val b = if (hasT) (((timestamps[k] - t0) / cellMs).toInt()).coerceIn(0, cells - 1)
                        else (k * cells / series.size)
                if (b == i) { sum += series[k]; cnt++ }
            }
            val isMoving = if (cnt > 0) sum / cnt >= 1.0 else false
            val isLast = i == cells - 1
            when {
                isMoving -> drawRoundRect(
                    if (isLast) moving.copy(alpha = 0.5f) else moving,
                    Offset(x, y), Size(cw, chh), cr
                )
                else -> drawRoundRect(
                    fg.copy(alpha = 0.10f), Offset(x, y), Size(cw, chh), cr,
                    style = Stroke(1.1.dp.toPx())
                )
            }
        }
    }
}
