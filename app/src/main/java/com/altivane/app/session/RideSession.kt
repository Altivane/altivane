package com.altivane.app.session

import com.altivane.app.domain.Coach
import com.altivane.app.domain.CoachEvent
import com.altivane.app.domain.KmSplit
import com.altivane.app.domain.LiveState
import com.altivane.app.domain.Phase
import com.altivane.app.domain.RidePoint
import com.altivane.app.domain.RideStats
import com.altivane.app.domain.SportType
import com.altivane.app.domain.TimedVal
import com.altivane.app.domain.TimedValF
import com.altivane.app.domain.Waypoint
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 实时运动会话：服务写入，UI 读取。单例（挂在 RideApp）。
 * 支持 骑行/徒步 两种运动。
 */
class RideSession {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    private val _points = MutableStateFlow<List<RidePoint>>(emptyList())
    val points: StateFlow<List<RidePoint>> = _points

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints

    /** 心率/踏频样本时序：随 BLE 直接收取，不依赖 GPS 点（静止被过滤时图表仍有数据） */
    private val _hrSamples = MutableStateFlow<List<TimedVal>>(emptyList())
    val hrSamples: StateFlow<List<TimedVal>> = _hrSamples

    private val _cadSamples = MutableStateFlow<List<TimedVal>>(emptyList())
    val cadSamples: StateFlow<List<TimedVal>> = _cadSamples

    /** 气压样本时序（EMA 平滑后 hPa）：气压卡曲线与天气趋势数据源，空闲态也记录 */
    private val _pressureSamples = MutableStateFlow<List<TimedValF>>(emptyList())
    val pressureSamples: StateFlow<List<TimedValF>> = _pressureSamples

    /** 气压高度基准：运动开始时的气压与 GPS 海拔（GPS 校准零点，之后气压灵敏追踪） */
    private var basePressure = 0f
    private var baseAltM = 0.0

    /** 每公里节点事件（服务播报） */
    private val _kmEvents = MutableSharedFlow<KmSplit>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val kmEvents: SharedFlow<KmSplit> = _kmEvents

    /** AI 教练事件（实时建议 / 打卡确认 / 滑雪趟完成播报）：资源 id + 参数，调用方 getString */
    private val _coachEvents = MutableSharedFlow<CoachEvent>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val coachEvents: SharedFlow<CoachEvent> = _coachEvents

    /** 刚结束的 rideId（UI 弹拉伸清单用），0 = 无 */
    private val _lastFinished = MutableStateFlow(0L)
    val lastFinished: StateFlow<Long> = _lastFinished

    /** 已展示过热身清单的开始时间（挂在单例跨页面持久，防止返回页面后重复弹出） */
    @Volatile
    var warmupShownFor: Long = 0L

    /** 上次运动快照：结束后保留显示，直到下次运动覆盖 */
    private val _lastSnapshot = MutableStateFlow<Map<SportType, LiveState>>(emptyMap())
    val lastSnapshot: StateFlow<Map<SportType, LiveState>> = _lastSnapshot

    /** 上次运动的轨迹点（徒步主卡空闲态显示用） */
    private val _lastPoints = MutableStateFlow<Map<SportType, List<RidePoint>>>(emptyMap())
    val lastPoints: StateFlow<Map<SportType, List<RidePoint>>> = _lastPoints

    /**
     * 冷启动从 Room 恢复上次运动快照（进程被杀后数据不再丢）。
     * 双保险：录制中不覆盖；内存已有该类型快照（刚结束的会话）不覆盖。
     */
    fun restoreSnapshot(sport: SportType, snap: LiveState, pts: List<RidePoint>) {
        if (_state.value.phase != Phase.IDLE) return
        if (_lastSnapshot.value[sport] != null) return
        _lastSnapshot.value = _lastSnapshot.value + (sport to snap)
        _lastPoints.value = _lastPoints.value + (sport to pts)
    }

