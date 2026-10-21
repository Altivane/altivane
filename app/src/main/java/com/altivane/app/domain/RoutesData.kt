package com.altivane.app.domain

/**
 * 内置经典登山线路（v0.8.45 恢复三山五岳全量）。
 * 节点为真实关键地点（登山口/索道站/途中景点/主峰）的地理位置，
 * 内部源数据为高德 GCJ-02 坐标（AMap POI 取值 + 地标经纬知识库近似，
 * 山岳节点误差约 100-300 m，界内平滑连线观感无碍）。
 * **公开值 [RoutesData.all] = 源数据经 gcj2wgs 逆变换后的 WGS84**（v0.8.28 起地图栈
 * 换 MapLibre + OSM 系底图，同系零偏移）。
 * 主线尽量终于主峰（黄/华/衡/恒/嵩）；庐山好汉坡线终于牯岭镇、雁荡"二灵一龙"
 * 终于大龙湫，为经典游线原貌，剖面标签随所选线路自适应（v0.8.45 起）。
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
        ),
        MountainRoute(
            "华山", "五岳 · 西岳", "陕西华阴", 2154.9, 1800.0, 10.0, 6.5,
            "自古华山一条路：千尺幢—苍龙岭，奇险天下第一山",
            listOf(
                RouteLine(
                    "自古一条路", listOf(
                        RoutePoint(34.4776, 110.0747, 430.0, "玉泉院"),
                        RoutePoint(34.4770, 110.0820, 700.0, "五里关"),
                        RoutePoint(34.4772, 110.0875, 900.0, "千尺幢"),
                        RoutePoint(34.4800, 110.0916, 1614.0, "北峰"),
                        RoutePoint(34.4815, 110.0935, 1750.0, "苍龙岭"),
                        RoutePoint(34.4830, 110.0958, 2010.0, "金锁关"),
                        RoutePoint(34.4852, 110.1005, 2096.0, "东峰"),
                        RoutePoint(34.4835, 110.0990, 2154.9, "南峰")
                    )
                )
            )
        ),
        MountainRoute(
            "衡山", "五岳 · 南岳", "湖南衡阳", 1300.2, 1200.0, 12.0, 6.0,
            "南岳独秀：祝融峰之高、藏经殿之秀、方广寺之深",
            listOf(
                RouteLine(
                    "祝融峰线", listOf(
                        RoutePoint(27.2465, 112.7345, 100.0, "南岳大庙"),
                        RoutePoint(27.2550, 112.7370, 320.0, "忠烈祠"),
                        RoutePoint(27.2690, 112.7350, 650.0, "半山亭"),
                        RoutePoint(27.2855, 112.7355, 1100.0, "南天门"),
                        RoutePoint(27.2900, 112.7385, 1200.0, "上封寺"),
                        RoutePoint(27.2935, 112.7415, 1300.2, "祝融峰")
                    )
                )
            )
        ),
        MountainRoute(
            "恒山", "五岳 · 北岳", "山西大同浑源", 2016.1, 800.0, 6.5, 4.0,
            "人天北柱，绝塞名山：山下悬空寺，北岳庙群依山而建",
            listOf(
                RouteLine(
                    "天峰岭线", listOf(
                        RoutePoint(39.6690, 113.7320, 1250.0, "停旨岭"),
                        RoutePoint(39.6718, 113.7365, 1450.0, "虎风口"),
                        RoutePoint(39.6735, 113.7390, 1650.0, "恒宗殿"),
                        RoutePoint(39.6748, 113.7405, 1800.0, "会仙府"),
                        RoutePoint(39.6768, 113.7442, 2016.1, "天峰岭")
                    )
                )
            )
        ),
        MountainRoute(
            "嵩山", "五岳 · 中岳", "河南登封", 1491.7, 950.0, 7.5, 5.0,
            "太室峻极于天：嵩阳书院起步，禅宗祖庭少林寺环伺",
            listOf(
                RouteLine(
                    "峻极峰线", listOf(
                        RoutePoint(34.5052, 113.0372, 540.0, "嵩阳书院"),
                        RoutePoint(34.5105, 113.0345, 750.0, "老君洞"),
                        RoutePoint(34.5162, 113.0318, 1000.0, "中岳行宫"),
                        RoutePoint(34.5195, 113.0305, 1200.0, "一线天"),
                        RoutePoint(34.5230, 113.0300, 1491.7, "峻极峰")
                    )
                )
            )
        ),
        MountainRoute(
            "黄山", "三山", "安徽黄山", 1864.8, 1200.0, 7.5, 5.0,
            "前山慈光阁上山：迎客松—莲花峰—光明顶，'登黄山天下无山'",
            listOf(
                RouteLine(
                    "前山线", listOf(
                        RoutePoint(30.1300, 118.1740, 700.0, "慈光阁"),
                        RoutePoint(30.1292, 118.1708, 1340.0, "半山寺"),
                        RoutePoint(30.1312, 118.1672, 1680.0, "玉屏楼"),
                        RoutePoint(30.1272, 118.1664, 1864.8, "莲花峰"),
                        RoutePoint(30.1345, 118.1585, 1860.0, "光明顶")
                    )
                ),
                RouteLine(
                    "后山线", listOf(
                        RoutePoint(30.1520, 118.1745, 890.0, "云谷寺"),
                        RoutePoint(30.1500, 118.1665, 1780.0, "白鹅岭"),
                        RoutePoint(30.1480, 118.1635, 1630.0, "北海"),
                        RoutePoint(30.1345, 118.1585, 1860.0, "光明顶")
                    )
                )
            )
        ),
        MountainRoute(
            "庐山", "三山", "江西九江", 1474.0, 900.0, 9.0, 5.0,
            "好汉坡民国古道登牯岭：'匡庐奇秀甲天下'，云雾与别墅群",
            listOf(
                RouteLine(
                    "好汉坡线", listOf(
                        RoutePoint(29.5885, 115.9745, 300.0, "莲花洞"),
                        RoutePoint(29.5760, 115.9780, 800.0, "好汉坡"),
                        RoutePoint(29.5672, 115.9805, 1050.0, "望江亭"),
                        RoutePoint(29.5620, 115.9770, 1160.0, "牯岭镇"),
                        RoutePoint(29.5610, 115.9630, 1100.0, "如琴湖·花径"),
                        RoutePoint(29.5578, 115.9621, 1040.0, "仙人洞")
                    )
                )
            )
        ),
        MountainRoute(
            "雁荡山", "三山", "浙江温州乐清", 1056.5, 450.0, 12.0, 5.5,
            "'二灵一龙'经典游线：灵峰—灵岩—大龙湫，海上名山、寰中绝胜",
            listOf(
                RouteLine(
                    "二灵一龙线", listOf(
                        RoutePoint(29.3630, 121.0550, 80.0, "响岭头"),
                        RoutePoint(29.3700, 121.0590, 150.0, "灵峰·合掌峰"),
                        RoutePoint(29.3550, 121.0470, 260.0, "灵岩·天柱峰"),
                        RoutePoint(29.3400, 121.0290, 320.0, "大龙湫")
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
