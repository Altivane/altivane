package com.altivane.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import com.altivane.app.MainActivity
import com.altivane.app.R
import com.altivane.app.domain.Coach
import com.altivane.app.domain.LiveState

/**
 * 桌面卡片黑白主题：跟随系统深浅色（深=黑底亮字，浅=白底黑字）。
 * 数字保留品牌彩色（浅色模式自动换同族深色变体保证对比度）。
 */
data class WTheme(val dark: Boolean, val bgRes: Int, val labelColor: Int)

fun widgetTheme(ctx: Context): WTheme {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    return if (dark) WTheme(true, R.drawable.widget_bg, 0xAAFFFFFF.toInt())
    else WTheme(false, R.drawable.widget_bg_light, 0x99000000.toInt())
}

/** 数字颜色：深色模式用亮色，浅色模式用同族深色变体 */
fun accentColor(t: WTheme, bright: Int, deep: Int): Int = if (t.dark) bright else deep

/** 最近一次推送的运动快照（进程被杀后 widget 仍有数据显示） */
object WidgetSnapshot {
    private const val PREF = "altivane_widget"

    data class Snap(
        val speed: String = "--",
        val dist: String = "--",
        val dur: String = "--",
        val hr: String = "--",
        val cals: String = "--"
    )

    fun save(ctx: Context, s: LiveState) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("speed", "%.1f".format(s.speedKmh))
            .putString("dist", "%.2f".format(s.distanceM / 1000.0))
            .putString("dur", Coach.fmtDur(s.movingMs))
            .putString("hr", if (s.hr > 0) s.hr.toString() else "--")
            .putString("cals", s.kcal.toInt().toString())
            .apply()
    }

    fun load(ctx: Context): Snap {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Snap(
            p.getString("speed", "--") ?: "--",
            p.getString("dist", "--") ?: "--",
            p.getString("dur", "--") ?: "--",
            p.getString("hr", "--") ?: "--",
            p.getString("cals", "--") ?: "--"
        )
    }

    /** 轨迹快照：平滑管道压缩到 ≤160 点再落盘（15km 全程也能存） */
    fun saveTrack(ctx: Context, pts: List<com.altivane.app.ui.TrackPt>) {
        val keep = com.altivane.app.ui.TrackGeom.prepareIndices(pts, 160)
        val str = keep.joinToString(";") { i ->
            "%.5f,%.5f".format(pts[i].lat, pts[i].lon)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("track", str).apply()
    }

    fun loadTrack(ctx: Context): List<com.altivane.app.ui.TrackPt> {
        val str = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("track", null) ?: return emptyList()
        return str.split(";").mapNotNull {
            val ab = it.split(",")
            if (ab.size == 2) try {
                com.altivane.app.ui.TrackPt(ab[0].toDouble(), ab[1].toDouble())
            } catch (_: Exception) {
                null
            } else null
        }
    }
}

/** 单指标小卡（2x1 起步，可拉伸）：黑白主题 */
abstract class TileWidget : AppWidgetProvider() {

    abstract val label: String
    abstract val bright: Int
    abstract val deep: Int
    abstract fun value(s: LiveState): String

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val t = widgetTheme(ctx)
        for (id in ids) mgr.updateAppWidget(id, build(ctx, label, "--", accentColor(t, bright, deep), t))
    }

    companion object {
        fun build(
            ctx: Context, label: String, value: String,
            valueColor: Int, t: WTheme = widgetTheme(ctx)
        ): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_tile)
            v.setInt(R.id.wRoot, "setBackgroundResource", t.bgRes)
            v.setTextViewText(R.id.wValue, value)
            v.setTextColor(R.id.wValue, valueColor)
            v.setTextViewText(R.id.wLabel, label)
            v.setTextColor(R.id.wLabel, t.labelColor)
            v.setOnClickPendingIntent(
                R.id.wRoot,
                PendingIntent.getActivity(
                    ctx, label.hashCode(),
                    Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return v
        }
    }
}