    private var elevMin = 0.0
    private var lastLocT = 0L
    private var lastEle = 0.0

    // 每公里节点
    private var lastKmMark = 0.0
    private var kmStartT = 0L
    private var kmStartGain = 0.0

    // 徒步爬升打卡
    private var lastGainMark = 0.0

    // AI 教练规则状态
    private var lastHydrateT = 0L
    private var zone5Since = 0L
    private var lastHrAdviceT = 0L

    fun setSport(s: SportType) {
        if (_state.value.phase == Phase.IDLE) {
            _state.value = _state.value.copy(sport = s)
        }
    }

    fun startNew(rideId: Long, at: Long) {
        elevMin = 0.0
        lastLocT = 0
        lastEle = 0.0
        lastKmMark = 0.0
        kmStartT = at
        kmStartGain = 0.0
        lastGainMark = 0.0
        lastHydrateT = at
        zone5Since = 0
        lastHrAdviceT = 0
        _lastFinished.value = 0
        val sport = _state.value.sport
        _state.value = LiveState(
            phase = Phase.RECORDING, sport = sport, startTime = at, rideId = rideId,
            hasBaro = _state.value.hasBaro
        )
        _points.value = emptyList()
        _waypoints.value = emptyList()
        _hrSamples.value = emptyList()
        _cadSamples.value = emptyList()
        _pressureSamples.value = emptyList()
        // 气压高度基准：以当前 GPS 海拔校准气压零点（登山=海拔追踪，骑行=相对高程归零）
        basePressure = _state.value.pressureHpa
        baseAltM = _state.value.altM
    }

    fun addPoint(p: RidePoint) {
        val s = _state.value
        if (s.phase != Phase.RECORDING) return
        val pts = _points.value
        val last = pts.lastOrNull()

        var dist = s.distanceM
        if (last != null) dist += RideStats.haversine(last.lat, last.lon, p.lat, p.lon)

        val dt = if (lastLocT == 0L) 0L else (p.t - lastLocT).coerceAtLeast(0)
        lastLocT = p.t
        var moving = s.movingMs
        if (dt in 1..5000 && p.speed >= 0.7f) moving += dt // >2.5km/h 计入移动时间；时钟跳变忽略

        // 爬升：谷-峰水位跟踪
        var gain = s.elevGainM
        if (pts.isEmpty()) { elevMin = p.ele; lastEle = p.ele }
        when {
            p.ele > elevMin + 3.0 -> { gain += p.ele - elevMin; elevMin = p.ele }
            p.ele < elevMin -> elevMin = p.ele
        }

        // 消耗：dt 内按当前心率/速度速率积分
        var kcal = s.kcal
        if (dt in 1..5000 && p.speed >= 0.7f) {
            kcal += Coach.kcalRate(p.hr, p.speed * 3.6, s.sport) * dt / 60000.0
        }

        val speed = p.speed * 3.6

        // 每公里节点
        if (dist >= lastKmMark + 1000.0 && last != null) {
            val idx = (dist / 1000.0).toInt()
            lastKmMark = idx * 1000.0
            _kmEvents.tryEmit(KmSplit(idx, p.t - kmStartT, gain - kmStartGain, p.t))
            kmStartT = p.t
            kmStartGain = gain
        }

        _points.value = pts + p
        if (last != null) alignCourse(p, last)
        _state.value = s.copy(
            distanceM = dist,
            movingMs = moving,
            speedKmh = speed,
            maxKmh = maxOf(s.maxKmh, speed),
            altM = p.ele,
            maxAltM = maxOf(s.maxAltM, p.ele),
            elevGainM = gain,
            kcal = kcal,
            pointCount = pts.size + 1,
            avgKmh = if (moving > 0) dist / (moving / 1000.0) * 3.6 else 0.0,
            paceMinPerKm = if (speed > 0.5) 60.0 / speed else 0.0
        )

        if (s.sport == SportType.HIKE) {
            hikeAutoWaypoint(gain, p.ele)
        }
        coachRules(p, s.copy(distanceM = dist, movingMs = moving), p.t)
        lastEle = p.ele
    }

