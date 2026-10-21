package com.altivane.app.domain

/**
 * 内置经典登山线路（v0.8.31 起测试期仅保留泰山红门线，其余山岳线路随验证逐步恢复）。
 * 节点为真实关键地点（登山口/索道站/途中景点/主峰）的地理位置，
 * 内部源数据为高德 GCJ-02 坐标（AMap POI / bigemap 直接取值，wiki/peakwiki/实测 GPS
 * 值经标准 WGS84→GCJ-02 纠偏换算，v0.8.25 全量校准）。
 * **公开值 [RoutesData.all] = 源数据经 gcj2wgs 逆变换后的 WGS84**（v0.8.28 起地图栈
 * 换 MapLibre + OSM 系底图，同系零偏移；真实路网线见 assets/routes/planned.json）。
 */
data class RoutePoint(val lat: Double, val lon: Double, val ele: Double, val name: String)

/** 一条登山线路：起点（登山口/索道站）→ 途经 → 主峰 */
data class RouteLine(val name: String, val pts: List<RoutePoint>)

data class MountainRoute(
    val name: String,
    val tag: String,        // 所属（东岳/三山…）
    val region: String,     // 位置
    val summitEle: Double,  // 主峰海拔 m
    val gainM: Double,      // 经典线路累计爬升
    val distKm: Double,     // 主线路里程
    val hours: Double,      // 主线路参考登顶用时
    val note: String,
    val lines: List<RouteLine>
) {
    /** 主线路节点（首个线路），兼容旧字段访问 */
    val pts: List<RoutePoint> get() = lines.first().pts
}

object RoutesData {

    private val SOURCE: List<MountainRoute> = listOf(
        MountainRoute(
            "泰山", "五岳 · 东岳", "山东泰安", 1545.0, 1400.0, 9.5, 5.0,
            "红门经典线：帝王封禅古道，十八盘最陡 1600 级台阶",
            listOf(
                RouteLine(
                    "红门线", listOf(
                        // 红门=高德 POI B021A00A32（36.2102,117.1284），起点即"登泰山起点"
                        // 中天门/玉皇顶为 bigemap 高德同源值，其余沿盘道走廊落点
                        RoutePoint(36.2102, 117.1284, 160.0, "红门"),
                        RoutePoint(36.2152, 117.1288, 250.0, "万仙楼"),
                        RoutePoint(36.2218, 117.1270, 500.0, "斗母宫"),
                        RoutePoint(36.2268, 117.1224, 700.0, "壶天阁"),
                        RoutePoint(36.2391, 117.1145, 847.0, "中天门"),
                        RoutePoint(36.2452, 117.1115, 1100.0, "云步桥"),
                        RoutePoint(36.2498, 117.1098, 1350.0, "十八盘"),
                        RoutePoint(36.2526, 117.1088, 1460.0, "南天门"),
                        RoutePoint(36.2548, 117.1075, 1520.0, "天街"),
                        RoutePoint(36.2574, 117.1080, 1545.0, "玉皇顶")
                    )
                )
            )
        )
    )

    /** 公开值 = GCJ-02 源经逆变换后的 WGS84（与 MapLibre/OSM 底图同系） */
    val all: List<MountainRoute> = SOURCE.map { m ->
        m.copy(lines = m.lines.map { l ->
            l.copy(pts = l.pts.map { p ->
                val w = gcj2wgs(p.lat, p.lon)
                p.copy(lat = w[0], lon = w[1])
            })
        })
    }

    /** GCJ-02 → WGS84 逆变换（迭代逼近，精度 <1 m） */
    private fun gcj2wgs(gcjLat: Double, gcjLon: Double): DoubleArray {
        val a = 6378245.0
        val ee = 0.00669342162296594323
        fun tLat(x: Double, y: Double): Double = (
                -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
                        0.2 * kotlin.math.sqrt(kotlin.math.abs(x)) +
                        (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0 +
                        (20.0 * kotlin.math.sin(y * Math.PI) + 40.0 * kotlin.math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0 +
                        (160.0 * kotlin.math.sin(y / 12.0 * Math.PI) + 320 * kotlin.math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
                )
        fun tLon(x: Double, y: Double): Double = (
                300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * kotlin.math.sqrt(kotlin.math.abs(x)) +
                        (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0 +
                        (20.0 * kotlin.math.sin(x * Math.PI) + 40.0 * kotlin.math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0 +
                        (150.0 * kotlin.math.sin(x / 12.0 * Math.PI) + 300.0 * kotlin.math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
                )
        fun fwd(lat: Double, lon: Double): DoubleArray {
            var dLat = tLat(lon - 105.0, lat - 35.0)
            var dLon = tLon(lon - 105.0, lat - 35.0)
            val radLat = lat / 180.0 * Math.PI
            var magic = kotlin.math.sin(radLat)
            magic = 1 - ee * magic * magic
            val sq = kotlin.math.sqrt(magic)
            dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sq) * Math.PI)
            dLon = (dLon * 180.0) / (a / sq * kotlin.math.cos(radLat) * Math.PI)
            return doubleArrayOf(lat + dLat, lon + dLon)
        }
        var wLat = gcjLat
        var wLon = gcjLon
        repeat(5) {
            val g = fwd(wLat, wLon)
            wLat += gcjLat - g[0]
            wLon += gcjLon - g[1]
        }
        return doubleArrayOf(wLat, wLon)
    }
}
