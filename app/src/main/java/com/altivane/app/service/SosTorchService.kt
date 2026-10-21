package com.altivane.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import com.altivane.app.widget.SosWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SOS 求救闪光前台服务：摩尔斯 SOS（三短-三长-三短）循环闪光。
 * 桌面 SOS 小部件点击启动/停止；熄灭时保证灯灭。
 */
class SosTorchService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.altivane.app.action.SOS_TOGGLE"
        const val CHANNEL_ID = "sos_torch"
        const val NOTIF_ID = 2
        var running = false
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            // 已在闪烁 → 停止
            loopJob?.cancel()
            running = false
            torch(false)
            SosWidget.push(this)
            stopSelf()
        } else {
            startAsForeground()
            running = true
            SosWidget.push(this)
            loopJob = scope.launch { sosLoop() }
        }
        return START_NOT_STICKY
    }

    /** 摩尔斯 SOS 循环：S(···) O(---) S(···)，单位 ≈ 220ms */
    private suspend fun sosLoop() {
        val dot = 220L
        val dash = dot * 3L
        val gap = dot
        val letter = dot * 3L
        while (running) {
            repeat(3) { torch(true); delay(dot); torch(false); delay(gap) }
            delay(letter - gap)
            repeat(3) { torch(true); delay(dash); torch(false); delay(gap) }
            delay(letter - gap)
            repeat(3) { torch(true); delay(dot); torch(false); delay(gap) }
            delay(letter)
        }
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            cm.setTorchMode(id, on)
        } catch (_: Exception) {
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SOS 求救闪光", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS 求救闪光中")
            .setContentText("闪光灯正按三短三长三短闪烁 · 桌面小部件可停止")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        running = false
        torch(false) // 兜底：任何路径退出都保证灯灭
        SosWidget.push(this)
        super.onDestroy()
    }
}
