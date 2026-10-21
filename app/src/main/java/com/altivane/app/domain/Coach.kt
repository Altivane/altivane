package com.altivane.app.domain

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

    fun startMsg(sport: SportType): String = when (sport) {
        SportType.RIDE -> "${sport.label}开始，注意安全，先热身再发力"
        SportType.HIKE -> "${sport.label}开始，保持节奏，前两公里慢走热身"
    }

    fun kmMessage(idx: Int, splitMs: Long, sport: SportType, kcal: Double): String =
        "第 $idx 公里，用时 ${fmtDur(splitMs)}，累计消耗 ${kcal.toInt()} 千卡" +
                if (sport == SportType.HIKE) "，注意补充水分" else ""

    fun finishSummary(
        sport: SportType, distanceM: Double, movingMs: Long,
        elevGainM: Double, kcal: Double
    ): String =
        "${sport.label}结束，全程 ${"%.2f".format(distanceM / 1000.0)} 公里，" +
                "用时 ${fmtDur(movingMs)}，" +
                "消耗 ${kcal.toInt()} 千卡。辛苦了，记得拉伸放松"

    // ---------- 实时建议（规则触发，带冷却由调用方控制） ----------

    fun hrAdvice(zone: Int, sport: SportType): String? = when {
        zone >= 5 -> "心率进入极限区间，建议降低强度，调整呼吸"
        zone == 4 && sport == SportType.HIKE -> "心率偏高，上坡可以放缓步频"
        else -> null
    }

    fun hydrateAdvice(minutes: Long): String =
        "已连续运动 ${minutes / 60} 分钟，建议补水补盐"

    fun waypointMsg(label: String): String = "已打卡：$label"

    // ---------- 事前热身 / 事后拉伸 ----------

    val warmupRide = listOf(
        "肩颈环绕 30 秒，放松上肢",
        "高抬腿原地 30 秒激活心率",
        "弓步压腿每侧 20 秒，激活髋部",
        "手腕脚踝环绕各 10 圈",
        "第一公里用低强度骑完再提速"
    )

    val warmupHike = listOf(
        "脚踝环绕每侧 15 圈，山路防崴脚",
        "弓步压腿每侧 20 秒",
        "肩背拉伸 30 秒，调整背包肩带",
        "原地小跳 20 次激活小腿",
        "前两公里慢走热身，再进入正常步频"
    )

    val stretchRide = listOf(
        "股四头肌站立拉伸，每侧 30 秒",
        "腘绳肌前屈拉伸，每侧 30 秒",
        "小腿后侧拉伸，每侧 30 秒",
        "肩颈与背部伸展 1 分钟",
        "补充蛋白质与水分"
    )

    val stretchHike = listOf(
        "小腿腓肠肌拉伸，每侧 40 秒（下坡重点）",
        "大腿前侧与后侧拉伸各 30 秒",
        "臀部梨状肌拉伸，每侧 30 秒",
        "脚底筋膜放松，踩踩滚轴或水瓶",
        "热水泡脚缓解足部疲劳"
    )

    fun warmup(sport: SportType): List<String> = when (sport) {
        SportType.RIDE -> warmupRide
        SportType.HIKE -> warmupHike
    }

    fun stretch(sport: SportType): List<String> = when (sport) {
        SportType.RIDE -> stretchRide
        SportType.HIKE -> stretchHike
    }
}
