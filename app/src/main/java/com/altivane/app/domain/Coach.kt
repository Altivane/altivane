package com.altivane.app.domain

import com.altivane.app.R
import kotlin.math.max

/**
 * 离线 AI 教练：规则引擎。
 * 卡路里用 Keytel 心率公式（有心率时）或 MET 分桶兜底；
 * 播报文案覆盖开始/每公里/实时建议/结束总结与热身拉伸清单。
 * 预留：接入云端 LLM 时替换文案生成入口即可。
 */
object Coach {

    const val DEFAULT_WEIGHT = 70.0
    const val DEFAULT_AGE = 30

    /** Keytel 公式（男性默认），kcal/min */
    fun kcalPerMin(hr: Int, weight: Double, age: Int): Double =
        (-55.0969 + 0.6309 * hr + 0.1988 * weight + 0.2017 * age) / 4.184

    /** MET 分桶（无心率兜底） */
    fun met(speedKmh: Double, sport: SportType): Double {
        val s = max(0.0, speedKmh)
        return when (sport) {
            SportType.RIDE -> when {
                s < 15 -> 4.0; s < 19 -> 6.0; s < 22 -> 8.0; s < 25 -> 10.0; s < 30 -> 12.0; else -> 15.8
            }
            SportType.HIKE -> when {
                s < 1 -> 1.3; s < 3 -> 2.5; s < 4.5 -> 3.3; s < 5.5 -> 4.3; s < 6.5 -> 6.0; else -> 8.0
            }
        }
    }

    /** kcal/min：有心率走 Keytel，否则 MET 兜底 */
    fun kcalRate(hr: Int, speedKmh: Double, sport: SportType, weight: Double = DEFAULT_WEIGHT, age: Int = DEFAULT_AGE): Double =
        if (hr > 0) {
            kcalPerMin(hr, weight, age).coerceIn(1.0, 30.0)
        } else {
            met(speedKmh, sport) * 3.5 * weight / 200.0
        }

    /** 心率区间 1-5（%HRmax） */
    fun heartZone(hr: Int, age: Int = DEFAULT_AGE): Int {
        if (hr <= 0) return 0
        val max = (220 - age).toDouble()
        val pct = hr / max * 100.0
        return when {
            pct < 60 -> 1; pct < 70 -> 2; pct < 80 -> 3; pct < 90 -> 4; else -> 5
        }
    }

    fun paceText(minPerKm: Double): String {
        if (minPerKm <= 0.0 || minPerKm > 200.0) return "--'--"
        val m = minPerKm.toInt()
        val s = ((minPerKm - m) * 60).toInt()
        return "%d'%02d\"".format(m, s)
    }

    fun fmtDur(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        // 自带单位：1时02分 / 05分30秒 / 42秒
        return when {
            h > 0 -> "%d时%02d分".format(h, m)
            m > 0 -> "%d分%02d秒".format(m, s)
            else -> "%d秒".format(s)
        }
    }

    // ---------- 播报文案（资源化：中英随应用语言） ----------

    fun startMsgRes(sport: SportType): Int = when (sport) {
        SportType.RIDE -> R.string.coach_start_ride
        SportType.HIKE -> R.string.coach_start_hike
    }

    fun kmHikeSuffixRes(sport: SportType): Int? =
        if (sport == SportType.HIKE) R.string.coach_km_hike_suffix else null

    fun finishSummaryRes(sport: SportType): Int = R.string.coach_finish_fmt

    fun hrAdviceRes(zone: Int, sport: SportType): Int? = when {
        zone >= 5 -> R.string.coach_hr_zone5
        zone == 4 && sport == SportType.HIKE -> R.string.coach_hr4_hike
        else -> null
    }

    fun hydrateRes(): Int = R.string.coach_hydrate_fmt

    fun wpMsgRes(): Int = R.string.coach_wp_fmt

    // ---------- 事前热身 / 事后拉伸（资源化） ----------

    val warmupRide = listOf(
        R.string.warmup_ride_1,
        R.string.warmup_ride_2,
        R.string.warmup_ride_3,
        R.string.warmup_ride_4,
        R.string.warmup_ride_5
    )

    val warmupHike = listOf(
        R.string.warmup_hike_1,
        R.string.warmup_hike_2,
        R.string.warmup_hike_3,
        R.string.warmup_hike_4,
        R.string.warmup_hike_5
    )

    val stretchRide = listOf(
        R.string.stretch_ride_1,
        R.string.stretch_ride_2,
        R.string.stretch_ride_3,
        R.string.stretch_ride_4,
        R.string.stretch_ride_5
    )

    val stretchHike = listOf(
        R.string.stretch_hike_1,
        R.string.stretch_hike_2,
        R.string.stretch_hike_3,
        R.string.stretch_hike_4,
        R.string.stretch_hike_5
    )

    fun warmup(sport: SportType): List<Int> = when (sport) {
        SportType.RIDE -> warmupRide
        SportType.HIKE -> warmupHike
    }

    fun stretch(sport: SportType): List<Int> = when (sport) {
        SportType.RIDE -> stretchRide
        SportType.HIKE -> stretchHike
    }
}

/** 教练播报事件：资源 id + 格式参数（调用方 getString 后送 TTS） */
data class CoachEvent(val resId: Int, val args: List<Any> = emptyList())
