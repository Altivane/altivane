package com.altivane.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AColor
import android.os.Bundle
import android.widget.RemoteViews
import com.altivane.app.MainActivity
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.service.SosTorchService
import com.altivane.app.ui.TrackGeom
import com.altivane.app.ui.TrackPt
import com.altivane.app.ui.headingName
import kotlin.math.cos
import kotlin.math.sin

/**
 * 指南针桌面卡（固定 2x2）：罗盘盘面（bitmap 绘制）+ 底部航向数字。
 * 航向取 App 进程内实时罗盘（RideApp 常驻传感器周期推送），点击打开 App。
 */
class CompassWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, render(ctx, mgr, id))
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        mgr.updateAppWidget(id, render(ctx, mgr, id))
    }

    companion object {
        fun heading(ctx: Context): Float =
            (ctx.applicationContext as? RideApp)?.session?.state?.value?.headingDeg ?: 0f

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val t = widgetTheme(ctx)
            val deg = heading(ctx)
            val px = 360
            val bmp = compassBitmap(deg, t.dark, px)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_compass_big).apply {
                setInt(R.id.wcRoot, "setBackgroundResource", t.bgRes)
                setImageViewBitmap(R.id.wcDial, bmp)
                setTextViewText(R.id.wcValue, "%.0f° %s".format(deg, headingName(deg)))
                setTextColor(R.id.wcValue, accentColor(t, 0xFF2DD4BF.toInt(), 0xFF0F766E.toInt()))
            }
            rv.setOnClickPendingIntent(
                R.id.wcRoot,
                PendingIntent.getActivity(
                    ctx, 9902,
                    Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return rv
        }

        fun push(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, CompassWidget::class.java))) {
                mgr.updateAppWidget(id, render(ctx, mgr, id))
            }
        }

        /** 罗盘盘面 bitmap（量子环版）：外圈青色扫描弧 + 细密刻度环 + 主刻度环
         *  + 直立方位字母（N 红）+ 红色 N 标随盘转 + 顶部固定索引线。中央留白（数字在下方 TextView）。 */
        fun compassBitmap(heading: Float, dark: Boolean, sizePx: Int): Bitmap {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = sizePx / 2f
            val cy = sizePx / 2f
            val r = sizePx / 2f - sizePx * 0.035f
            val fg = if (dark) AColor.WHITE else AColor.BLACK
            val dim = if (dark) 0x8CFFFFFF.toInt() else 0x8C000000.toInt()
            val cyan = 0xFF22D3EE.toInt()
            val red = 0xFFFF5252.toInt()

            // 外圈青色扫描弧（装饰段）
            val ext = sizePx * 0.02f
            val arcP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = cyan
                strokeWidth = sizePx * 0.010f; strokeCap = Paint.Cap.ROUND
            }
            c.drawArc(cx - r - ext, cy - r - ext, cx + r + ext, cy + r + ext, -60f, 70f, false, arcP)

            // 刻度（随盘转）：72 细密 + 12 主刻度
            val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sizePx * 0.012f
                color = fg; strokeCap = Paint.Cap.ROUND
            }
            val tickMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sizePx * 0.005f
                color = dim; strokeCap = Paint.Cap.ROUND
            }
            c.save()
            c.rotate(-heading, cx, cy)
            for (i in 0 until 72) {
                val a = Math.toRadians((i * 5f).toDouble())
                c.drawLine(
                    (cx + sin(a) * r).toFloat(), (cy - cos(a) * r).toFloat(),
                    (cx + sin(a) * (r - r * 0.05f)).toFloat(), (cy - cos(a) * (r - r * 0.05f)).toFloat(),
                    tickMinor
                )
            }
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30f).toDouble())
                c.drawLine(
                    (cx + sin(a) * r * 0.86f).toFloat(), (cy - cos(a) * r * 0.86f).toFloat(),
                    (cx + sin(a) * r * 0.73f).toFloat(), (cy - cos(a) * r * 0.73f).toFloat(),
                    tickMajor
                )
            }
            // 红色 N 标（尖朝外，随盘转）
            val triR = r * 0.95f
            val tw = sizePx * 0.022f
            val th = sizePx * 0.042f
            val redN = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
            val north = android.graphics.Path().apply {
                moveTo(cx, cy - triR)
                lineTo(cx - tw, cy - triR + th)
                lineTo(cx + tw, cy - triR + th)
                close()
            }
            c.drawPath(north, redN)
            c.restore()

            // 顶部固定索引线（青）
            val idxP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = cyan
                strokeWidth = sizePx * 0.012f; strokeCap = Paint.Cap.ROUND
            }
            c.drawLine(cx, cy - r + sizePx * 0.005f, cx, cy - r + sizePx * 0.038f, idxP)

            // 方位字母（直立，位置随盘转）：N 红，其余前景
            val nPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = red
                textSize = sizePx * 0.105f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val ewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fg
                textSize = sizePx * 0.082f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            listOf("N", "E", "S", "W").forEachIndexed { idx, s ->
                val a = Math.toRadians((idx * 90f - heading).toDouble())
                val px2 = (cx + sin(a) * r * 0.56f).toFloat()
                val py2 = (cy - cos(a) * r * 0.56f).toFloat()
                val p = if (idx == 0) nPaint else ewPaint
                c.drawText(s, px2, py2 + p.textSize / 3f, p)
            }
            return bmp
        }
    }
}