    /** 徒步：每爬升 100m 自动打卡 */
    private fun hikeAutoWaypoint(gain: Double, ele: Double) {
        if (gain - lastGainMark >= 100.0) {
            lastGainMark = (gain / 100.0).toInt() * 100.0
            addWaypoint("C", "爬升 ${gain.toInt()} 米")
        }
    }

    /** AI 教练规则：心率区间 / 补水提醒（带冷却） */
    private fun coachRules(p: RidePoint, s: LiveState, now: Long) {
        val zone = Coach.heartZone(p.hr)
        if (zone >= 4) {
            if (zone5Since == 0L) zone5Since = now
            if (now - zone5Since > 30_000 && now - lastHrAdviceT > 180_000) {
                Coach.hrAdviceRes(zone, s.sport)?.let {
                    _coachEvents.tryEmit(CoachEvent(it))
                    lastHrAdviceT = now
                }
            }
        } else {
            zone5Since = 0
        }
        if (s.movingMs > 45 * 60_000 && now - lastHydrateT > 45 * 60_000) {
            _coachEvents.tryEmit(CoachEvent(Coach.hydrateRes(), listOf((s.movingMs / 60_000).toInt())))
            lastHydrateT = now
        }
    }

    fun addWaypoint(kind: String, label: String, photo: String? = null) {
        val p = _points.value.lastOrNull() ?: return
        val wp = Waypoint(p.t, p.lat, p.lon, p.ele, kind, label, photo)
        _waypoints.value = _waypoints.value + wp
        _state.value = _state.value.copy(waypointCount = _waypoints.value.size)
        if (kind == "M") _coachEvents.tryEmit(CoachEvent(Coach.wpMsgRes(), listOf(label)))
    }

    /**
     * 指南针 GPS 航向自动对齐（v0.8.46）：磁力计硬/软铁畸变会漂（画 8 字管一阵又漂）。
     * 移动中（>1.2 m/s）GPS 地面航向是真值——与磁航向的角差按 EMA α=0.05 慢速累积为
     * courseOff（clamp ±50°），显示航向 = 磁航向 + courseOff。静止/低速不修（bearing 噪声大）。
     */
    private var rawHeading = 0f
    private var courseOff = 0f

    fun setHeading(deg: Float, has: Boolean) {
        rawHeading = deg
        val corrected = (deg + courseOff + 360f) % 360f
        val s = _state.value
        // 去抖：航向变化 <0.5° 不发新状态（空闲态传感器常驻，避免高频重组）
        if (s.hasCompass == has && kotlin.math.abs(s.headingDeg - corrected) < 0.5f) return
        _state.value = s.copy(headingDeg = corrected, hasCompass = has)
    }

    private fun alignCourse(p: RidePoint, last: RidePoint) {
        if (p.speed < 1.2f) return
        val dLon = Math.toRadians(p.lon - last.lon)
        val brg = Math.toDegrees(
            kotlin.math.atan2(
                kotlin.math.sin(dLon) * kotlin.math.cos(Math.toRadians(p.lat)),
                kotlin.math.cos(Math.toRadians(last.lat)) * kotlin.math.sin(Math.toRadians(p.lat)) -
                        kotlin.math.sin(Math.toRadians(last.lat)) *
                        kotlin.math.cos(Math.toRadians(p.lat)) * kotlin.math.cos(dLon)
            )
        ).toFloat()
        var d = brg - (rawHeading + courseOff)
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        val newOff = (courseOff + d * 0.05f).coerceIn(-50f, 50f)
        if (kotlin.math.abs(newOff - courseOff) > 0.05f) {
            courseOff = newOff
            // 立即重发修正后的航向（下一传感器事件前 UI/小部件即可看到）
            val s = _state.value
            if (s.hasCompass) {
                _state.value = s.copy(headingDeg = (rawHeading + courseOff + 360f) % 360f)
            }
        }
    }

