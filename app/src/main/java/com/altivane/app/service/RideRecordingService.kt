package com.altivane.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.altivane.app.MainActivity
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.data.PointEntity
import com.altivane.app.data.RideEntity
import com.altivane.app.data.WaypointEntity
import com.altivane.app.domain.Coach
import com.altivane.app.domain.LiveState
import com.altivane.app.domain.Phase
import com.altivane.app.domain.RidePoint
import com.altivane.app.sensor.LocationEngine
import com.altivane.app.sensor.SensorEngine
import com.altivane.app.tts.CoachTts
import com.altivane.app.widget.WidgetPush
import com.altivane.app.widget.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台录制服务：GPS 1Hz + 罗盘/气压 + 会话累计 + 语音播报 + 桌面卡片推送 + 锁屏富通知。
 */
class RideRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.altivane.app.action.START"
        const val ACTION_PAUSE = "com.altivane.app.action.PAUSE"
        const val ACTION_RESUME = "com.altivane.app.action.RESUME"
        const val ACTION_STOP = "com.altivane.app.action.STOP"
        const val CHANNEL_ID = "ride_rec"
        const val NOTIF_ID = 1
    }

    private lateinit var app: RideApp
    private var loc: LocationEngine? = null
    private var sens: SensorEngine? = null
    private var tts: CoachTts? = null
    private var rideId = 0L
    private var baroRaw: Double? = null
    private var baroOffset: Double? = null
    private var lastFlushIdx = 0
    /** 上一个被采纳的定位点：静止漂移过滤基准 */
    private var lastLoc: android.location.Location? = null
    /** 连续低精度被拒计数（防树荫/峡谷下轨迹断流） */
    private var lowAccStreak = 0
    private var lastWpFlushIdx = 0
    private var flushJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifT = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopping = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as RideApp
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        tts = CoachTts(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRide()
            ACTION_PAUSE -> { app.session.pause(); refreshNotif(true) }
            ACTION_RESUME -> { app.session.resume(); refreshNotif(true) }
            ACTION_STOP -> stopRide()
        }
        return START_NOT_STICKY
    }

    private fun startRide() {
        val n = buildNotification(app.session.state.value)
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            // ROM 拒绝前台化（类型/权限校验）时不崩：记录继续，仅无通知
            com.altivane.app.domain.AppLog.e("Ride", "startForeground failed", e)
        }
        if (app.session.state.value.phase != Phase.IDLE) return

        stopping.set(false)
        baroOffset = null
        lastLoc = null
        lowAccStreak = 0
        lastFlushIdx = 0
        lastWpFlushIdx = 0
        val sport = app.session.state.value.sport
        scope.launch {
            val id = app.db.dao().insertRide(
                RideEntity(startTime = System.currentTimeMillis(), sportType = sport.name)
            )
            rideId = id
            app.session.startNew(id, System.currentTimeMillis())
            flushJob = scope.launch {
                while (isActive) { delay(30_000); flush() }
            }
            // 语音事件收集
            launch {
                app.session.kmEvents.collect {
                    val s = app.session.state.value
                    tts?.say(Coach.kmMessage(it.index, it.splitMs, s.sport, s.kcal))
                }
            }
            launch {
                app.session.coachEvents.collect { tts?.say(it) }
            }
        }
        tts?.say(Coach.startMsg(sport))

        // 运动开始：把记住的 BLE 设备挂上（若已连接则跳过；autoConnect 兜底）
        app.ble.reconnectSaved()

        val le = LocationEngine(this)
        if (le.start { onLoc(it) }) {
            loc = le
        }
        val se = SensorEngine(this)
        se.start(
            { deg -> app.session.setHeading(deg, true) },
            { alt -> onBaro(alt) },
            { },
            { acc -> app.session.setHeadingAcc(acc) },
            { tilt, dir -> app.session.setTilt(tilt, dir) }
        )
        sens = se

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "retii:ride")
            .apply { acquire(8 * 60 * 60 * 1000L) }
    }

    private fun onLoc(l: android.location.Location) {
        val s = app.session.state.value
        if (s.phase != Phase.RECORDING) return

        // 信号条：任何回调都更新显示（被过滤的点也能反映真实信号质量）
        app.session.setGpsAcc(if (l.hasAccuracy()) l.accuracy else -1f)

        // ---- GPS 轨迹过滤（v0.8.8）：3m 高置信闸 + 误差融合收缩 + 静止/跳变四道闸 ----
        val last = lastLoc
        val acc = if (l.hasAccuracy()) l.accuracy else 20f
        if (last != null) {
            val dist = last.distanceTo(l)                       // 与上一点的水平距离 m
            val dt = ((l.time - last.time).coerceAtLeast(0L)) / 1000f
            val repSpd = if (l.hasSpeed()) l.speed else -1f     // GPS 多普勒速度（静止时≈0，最可靠）
            // ① 精度闸：≤3m 高置信直接采；3~15m 融合收缩后采；>15m 拒收，
            //    但连续 10 个低精度点后放行一个（树荫/峡谷防轨迹断流）
            if (acc > 15f) {
                lowAccStreak++
                if (lowAccStreak < 10) return
                lowAccStreak = 0
            } else {
                lowAccStreak = 0
            }
            // ② 静止判定：GPS 报告速度极低且几乎没挪窝 → 漂移，不采点不画线
            if (repSpd in 0f..0.5f && dist < 10f) return
            // 无多普勒速度的机型：近距离快速更新视为抖动
            if (repSpd < 0f && dist < 6f && dt < 3f) return
            // ③ 隐含速度不合理（定位跳变）丢点：>100 km/h（骑行极速富余）
            if (dt > 0.5f && dist / dt > 28f) return
        } else if (acc > 25f) {
            return // 首点也要基本可信
        }
        lastLoc = l

        // 位置融合：精度越差，新点向已定位置收缩越多（低通滤波平滑轨迹）
        var lat = l.latitude
        var lon = l.longitude
        if (acc > 3f && last != null) {
            val w = (3.0 / acc).coerceIn(0.25, 0.85)
            lat = last.latitude + (l.latitude - last.latitude) * w
            lon = last.longitude + (l.longitude - last.longitude) * w
        }

        val ele = if (l.hasAltitude()) l.altitude else s.altM
        val t = if (l.time > 0) l.time else System.currentTimeMillis()
        app.session.addPoint(
            RidePoint(
                t = t, lat = lat, lon = lon, ele = ele,
                speed = if (l.hasSpeed()) l.speed else 0f,
                hr = s.hr, cad = s.cad
            )
        )
        if (baroOffset == null && baroRaw != null) baroOffset = ele - baroRaw!!
        refreshNotif(false)
    }

    private fun onBaro(alt: Double) {
        baroRaw = alt
        val s = app.session.state.value
        if (s.phase == Phase.IDLE) return
        app.session.setAltitude(alt + (baroOffset ?: 0.0))
    }

    private suspend fun flush() {
        val pts = app.session.points.value
        if (pts.size > lastFlushIdx) {
            val slice = pts.subList(lastFlushIdx, pts.size).map {
                PointEntity(rideId, it.t, it.lat, it.lon, it.ele, it.speed, it.hr, it.cad)
            }
            app.db.dao().insertPoints(slice)
            lastFlushIdx = pts.size
            // 轨迹小部件快照：平滑压缩后落盘（每 30s 增量）
            WidgetSnapshot.saveTrack(
                this, pts.map { com.altivane.app.ui.TrackPt(it.lat, it.lon) }
            )
        }
        val wps = app.session.waypoints.value
        if (wps.size > lastWpFlushIdx) {
            app.db.dao().insertWaypoints(
                wps.subList(lastWpFlushIdx, wps.size).map {
                    WaypointEntity(rideId, it.t, it.lat, it.lon, it.ele, it.kind, it.label, it.photo)
                }
            )
            lastWpFlushIdx = wps.size
        }
        saveSummary()
    }

    private suspend fun saveSummary() {
        val s = app.session.state.value
        if (s.rideId == 0L) return
        app.db.dao().ride(s.rideId)?.let { r ->
            r.distanceM = s.distanceM
            r.movingS = (s.movingMs / 1000).toInt()
            r.avgKmh = s.avgKmh
            r.maxKmh = s.maxKmh
            r.elevGainM = s.elevGainM
            r.maxAltM = s.maxAltM
            r.kcal = s.kcal
            r.pointCount = s.pointCount
            r.waypointCount = s.waypointCount
            app.db.dao().updateRide(r)
        }
    }

    private fun stopRide() {
        if (!stopping.compareAndSet(false, true)) return
        val s0 = app.session.state.value
        val summary = Coach.finishSummary(
            s0.sport, s0.distanceM, s0.movingMs, s0.elevGainM, s0.kcal
        )
        tts?.say(summary)
        scope.launch {
            flush()
            app.db.dao().ride(rideId)?.let { r ->
                r.endTime = System.currentTimeMillis()
                val s = app.session.state.value
                r.distanceM = s.distanceM
                r.movingS = (s.movingMs / 1000).toInt()
                r.avgKmh = s.avgKmh
                r.maxKmh = s.maxKmh
                r.elevGainM = s.elevGainM
                r.maxAltM = s.maxAltM
                r.kcal = s.kcal
                r.pointCount = s.pointCount
                r.waypointCount = s.waypointCount
                app.db.dao().updateRide(r)
            }
            withContext(Dispatchers.Main) {
                loc?.stop(); loc = null
                sens?.stop(); sens = null
                wakeLock?.takeIf { it.isHeld }?.release()
                val finishedId = rideId
                app.session.reset()
                app.session.markFinished(finishedId)
                ServiceCompat.stopForeground(this@RideRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun refreshNotif(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifT < 5000) return
        lastNotifT = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val s = app.session.state.value
        nm.notify(NOTIF_ID, buildNotification(s))
        WidgetPush.push(this, s)
    }

    /** 锁屏可见富通知：大视图显示 速度/心率/里程/消耗 */
    private fun buildNotification(s: LiveState): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RideRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when (s.phase) {
            Phase.RECORDING -> getString(R.string.notif_rec_fmt, getString(s.sport.labelRes))
            Phase.PAUSED -> getString(R.string.notif_paused_fmt, getString(s.sport.labelRes))
            Phase.IDLE -> "Altivane"
        }
        val text = "%.2f km · %.1f km/h".format(s.distanceM / 1000.0, s.speedKmh)
        val big = "速度 %.1f km/h    心率 %s 次/分\n里程 %.2f km    消耗 %d kcal    海拔 %.0f m".format(
            s.speedKmh,
            if (s.hr > 0) s.hr.toString() else "--",
            s.distanceM / 1000.0,
            s.kcal.toInt(),
            s.altM
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)                 // Promoted 资格：必须有 contentTitle
            .setContentText(text)                   // Promoted 卡展示 title+text+进度条
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setOngoing(true)                       // Promoted 资格
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopIntent)       // 非 colorized / 无 RemoteViews ✓
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            // Live Updates：进度条（5km 一档里程碑）+ 显式请求系统提升（锁屏大卡/状态栏 chip）
            val dist = s.distanceM
            val max = (kotlin.math.ceil(dist / 5000.0).toInt() * 5000).coerceAtLeast(5000)
            val style = NotificationCompat.ProgressStyle()
                .setProgress(dist.toInt().coerceIn(0, max))
                .addProgressSegment(
                    NotificationCompat.ProgressStyle.Segment(max)
                        .setColor(0xFF22D3EE.toInt())
                )
                .setProgressTrackerIcon(
                    androidx.core.graphics.drawable.IconCompat.createWithResource(
                        this, android.R.drawable.ic_menu_mylocation
                    )
                )
            b.setStyle(style).setRequestPromotedOngoing(true)
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(big))
        }
        return b.build()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