class SpeedTile : TileWidget() {
    override val label = "时速 km/h"
    override val bright = 0xFF40E0FF.toInt()
    override val deep = 0xFF0E7490.toInt()
    override fun value(s: LiveState) = "%.1f".format(s.speedKmh)
}

class HrTile : TileWidget() {
    override val label = "心率 bpm"
    override val bright = 0xFFFF6B8A.toInt()
    override val deep = 0xFFBE123C.toInt()
    override fun value(s: LiveState) = if (s.hr > 0) s.hr.toString() else "--"
}

class DistanceTile : TileWidget() {
    override val label = "里程 km"
    override val bright = 0xFF5B9DFF.toInt()
    override val deep = 0xFF0369A1.toInt()
    override fun value(s: LiveState) = "%.2f".format(s.distanceM / 1000.0)
}

class CalsTile : TileWidget() {
    override val label = "消耗 kcal"
    override val bright = 0xFFFF9F45.toInt()
    override val deep = 0xFFB45309.toInt()
    override fun value(s: LiveState) = s.kcal.toInt().toString()
}

/**
 * 运动概览卡：大小自适应三档（内容随尺寸变化）：
 * - <180dp（≈1x1）：单指标（当前时速）
 * - 180~330dp（≈2x2）：双排四指标（时速/里程 // 时长/心率）
 * - ≥330dp（≈4x2）：单排四指标
 * 黑白主题跟随系统；点击打开 App。
 */
class MainSummaryWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, render(ctx, mgr, id))
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle
    ) {
        mgr.updateAppWidget(id, render(ctx, mgr, id))
    }

    companion object {
        fun render(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val t = widgetTheme(ctx)
            val snap = WidgetSnapshot.load(ctx)
            val minW = try {
                mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            } catch (_: Exception) { 250 }
            val v = when {
                minW < 180 -> {   // 1x1：单指标
                    val rv = RemoteViews(ctx.packageName, R.layout.widget_tile)
                    rv.setInt(R.id.wRoot, "setBackgroundResource", t.bgRes)
                    rv.setTextViewText(R.id.wValue, snap.speed)
                    rv.setTextColor(R.id.wValue, accentColor(t, 0xFF40E0FF.toInt(), 0xFF0E7490.toInt()))
                    rv.setTextViewText(R.id.wLabel, "时速 km/h")
                    rv.setTextColor(R.id.wLabel, t.labelColor)
                    rv
                }
                minW < 330 -> {   // 2x2：双排四指标
                    val rv = RemoteViews(ctx.packageName, R.layout.widget_grid)
                    rv.setInt(R.id.wRoot, "setBackgroundResource", t.bgRes)
                    rv.setTextViewText(R.id.wmTitle, "Altivane · 运动")
                    rv.setTextColor(R.id.wmTitle, t.labelColor)
                    rv.setTextViewText(R.id.wmV1, snap.speed)
                    rv.setTextColor(R.id.wmV1, accentColor(t, 0xFF40E0FF.toInt(), 0xFF0E7490.toInt()))
                    rv.setTextViewText(R.id.wmV2, snap.dist)
                    rv.setTextColor(R.id.wmV2, accentColor(t, 0xFF5B9DFF.toInt(), 0xFF0369A1.toInt()))
                    rv.setTextViewText(R.id.wmV3, snap.dur)
                    rv.setTextColor(R.id.wmV3, accentColor(t, 0xFFA3E635.toInt(), 0xFF4D7C0F.toInt()))
                    rv.setTextViewText(R.id.wmV4, snap.hr)
                    rv.setTextColor(R.id.wmV4, accentColor(t, 0xFFFF6B8A.toInt(), 0xFFBE123C.toInt()))
                    listOf(R.id.wmL1, R.id.wmL2, R.id.wmL3, R.id.wmL4).forEach {
                        rv.setTextColor(it, t.labelColor)
                    }
                    rv
                }
                else -> {         // 4x2：单排四指标
                    val rv = RemoteViews(ctx.packageName, R.layout.widget_main)
                    rv.setInt(R.id.wRoot, "setBackgroundResource", t.bgRes)
                    rv.setTextViewText(R.id.wmTitle, "Altivane · 运动")
                    rv.setTextColor(R.id.wmTitle, t.labelColor)
                    rv.setTextViewText(R.id.wmV1, snap.speed)
                    rv.setTextColor(R.id.wmV1, accentColor(t, 0xFF40E0FF.toInt(), 0xFF0E7490.toInt()))
                    rv.setTextViewText(R.id.wmV2, snap.dist)
                    rv.setTextColor(R.id.wmV2, accentColor(t, 0xFF5B9DFF.toInt(), 0xFF0369A1.toInt()))
                    rv.setTextViewText(R.id.wmV3, snap.dur)
                    rv.setTextColor(R.id.wmV3, accentColor(t, 0xFFA3E635.toInt(), 0xFF4D7C0F.toInt()))
                    rv.setTextViewText(R.id.wmV4, snap.hr)
                    rv.setTextColor(R.id.wmV4, accentColor(t, 0xFFFF6B8A.toInt(), 0xFFBE123C.toInt()))
                    listOf(R.id.wmL1, R.id.wmL2, R.id.wmL3, R.id.wmL4).forEach {
                        rv.setTextColor(it, t.labelColor)
                    }
                    rv
                }
            }
            v.setOnClickPendingIntent(
                R.id.wRoot,
                PendingIntent.getActivity(
                    ctx, 9901,
                    Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return v
        }
    }
}

/** 服务 → 桌面卡片 推送（4s 节流）：tile ×4 + 概览三档 + 指南针 */
object WidgetPush {
    private var lastPush = 0L

    fun push(ctx: Context, s: LiveState) {
        val now = System.currentTimeMillis()
        if (now - lastPush < 4000) return
        lastPush = now
        WidgetSnapshot.save(ctx, s)
        pushAll(ctx)
    }

    /**
     * 全量推送（绕过节流）：回前台 / 系统深浅切换时调用。
     * 数据用落盘快照，进程刚起来也能刷新。
     */
    fun pushAll(ctx: Context) {
        val snap = WidgetSnapshot.load(ctx)
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val t = widgetTheme(ctx)
        val tiles = listOf(
            Triple(SpeedTile::class.java, "时速 km/h", snap.speed) to
                    accentColor(t, 0xFF40E0FF.toInt(), 0xFF0E7490.toInt()),
            Triple(HrTile::class.java, "心率 bpm", snap.hr) to
                    accentColor(t, 0xFFFF6B8A.toInt(), 0xFFBE123C.toInt()),
            Triple(DistanceTile::class.java, "里程 km", snap.dist) to
                    accentColor(t, 0xFF5B9DFF.toInt(), 0xFF0369A1.toInt()),
            Triple(CalsTile::class.java, "消耗 kcal", snap.cals) to
                    accentColor(t, 0xFFFF9F45.toInt(), 0xFFB45309.toInt())
        )
        for ((data, color) in tiles) {
            val (cls, label, value) = data
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            for (id in ids) {
                mgr.updateAppWidget(id, TileWidget.build(ctx, label, value, color, t))
            }
        }
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, MainSummaryWidget::class.java))) {
            mgr.updateAppWidget(id, MainSummaryWidget.render(ctx, mgr, id))
        }
        for (id in mgr.getAppWidgetIds(ComponentName(ctx, CompassWidget::class.java))) {
            mgr.updateAppWidget(id, CompassWidget.render(ctx, mgr, id))
        }
        TrackWidget.push(ctx)
        SosWidget.push(ctx)
    }
}