    fun setTilt(deg: Float, dir: Float) {
        val s = _state.value
        if (kotlin.math.abs(s.tiltDeg - deg) < 0.5f &&
            kotlin.math.abs(s.tiltDir - dir) < 2f
        ) return
        _state.value = s.copy(tiltDeg = deg, tiltDir = dir)
    }

    fun setHeadingAcc(acc: Int) {
        val s = _state.value
        if (s.headingAcc == acc) return
        _state.value = s.copy(headingAcc = acc)
    }

    fun setAltitude(alt: Double) {
        // 气压海拔（平滑）仅覆盖实时显示；爬升/落差统计走 addPoint（GPS 口径）
        val s = _state.value
        if (s.phase == Phase.IDLE) return
        _state.value = s.copy(altM = alt)
    }

    fun setHr(v: Int) {
        _state.value = _state.value.copy(hr = v)
        if (v > 0) {
            // 有效样本入时序（cap 7200 ≈ 2h @1Hz），静止无 GPS 点也不丢
            _hrSamples.value = (_hrSamples.value + TimedVal(System.currentTimeMillis(), v))
                .takeLast(7200)
        }
    }

    fun setCad(v: Int) {
        _state.value = _state.value.copy(cad = v)
        if (v >= 0) {
            _cadSamples.value = (_cadSamples.value + TimedVal(System.currentTimeMillis(), v))
                .takeLast(7200)
        }
    }

    /** 最近 GPS 定位精度（主页信号条） */
    fun setGpsAcc(accM: Float) { _state.value = _state.value.copy(gpsAcc = accM) }

    /** 设备气压计能力（无传感器 → 气压卡/详情显示空态） */
    fun setHasBaro(v: Boolean) { _state.value = _state.value.copy(hasBaro = v) }

    /** 气压计读数（hPa，EMA 平滑后）：样本时序 + 气压高度实时换算（空闲态也记录） */
    fun setPressure(hpa: Float) {
        val old = _state.value.pressureHpa
        if (old > 0 && kotlin.math.abs(old - hpa) < 0.1f) return
        _state.value = _state.value.copy(pressureHpa = hpa)
        _pressureSamples.value = (_pressureSamples.value + TimedValF(System.currentTimeMillis(), hpa.toDouble()))
            .takeLast(3600)
        // 气压高度：相对运动开始点（ISA 公式）。徒步海拔 = 起点GPS海拔 + 气压差高；骑行 = 纯相对高程
        if (basePressure > 0f) {
            val rel = RideStats.relativeAltitude(hpa.toDouble(), basePressure.toDouble())
            val alt = baseAltM + rel
            _state.value = _state.value.copy(relAltM = rel, altBaroM = alt, baseAltSet = true)
        }
    }

    fun pause() {
        if (_state.value.phase == Phase.RECORDING) {
            _state.value = _state.value.copy(phase = Phase.PAUSED)
        }
    }

    fun resume() {
        if (_state.value.phase == Phase.PAUSED) {
            lastLocT = 0
            _state.value = _state.value.copy(phase = Phase.RECORDING)
        }
    }

    fun markFinished(rideId: Long) {
        _lastFinished.value = rideId
    }

    fun reset() {
        val s = _state.value
        // 结束一次有效运动（非空轨迹）时按运动类型分存快照（骑/徒数据隔离）
        if (s.rideId != 0L) {
            _lastSnapshot.value = _lastSnapshot.value + (s.sport to s)
            _lastPoints.value = _lastPoints.value + (s.sport to _points.value)
        }
        _state.value = LiveState(sport = s.sport, hasBaro = s.hasBaro)
        _points.value = emptyList()
        _waypoints.value = emptyList()
    }
}
