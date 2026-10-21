package com.altivane.app.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.altivane.app.data.PointEntity
import com.altivane.app.data.RideEntity
import com.altivane.app.data.WaypointEntity
import com.altivane.app.domain.Coach
import com.altivane.app.domain.SportType
import kotlin.math.cos
import kotlin.math.min

/** 海报主题：暗色（暗夜竞速）/ 明色（日光），跟随 App 当前主题 */
private class Theme(
    val bg: Int,
    val line: Int,
    val text: Int,
    val dim: Int,
    val accent: Int,
    val frame: Int
)

/**
 * 分享图渲染器：1080x1620 海报。
 * v0.8.31：轨迹区改为 MapLibre MapSnapshotter 真实地图底图（Liberty 矢量/离线包同源可加速），
 * 彩虹轨迹用 pixelForLatLng 投影叠加；底图失败自动回退纯色海报。
 * 主题自适应：dark=true 深空黑底白字 / dark=false 浅色底深字。
 */
object ShareImageRenderer {
    const val W = 1080
    const val H = 1620
    private const val TRACK_TOP = 230f
    private const val TRACK_BOTTOM = 1030f
    private const val PAD = 90f

    /** 地图底图尺寸 = 轨迹区尺寸（MapSnapshotter 按 1:1 像素投影） */
    const val MAP_W = (W - PAD * 2).toInt()   // 900
    const val MAP_H = (TRACK_BOTTOM - TRACK_TOP).toInt()  // 800

    // 与 UI AvColors 一致的 7 色
    private val RAINBOW = intArrayOf(
        0xFF40E0FF.toInt(), 0xFF5B9DFF.toInt(), 0xFF4CD9A5.toInt(),
        0xFFFFD84D.toInt(), 0xFFFF9F45.toInt(), 0xFFFF6B8A.toInt(), 0xFFB08CFF.toInt()
    )

