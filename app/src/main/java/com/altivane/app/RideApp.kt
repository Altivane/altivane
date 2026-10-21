package com.altivane.app

import android.app.Application
import androidx.room.Room
import com.altivane.app.data.RideDb
import com.altivane.app.sensor.BleManager
import com.altivane.app.sensor.SensorEngine
import com.altivane.app.session.RideSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RideApp : Application() {
    lateinit var db: RideDb
    lateinit var ble: BleManager
    val session = RideSession()

    /** App 级协程：BLE 数据 → Session 全局桥接 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 常驻传感器：罗盘/气压在空闲态也实时更新（App 生命周期同进程） */
    private lateinit var sens: SensorEngine

    /** 罗盘桌面卡推送闸门：航向变化 ≥0.5° 且距上次推送 ≥1s 才真推（传感器回调驱动，无常驻轮询） */
    private var lastWidgetHeading = -999f
    private var lastWidgetPushT = 0L

    override fun onCreate() {
        super.onCreate()
        com.altivane.app.domain.AppLog.installCrashCapture(this)
        com.altivane.app.domain.AppLog.i("App", "onCreate start")
        // MapLibre 全局初始化（官方推荐 Application.onCreate 调用）：离线地图页的
        // OfflineManager / FileSource 也依赖 tile server options，此前只有 MapTrackView
        // 组合时才初始化——冷启动直接进离线页 = MapLibreConfigurationException 闪退
        org.maplibre.android.MapLibre.getInstance(this)
        com.altivane.app.ui.AppTheme.init(this)
        com.altivane.app.ui.ProfileStore.init(this)
        db = Room.databaseBuilder(this, RideDb::class.java, "ride.db")
            .addMigrations(RideDb.MIGRATION_1_2, RideDb.MIGRATION_2_3)
            .build()
        ble = BleManager(this)
        // 根因修复：此前 BLE 心率/踏频只进 BleManager 自身 Flow（仅设置页收集），
        // 没人写 session → 主页卡片恒显示 "--"，记录点 hr/cad 也全为 -1。
        // App 级收集一次，主页/详情/记录/桌面小部件全链路共享。
        appScope.launch {
            ble.hr.collect {
                if (it > 0) {
                    session.setHr(it)
                    // 空闲态也同步桌面心率小部件（4s 节流在 WidgetPush 内）
                    com.altivane.app.widget.WidgetPush.push(this@RideApp, session.state.value)
                } else {
                    // 设备断连/关闭：清掉 session 残留心率并立即刷新小部件（显示 "--"），
                    // 否则小部件永远停在最后一次读数上
                    session.setHr(-1)
                    com.altivane.app.widget.WidgetSnapshot.save(this@RideApp, session.state.value)
                    com.altivane.app.widget.WidgetPush.pushAll(this@RideApp)
                }
            }
        }
        appScope.launch { ble.cad.collect { session.setCad(it) } }
        sens = SensorEngine(this)
        sens.start(
            { deg ->
                session.setHeading(deg, true)
                // 罗盘小部件：0.5° 变化闸 + 1s 时间闸（CompassWidget.push 是直推无节流，
                // SENSOR_DELAY_UI 回调 ~15-60ms，不加时间闸会高频渲染 bitmap 反而耗电）
                val now = System.currentTimeMillis()
                if (kotlin.math.abs(deg - lastWidgetHeading) >= 0.5f && now - lastWidgetPushT >= 1000L) {
                    lastWidgetHeading = deg
                    lastWidgetPushT = now
                    com.altivane.app.widget.CompassWidget.push(this@RideApp)
                }
            },
            { },
            { hpa -> session.setPressure(hpa) },
            { acc -> session.setHeadingAcc(acc) },
            { tilt, dir -> session.setTilt(tilt, dir) }
        )
        // 测试机无气压计：置 hasBaro=false，气压卡/详情走空态而非永远 "--"
        if ((getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager)
                .getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE) == null) {
            session.setHasBaro(false)
            com.altivane.app.domain.AppLog.i("App", "no barometer on device")
        }
        // 蓝牙自动重连：启动时把记住的设备全部挂上（autoConnect 模式，
        // 系统后台等设备可连时自动完成——手环广播重启/超时断线后无需手动操作）。
        // crash/进程被杀后手环恢复广播需要几秒，追加 6s/15s 两次重试兜底
        appScope.launch { ble.reconnectSaved() }
        appScope.launch { delay(6000); ble.reconnectSaved() }
        appScope.launch { delay(15000); ble.reconnectSaved() }
        // 冷启动恢复"上次运动"快照（此前纯内存，进程被杀即丢）：
        // 从 Room 取两运动类型各自最近一次有效记录 + 最近 500 点画轨迹卡
        appScope.launch(Dispatchers.IO) {
            for (sport in com.altivane.app.domain.SportType.entries) {
                try {
                    val r = db.dao().lastRideOfSport(sport.name) ?: continue
                    val raw = db.dao().points(r.id)
                    val pts = raw.takeLast(500).map {
                        com.altivane.app.domain.RidePoint(it.t, it.lat, it.lon, it.ele, it.speed, it.hr, it.cad)
                    }
                    val last = raw.lastOrNull()
                    val snap = com.altivane.app.domain.LiveState(
                        phase = com.altivane.app.domain.Phase.IDLE, sport = sport, startTime = r.startTime,
                        movingMs = r.movingS * 1000L, distanceM = r.distanceM,
                        speedKmh = (last?.speed ?: 0f) * 3.6, maxKmh = r.maxKmh, avgKmh = r.avgKmh,
                        paceMinPerKm = if (r.avgKmh > 0.5) 60.0 / r.avgKmh else 0.0,
                        altM = last?.ele ?: 0.0, maxAltM = r.maxAltM,
                        elevGainM = r.elevGainM, kcal = r.kcal,
                        pointCount = r.pointCount, waypointCount = r.waypointCount,
                        hasBaro = session.state.value.hasBaro,
                        rideId = r.id
                    )
                    session.restoreSnapshot(sport, snap, pts)
                } catch (e: Exception) {
                    com.altivane.app.domain.AppLog.e("App", "restore snapshot $sport", e)
                }
            }
        }
    }
}