/**
 * 轨迹桌面卡（2x2，可拉伸）：上次/本次运动全程平滑轨迹（bitmap 绘制），
 * 黑白主题跟随系统，起点绿点、终点粉环，点击打开 App。
 */
class TrackWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, render(ctx))
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        mgr.updateAppWidget(id, render(ctx))
    }

    companion object {
        fun render(ctx: Context): RemoteViews {
            val t = widgetTheme(ctx)
            val pts = WidgetSnapshot.loadTrack(ctx)
            val bmp = trackBitmap(pts, t.dark, 420)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_track)
            rv.setInt(R.id.wtRoot, "setBackgroundResource", t.bgRes)
            rv.setTextColor(R.id.wtTitle, t.labelColor)
            if (bmp != null) {
                rv.setViewVisibility(R.id.wtTrack, android.view.View.VISIBLE)
                rv.setViewVisibility(R.id.wtEmpty, android.view.View.GONE)
                rv.setImageViewBitmap(R.id.wtTrack, bmp)
            } else {
                rv.setViewVisibility(R.id.wtTrack, android.view.View.GONE)
                rv.setViewVisibility(R.id.wtEmpty, android.view.View.VISIBLE)
                rv.setTextViewText(R.id.wtEmpty, "开始运动后显示全程轨迹")
                rv.setTextColor(R.id.wtEmpty, t.labelColor)
            }
            rv.setOnClickPendingIntent(
                R.id.wtRoot,
                PendingIntent.getActivity(
                    ctx, 9904,
                    Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return rv
        }

        fun push(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, TrackWidget::class.java))) {
                mgr.updateAppWidget(id, render(ctx))
            }
        }

        /** 全程轨迹 bitmap：等比 fit + 起点绿点 / 终点粉环（黑白主题内保留品牌双色） */
        fun trackBitmap(pts: List<TrackPt>, dark: Boolean, sizePx: Int): Bitmap? {
            if (pts.size < 2) return null
            val fit = TrackGeom.fit(pts) ?: return null
            val bw = ((fit.lonMax - fit.lonMin) * fit.kx).coerceAtLeast(1e-6)
            val bh = (fit.latMax - fit.latMin).coerceAtLeast(1e-6)
            val pad = sizePx * 0.10f
            val availW = sizePx - pad * 2
            val availH = sizePx - pad * 2
            val scale = minOf(availW / bw.toFloat(), availH / bh.toFloat())
            val offX = pad + (availW - bw.toFloat() * scale) / 2f
            val offY = pad + (availH - bh.toFloat() * scale) / 2f
            fun map(p: TrackPt): FloatArray = floatArrayOf(
                offX + ((p.lon - fit.lonMin) * fit.kx * scale).toFloat(),
                offY + ((fit.latMax - p.lat) * scale).toFloat()
            )
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val fg = if (dark) AColor.WHITE else AColor.BLACK
            val dim = if (dark) 0x40FFFFFF else 0x28000000
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = sizePx * 0.022f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = fg
            }
            // 底衬轨迹（微弱同形线，增强可读性）
            val under = Paint(line).apply { strokeWidth = sizePx * 0.045f; color = dim }
            val path = android.graphics.Path()
            pts.forEachIndexed { i, p ->
                val o = map(p)
                if (i == 0) path.moveTo(o[0], o[1]) else path.lineTo(o[0], o[1])
            }
            c.drawPath(path, under)
            c.drawPath(path, line)
            // 起点（绿）/ 终点（粉环）
            val s0 = map(pts.first())
            val sN = map(pts.last())
            val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA3E635.toInt() }
            c.drawCircle(s0[0], s0[1], sizePx * 0.035f, green)
            c.drawCircle(s0[0], s0[1], sizePx * 0.013f, Paint().apply { color = if (dark) AColor.BLACK else AColor.WHITE })
            val pink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sizePx * 0.014f; color = 0xFFFF6B81.toInt()
            }
            c.drawCircle(sN[0], sN[1], sizePx * 0.030f, pink)
            return bmp
        }
    }
}

/**
 * SOS 求救桌面卡（2x1）：点击 = 启动/停止 SOS 摩尔斯闪光服务。
 */
class SosWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, render(ctx))
    }

    companion object {
        fun render(ctx: Context): RemoteViews {
            val t = widgetTheme(ctx)
            val on = SosTorchService.running
            val rv = RemoteViews(ctx.packageName, R.layout.widget_sos)
            rv.setInt(R.id.wsRoot, "setBackgroundResource", t.bgRes)
            rv.setTextViewText(R.id.wsTitle, if (on) "SOS 闪烁中" else "SOS 手电筒")
            rv.setTextColor(
                R.id.wsTitle,
                if (on) accentColor(t, 0xFFFF5252.toInt(), 0xFFB91C1C.toInt())
                else accentColor(t, 0xFFFF5252.toInt(), 0xFFB91C1C.toInt())
            )
            rv.setTextViewText(R.id.wsSub, if (on) "点击停止闪光" else "点击开手电筒 · 三短三长三短")
            rv.setTextColor(R.id.wsSub, t.labelColor)
            rv.setOnClickPendingIntent(
                R.id.wsRoot,
                PendingIntent.getForegroundService(
                    ctx, 9903,
                    Intent(ctx, SosTorchService::class.java)
                        .setAction(SosTorchService.ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return rv
        }

        fun push(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, SosWidget::class.java))) {
                mgr.updateAppWidget(id, render(ctx))
            }
        }
    }
}
