package com.altivane.app.domain

import com.altivane.app.R

/** 运动类型（labelRes 五语：中/英/俄/法/西） */
enum class SportType(val labelRes: Int) {
    HIKE(R.string.sport_hike),
    RIDE(R.string.sport_ride)
}

/** 打卡点：手动打卡 M / 每1km自动 K / 每100m爬升自动 C */
data class Waypoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val kind: String,
    val label: String,
    val photo: String? = null
)

/** 可排序卡片（labelRes 五语） */
enum class CardType(val labelRes: Int) {
    DISTANCE(R.string.card_distance),
    DURATION(R.string.card_duration),
    PACE(R.string.card_pace),
    HR(R.string.card_hr),
    CAD(R.string.card_cad),
    CALS(R.string.card_cals),
    MAX_SPEED(R.string.card_max_speed),
    COMPASS(R.string.card_compass),
    BARO(R.string.card_baro),
    ALTITUDE(R.string.card_altitude),
    TRACK(R.string.card_track),
    PROFILE(R.string.card_profile)
}

/** 单例实时状态（服务写，UI 读） */
data class LiveState(
    val phase: Phase = Phase.IDLE,
    val sport: SportType = SportType.HIKE,
    val startTime: Long = 0,
    val movingMs: Long = 0,
    val distanceM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val maxKmh: Double = 0.0,
    val avgKmh: Double = 0.0,
    val paceMinPerKm: Double = 0.0,
    val altM: Double = 0.0,
    val maxAltM: Double = 0.0,
    val elevGainM: Double = 0.0,
    val kcal: Double = 0.0,
    val headingDeg: Float = 0f,
    val hasCompass: Boolean = false,
    /** 旋转矢量精度（SENSOR_STATUS_*：3高/2中/1低/0不可靠/-1无数据；罗盘校准卡用） */
    val headingAcc: Int = -1,
    /** 设备平面倾角 0-90°（0=水平；指南针外圈水平仪弧，水平不显示） */
    val tiltDeg: Float = 0f,
    /** 倾斜低侧屏幕方向角（0°=屏幕上方顺时针；水平仪弧的出现方位） */
    val tiltDir: Float = 0f,
    val pressureHpa: Float = 0f,
    /** 设备有气压计（无传感器时气压卡显示空态，不再等数据） */
    val hasBaro: Boolean = true,
    /** 气压高度基准已建立（运动开始时以 GPS 海拔校准） */
    val baseAltSet: Boolean = false,
    /** 气压计高度：徒步=海拔（起点 GPS 校准 + 气压追踪），骑行=相对起点高程（可负） */
    val altBaroM: Double = 0.0,
    val relAltM: Double = 0.0,
    val hr: Int = -1,
    val cad: Int = -1,
    val hrDevice: String = "",
    val cadDevice: String = "",
    val pointCount: Int = 0,
    val waypointCount: Int = 0,
    /** 最近 GPS 定位精度 m（-1 = 无定位）；主页大卡信号条数据源 */
    val gpsAcc: Float = -1f,
    val rideId: Long = 0
)

enum class Phase { IDLE, RECORDING, PAUSED }

/** 轨迹点（内存中流转；入库时转 PointEntity） */
data class RidePoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val speed: Float = 0f,
    val hr: Int = -1,
    val cad: Int = -1
)

/** 独立于 GPS 的时间戳采样（心率/踏频）：静止被 GPS 过滤时样本不丢 */
data class TimedVal(val t: Long, val v: Int)

/** Double 版采样（气压 hPa 需保留小数精度） */
data class TimedValF(val t: Long, val v: Double)

/** 公里分段时间（每公里节点播报用） */
data class KmSplit(val index: Int, val splitMs: Long, val eleGainM: Double, val t: Long)
