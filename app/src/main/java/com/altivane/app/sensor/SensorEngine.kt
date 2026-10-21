package com.altivane.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 罗盘（旋转矢量，标准 getOrientation 语义）+ 气压计海拔（EMA 平滑）+ 水平仪（加速度计）。
 * v0.8.41：航向 = getOrientation(原始矩阵).azimuth（手机头上边的水平投影方位，纯倾斜不变）；
 * 倾角改加速度计直读（幅值 + 屏幕方向角），驱动指南针外圈"水平仪弧"——
 * 手机平放（<1°）不显示，倾斜时弧出现在低侧方向（东北/西北/东南/西南连续覆盖）。
 */
class SensorEngine(ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var onHeading: ((Float) -> Unit)? = null
    private var onBaroAlt: ((Double) -> Unit)? = null
    private var onPressure: ((Float) -> Unit)? = null
    private var onAcc: ((Int) -> Unit)? = null
    private var onTilt: ((Float, Float) -> Unit)? = null

    private val rot = FloatArray(9)
    private val orient = FloatArray(3)
    private var baroEma: Float? = null

    // 航向环形低通：抑制罗盘抖动（359°↔1° 跨界按最短角差处理）
    private var headingFiltered: Float? = null

    // 重力向量低通（水平仪）：静止比力 ≈ -g，EMA 抑制手抖
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var gInit = false

    fun start(
        hCb: (Float) -> Unit,
        aCb: (Double) -> Unit,
        pCb: (Float) -> Unit = {},
        accCb: (Int) -> Unit = {},
        tiltCb: (Float, Float) -> Unit = { _, _ -> }
    ) {
        onHeading = hCb
        onBaroAlt = aCb
        onPressure = pCb
        onAcc = accCb
        onTilt = tiltCb
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                // v0.8.41 标准指南针：getOrientation(原始矩阵).azimuth = 手机头上边的
                // 水平投影罗盘方位——纯倾斜（左右倾绕 Y 自转、头朝上/下在垂直面内抬起）
                // 投影方向不变，读数不动；只有真实水平转动才变。（此前 remap(AXIS_X,AXIS_Z)
                // 把参考轴换成屏幕法线，任何改变法线与重力夹角的倾斜都会让读数跟转）
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orient)
                var deg = Math.toDegrees(orient[0].toDouble()).toFloat()
                if (deg < 0) deg += 360f
                // 环形低通：filtered += 最短角差 × α（359°↔1° 跨界按最短角差处理，罗盘降噪标配）
                val f = headingFiltered
                if (f == null) {
                    headingFiltered = deg
                } else {
                    var d = deg - f
                    while (d > 180f) d -= 360f
                    while (d < -180f) d += 360f
                    deg = (f + d * 0.15f + 360f) % 360f
                    headingFiltered = deg
                }
                onHeading?.invoke(deg)
            }
            Sensor.TYPE_PRESSURE -> {
                // 气压直接读原始值（显示实时读数）；海拔仍用 EMA 平滑防跳变
                val p = e.values[0]
                baroEma = if (baroEma == null) p else baroEma!! * 0.92f + p * 0.08f
                onPressure?.invoke(p)
                onBaroAlt?.invoke(
                    SensorManager.getAltitude(
                        SensorManager.PRESSURE_STANDARD_ATMOSPHERE, baroEma!!
                    ).toDouble()
                )
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // 重力 EMA（α=0.25：水平仪响应跟手又不过敏）
                val k = 0.25f
                if (!gInit) {
                    gx = e.values[0]; gy = e.values[1]; gz = e.values[2]; gInit = true
                } else {
                    gx = gx * (1 - k) + e.values[0] * k
                    gy = gy * (1 - k) + e.values[1] * k
                    gz = gz * (1 - k) + e.values[2] * k
                }
                val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.1f)
                // 倾角 = 设备平面与水平面夹角（0-90°，|gz| 消除屏幕朝上/朝下差异）
                val tilt = Math.toDegrees(
                    kotlin.math.acos((kotlin.math.abs(gz) / norm).coerceIn(0f, 1f).toDouble())
                ).toFloat()
                // 低侧屏幕方向角（0°=屏幕上方，顺时针）：静止比力指向高侧，
                // 低侧设备向量 = (-ax,-ay) → 屏幕 y 翻转 → atan2(-ax, -ay)
                var dir = Math.toDegrees(atan2((-gx).toDouble(), (-gy).toDouble())).toFloat()
                if (dir < 0) dir += 360f
                onTilt?.invoke(tilt, dir)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 旋转矢量 accuracy = 磁力计校准状态（3高/2中/1低/0不可靠），透传备用
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) onAcc?.invoke(accuracy)
    }

    fun stop() = sm.unregisterListener(this)
}
