package com.altivane.app.domain

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.cos

object RideStats {

    /** 两点球面距离（米） */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * 累计爬升：谷-峰跟踪算法。
     * 水位 min 跟随下降；上升超过 threshold 才计入 gain 并把水位抬到峰值。
     * 有效过滤 GPS 海拔 ±2m 噪声。
     */
    fun elevGain(altitudes: List<Double>, threshold: Double = 3.0): Double {
        if (altitudes.isEmpty()) return 0.0
        var gain = 0.0
        var min = altitudes[0]
        for (a in altitudes) {
            when {
                a > min + threshold -> { gain += a - min; min = a }
                a < min -> min = a
            }
        }
        return gain
    }

    // ---------- 详情页分析（纯函数，UI 与测试共用） ----------

    /**
     * 气压计高度（ISA 国际标准大气模型，简化指数公式）：
     * Δh = 44330 × ((P0/P)^0.190295 − 1)
     * P0 = 基准气压（运动开始时以 GPS 海拔校准），返回相对起点的高度差 m（上升为正）。
     * 气压计分辨力 0.1 hPa ≈ 0.8m，比 GPS 海拔（±5m 噪声）灵敏得多。
     */
    fun relativeAltitude(p: Double, p0: Double): Double =
        if (p0 <= 0.0 || p <= 0.0) 0.0
        else 44330.0 * ((p0 / p).pow(0.190295) - 1.0)

    /**
     * 每公里配速（min/km）：按累计距离跨越每个整公里节点时的时间差。
     * 返回第 1..N 公里的配速列表，数据不足返回空表。
     */
    fun kmPaces(pts: List<RidePoint>): List<Double> {
        if (pts.size < 2) return emptyList()
        val out = ArrayList<Double>()
        var dist = 0.0
        var nextKm = 1000.0
        var kmStartT = pts[0].t
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val seg = haversine(a.lat, a.lon, b.lat, b.lon)
            dist += seg
            if (dist >= nextKm) {
                val dt = (b.t - kmStartT).coerceAtLeast(1L)
                out.add(dt / 60000.0)
                kmStartT = b.t
                nextKm += 1000.0
            }
        }
        return out
    }

    /** 五档心率区间时长（秒）：Z1..Z5；无心率样本返回全 0 */
    fun zoneSeconds(pts: List<RidePoint>): LongArray {
        val zones = LongArray(5)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dt = (b.t - a.t)
            if (dt in 1..8000 && a.hr > 0) {
                val z = Coach.heartZone(a.hr)
                if (z in 1..5) zones[z - 1] += dt / 1000L
            }
        }
        return zones
    }

    /**
     * 消耗按强度分档：低(Z1-2) / 中(Z3) / 高(Z4-5)。
     * 有心率按区间分桶；无心率按速度分桶（慢/中/快）。
     */
    fun kcalSplit(pts: List<RidePoint>, sport: SportType): DoubleArray {
        val out = DoubleArray(3)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dt = (b.t - a.t)
            if (dt !in 1..8000) continue
            val speedKmh = a.speed * 3.6
            if (speedKmh < 0.7 && a.hr <= 0) continue
            val rate = Coach.kcalRate(a.hr, speedKmh, sport)
            val kcal = rate * dt / 60000.0
            val bucket = if (a.hr > 0) {
                when (Coach.heartZone(a.hr)) {
                    1, 2 -> 0; 3 -> 1; else -> 2
                }
            } else {
                when {
                    speedKmh < 12.0 -> 0
                    speedKmh < 22.0 -> 1
                    else -> 2
                }
            }
            out[bucket] += kcal
        }
        return out
    }
}