    /** 速度 → RGB（慢=紫蓝 → 快=红），与 UI Color.hsv 保持一致 */
    fun rainbow(speedKmh: Double, maxKmh: Double): Int {
        val t = (speedKmh / maxKmh.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val hue = (270.0 - t * 270.0).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.80f, 0.98f))
    }

    fun rainbowMax(sport: SportType): Double = when (sport) {
        SportType.RIDE -> 35.0
        SportType.HIKE -> 10.0
    }

    private fun themeOf(dark: Boolean): Theme = if (dark) Theme(
        0xFF0D1117.toInt(), 0x1AFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
        0x8CFFFFFF.toInt(), 0xFF22D3EE.toInt(), 0x33FFFFFF.toInt()
    ) else Theme(
        0xFFF6F8FB.toInt(), 0x14000000.toInt(), 0xFF16212E.toInt(),
        0x9916212E.toInt(), 0xFF0891B2.toInt(), 0x22000000.toInt()
    )

    fun render(
        pts: List<PointEntity>,
        wps: List<WaypointEntity>,
        ride: RideEntity?,
        mapBmp: Bitmap? = null,
        projPts: List<FloatArray>? = null,
        projWps: List<FloatArray>? = null,
        dark: Boolean = true,
        sportName: String = ""
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sport = try {
            SportType.valueOf(ride?.sportType ?: "RIDE")
        } catch (_: Exception) {
            SportType.RIDE
        }
        val th = themeOf(dark)

        c.drawColor(th.bg)

        val hasMap = mapBmp != null && projPts != null && projPts.size == pts.size
        if (hasMap) {
            drawMapTrack(c, mapBmp!!, projPts!!, projWps, pts, wps, sport, th)
        } else {
            drawTrack(c, pts, wps, sport, th)
        }
        drawRainbowBar(c, th)
        drawPanel(c, pts, wps, ride, sport, th, sportName)
        return bmp
    }

    // ---------- 轨迹 ----------

    /** 地图底图版：MapSnapshotter 位图圆角贴入 + 彩虹轨迹投影叠加（白晕提高可读） */
    private fun drawMapTrack(
        c: Canvas, mapBmp: Bitmap,
        projPts: List<FloatArray>, projWps: List<FloatArray>?,
        pts: List<PointEntity>, wps: List<WaypointEntity>,
        sport: SportType, th: Theme
    ) {
        val r = 28f
        val clip = Path().apply {
            addRoundRect(
                PAD, TRACK_TOP, PAD + MAP_W, TRACK_TOP + MAP_H, r, r, Path.Direction.CW
            )
        }
        val save = c.save()
        c.clipPath(clip)
        c.drawBitmap(mapBmp, PAD, TRACK_TOP, null)

        if (pts.size >= 2) {
            // 与纯色版同一管线：平滑索引 → 投影坐标（projPts 与 pts 逐点对齐）
            val keep = com.altivane.app.ui.TrackGeom.prepareIndices(
                pts.map { com.altivane.app.ui.TrackPt(it.lat, it.lon) }
            )
            val sm = keep.map { projPts[it] }
            val smSpd = keep.map { pts[it].speed }
            fun at(p: FloatArray): FloatArray = floatArrayOf(PAD + p[0], TRACK_TOP + p[1])

            val maxS = rainbowMax(sport)
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 15f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = 0xE6FFFFFF.toInt()
            }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 8f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            for (i in 0 until sm.size - 1) {
                val a = at(sm[i]); val b = at(sm[i + 1])
                c.drawLine(a[0], a[1], b[0], b[1], halo)
                p.color = rainbow(smSpd[i].toDouble(), maxS)
                c.drawLine(a[0], a[1], b[0], b[1], p)
            }
            // 打卡点（色点白环，明暗底图均可读）
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFFFFFFF.toInt()
            }
            if (projWps != null) {
                for ((i, w) in wps.withIndex()) {
                    if (i >= projWps.size) break
                    val o = at(projWps[i])
                    val dot = when (w.kind) {
                        "M" -> 0xFFFFD84D.toInt()
                        "K" -> 0xFF5B9DFF.toInt()
                        "C" -> 0xFF4CD9A5.toInt()
                        else -> 0xFFB08CFF.toInt()
                    }
                    c.drawCircle(o[0], o[1], 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot })
                    c.drawCircle(o[0], o[1], 9f, ring)
                }
            }
            // 起终点
            val s0 = at(sm.first()); val sN = at(sm.last())
            c.drawCircle(s0[0], s0[1], 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4CD9A5.toInt() })
            c.drawCircle(s0[0], s0[1], 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16212E.toInt() })
            c.drawCircle(sN[0], sN[1], 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFFFF6B8A.toInt()
            })
        }
        c.restoreToCount(save)
        // 圆角描边框
        c.drawPath(clip, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = th.frame
        })
    }

    /** 回退版：无地图底图（快照失败/无网络），速度彩虹轨迹直接画 */
    private fun drawTrack(
        c: Canvas, pts: List<PointEntity>, wps: List<WaypointEntity>,
        sport: SportType, th: Theme
    ) {
        if (pts.size < 2) return
        // 平滑管道：滑窗平滑 + 距离等距重采样 + DP 简化（长轨迹全程自适应，速度列表同步裁剪）
        val asTrack = pts.map { com.altivane.app.ui.TrackPt(it.lat, it.lon) }
        val keep = com.altivane.app.ui.TrackGeom.prepareIndices(asTrack)
        val sm = keep.map { asTrack[it] }
        val smSpd = keep.map { pts[it].speed }
        val latMin = sm.minOf { it.lat }; val latMax = sm.maxOf { it.lat }
        val lonMin = sm.minOf { it.lon }; val lonMax = sm.maxOf { it.lon }
        val midLat = Math.toRadians((latMin + latMax) / 2.0)
        val kx = cos(midLat)
        val bw = ((lonMax - lonMin) * kx).coerceAtLeast(1e-6)
        val bh = (latMax - latMin).coerceAtLeast(1e-6)
        val availW = W - PAD * 2
        val availH = TRACK_BOTTOM - TRACK_TOP
        val scale = min(availW / bw.toFloat(), availH / bh.toFloat()).toFloat()
        val offX = PAD + (availW - bw.toFloat() * scale) / 2f
        val offY = TRACK_TOP + (availH - bh.toFloat() * scale) / 2f
        fun map(lat: Double, lon: Double): FloatArray = floatArrayOf(
            offX + ((lon - lonMin) * kx * scale).toFloat(),
            offY + ((latMax - lat) * scale).toFloat()
        )

        val maxS = rainbowMax(sport)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (i in 0 until sm.size - 1) {
            p.color = rainbow(smSpd[i].toDouble(), maxS)
            val a = map(sm[i].lat, sm[i].lon)
            val b = map(sm[i + 1].lat, sm[i + 1].lon)
            c.drawLine(a[0], a[1], b[0], b[1], p)
        }
        // 打卡点
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFFFFFFF.toInt()
        }
        for (w in wps) {
            val o = map(w.lat, w.lon)
            val dot = when (w.kind) {
                "M" -> 0xFFFFD84D.toInt()
                "K" -> 0xFF5B9DFF.toInt()
                "C" -> 0xFF4CD9A5.toInt()
                else -> 0xFFB08CFF.toInt()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot }
            c.drawCircle(o[0], o[1], 9f, fill)
            c.drawCircle(o[0], o[1], 9f, ring)
        }
        // 起终点
        val s0 = map(pts.first().lat, pts.first().lon)
        val sN = map(pts.last().lat, pts.last().lon)
        val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4CD9A5.toInt() }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.bg }
        c.drawCircle(s0[0], s0[1], 15f, green)
        c.drawCircle(s0[0], s0[1], 6f, inner)
        val pink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFFFF6B8A.toInt()
        }
        c.drawCircle(sN[0], sN[1], 14f, pink)
    }

    /** 彩虹装饰条（面板顶部，点题"七彩虹"轨迹） */
    private fun drawRainbowBar(c: Canvas, th: Theme) {
        val y = 160f
        val h = 8f
        val segW = (W - PAD * 2f) / RAINBOW.size
        for (i in RAINBOW.indices) {
            val p = Paint().apply { color = RAINBOW[i] }
            c.drawRect(PAD + i * segW, y, PAD + (i + 1) * segW, y + h, p)
        }
    }

    // ---------- 数据面板 ----------

    private fun drawPanel(
        c: Canvas, pts: List<PointEntity>, wps: List<WaypointEntity>,
        ride: RideEntity?, sport: SportType, th: Theme, sportName: String
    ) {
        val dist = ride?.distanceM ?: 0.0
        val avg = ride?.avgKmh ?: 0.0
        val maxK = ride?.maxKmh ?: 0.0
        val maxAlt = ride?.maxAltM ?: 0.0
        val kcal = ride?.kcal ?: 0.0
        val movingS = (ride?.movingS ?: 0) * 1000L

        val date = ride?.let {
            java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(it.startTime))
        } ?: ""
        val sportTag = if (sportName.isEmpty()) sport.name else sportName
        c.drawText("${sportTag.uppercase()} · $date", PAD, 120f, textPaint(34f, th.dim))
        c.drawText("ALTIVANE", W - PAD - 200f, 120f, textPaint(34f, th.dim, medium = true, spacing = 0.18f))

        // 指北针装饰
        val nx = W - PAD / 2f
        val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.dim; strokeWidth = 3f; this.style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        c.drawLine(nx, 220f, nx, 170f, np)
        val tri = Path().apply {
            moveTo(nx, 162f); lineTo(nx - 8f, 178f); lineTo(nx + 8f, 178f); close()
        }
        val triFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.dim }
        c.drawPath(tri, triFill)
        c.drawText("N", nx - 11f, 208f, textPaint(26f, th.dim, medium = true))

        // 距离大字
        val distY = 1250f
        c.drawText("%.2f".format(dist / 1000.0), PAD, distY, textPaint(150f, th.text, light = true))
        val dp = textPaint(150f, th.text, light = true)
        val distW = dp.measureText("%.2f".format(dist / 1000.0))
        c.drawText("km", PAD + distW + 24f, distY, textPaint(52f, th.dim))

        // 数据网格 2x3
        val cols = 3
        val colW = (W - PAD * 2) / cols
        val rowY = floatArrayOf(1360f, 1470f)
        val items = listOf(
            "均速" to "%.1f km/h".format(avg),
            "最高" to "%.1f km/h".format(maxK),
            "时长" to Coach.fmtDur(movingS),
            "海拔" to "%.0f m".format(maxAlt),
            "消耗" to "${kcal.toInt()} kcal",
            "打卡点" to "${wps.size} 个"
        )
        items.forEachIndexed { i, (label, value) ->
            val cx = PAD + (i % cols) * colW
            val cy = rowY[i / cols]
            c.drawText(value, cx, cy, textPaint(56f, th.text, light = true))
            c.drawText(label, cx, cy + 40f, textPaint(30f, th.dim))
        }

        // 品牌行
        val brand = textPaint(34f, th.dim, spacing = 0.3f)
        val t = "ALTIVANE · RIDE BEYOND SIGNAL"
        val tw = brand.measureText(t)
        c.drawText(t, (W - tw) / 2f, H - 70f, brand)
    }

    private fun textPaint(
        size: Float, color: Int,
        medium: Boolean = false, light: Boolean = false, spacing: Float = 0f
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = when {
            light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        letterSpacing = spacing
    }
}
