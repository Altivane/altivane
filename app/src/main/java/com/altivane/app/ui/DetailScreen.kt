package com.altivane.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.data.GpxExporter
import com.altivane.app.data.PointEntity
import com.altivane.app.data.RideEntity
import com.altivane.app.data.WaypointEntity
import com.altivane.app.domain.Coach
import com.altivane.app.domain.RidePoint
import com.altivane.app.domain.RideStats
import com.altivane.app.domain.SportType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 运动详情页（v0.8.35 方案A「运动报告」+ 心率分析）：
 * 距离大数字横幅（较上次对比徽章）+ 2×3 数据格 + 心率曲线/区间分布（有数据才显示）
 * + 海拔剖面（标尺版）+ 运动解读卡 + 打卡点 + GPX/分享。不再放轨迹地图（轨迹展示归轨迹详情页）。
 */
@Composable
fun DetailScreen(app: RideApp, id: Long, onBack: () -> Unit, onShare: () -> Unit) {
    var ride by remember { mutableStateOf<RideEntity?>(null) }
    var pts by remember { mutableStateOf<List<PointEntity>>(emptyList()) }
    var wps by remember { mutableStateOf<List<WaypointEntity>>(emptyList()) }
    var prev by remember { mutableStateOf<RideEntity?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var zoomPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val dark = isDark()

    LaunchedEffect(id) {
        ride = app.db.dao().ride(id)
        pts = app.db.dao().points(id)
        wps = app.db.dao().waypoints(id)
        // 较上次对比：同运动类型、早于本次的最近一条记录
        val r = ride
        if (r != null) {
            prev = app.db.dao().rides().first().firstOrNull {
                it.id != r.id && it.sportType == r.sportType && it.startTime < r.startTime
            }
        } else {
            prev = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column {
                Text(
                    stringResource(R.string.detail_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                ride?.let { r ->
                    Text(
                        stringResource(R.string.fmt_sport_date, stringResource(sportOf(r).labelRes), fmtDate(r.startTime)),
                        style = MaterialTheme.typography.labelMedium,
                        color = AvColors.onColor(AvColors.cyan)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { confirmDelete = true }) {
                Icon(
                    Icons.Filled.Delete, "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ride?.let { r ->
            val sport = sportOf(r)
            val km = r.distanceM / 1000.0
            val hrList = pts.mapNotNull { it.hr }.filter { it > 0 }.map { it.toDouble() }
            val rpts = remember(pts) {
                pts.map { RidePoint(it.t, it.lat, it.lon, it.ele, it.speed, it.hr, it.cad) }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ① 距离大数字横幅 + 较上次徽章
                GlassCard(accent = AvColors.cyan) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "%.2f".format(km),
                                fontSize = 52.sp, fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.fmt_km_banner, stringResource(sport.labelRes)),
                                style = MaterialTheme.typography.titleMedium,
                                color = AvColors.onColor(AvColors.cyan),
                                modifier = Modifier.padding(bottom = 10.dp)
                            )
                        }
                        prev?.let { p ->
                            val dKm = km - p.distanceM / 1000.0
                            val up = dKm >= 0
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            (if (up) AvColors.green else AvColors.orange).copy(alpha = 0.16f)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.vs_prev_km, if (up) "+" else "", dKm),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AvColors.onColor(if (up) AvColors.green else AvColors.orange)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.last_km_fmt, p.distanceM / 1000.0, fmtDate(p.startTime)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ② 数据格 2×3（核心 / 进阶；骑/徒按运动类型换指标）
                val statH = 88.dp
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(stringResource(R.string.card_duration), fmtCardDur(r.movingS * 1000L), "", AvColors.yellow, Modifier.weight(1f).height(statH))
                    if (sport == SportType.RIDE) {
                        StatCard(stringResource(R.string.stat_avg), "%.1f".format(r.avgKmh), "km/h", AvColors.green, Modifier.weight(1f).height(statH))
                    } else {
                        StatCard(
                            stringResource(R.string.card_pace),
                            if (r.avgKmh > 0.1) Coach.paceText(60.0 / r.avgKmh) else "--",
                            "min/km", AvColors.green, Modifier.weight(1f).height(statH)
                        )
                    }
                    StatCard(stringResource(R.string.card_cals), r.kcal.toInt().toString(), "kcal", AvColors.pink, Modifier.weight(1f).height(statH))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(stringResource(R.string.lbl_climb), "%.0f".format(r.elevGainM), "m", AvColors.orange, Modifier.weight(1f).height(statH))
                    if (sport == SportType.RIDE) {
                        // 最慢公里：全程逐公里配速中最慢的一段
                        StatCard(
                            stringResource(R.string.lbl_slowest_km),
                            remember(rpts) { RideStats.kmPaces(rpts).maxOrNull()?.let { Coach.paceText(it) } ?: "--" },
                            "min/km", AvColors.violet, Modifier.weight(1f).height(statH)
                        )
                    } else {
                        // 步数：里程 ÷ 0.72 m（成人通用步长估算，仅徒步）
                        StatCard(
                            stringResource(R.string.lbl_steps),
                            if (km > 0.05) "%d".format((km * 1000 / 0.72).toInt()) else "--",
                            "", AvColors.violet, Modifier.weight(1f).height(statH)
                        )
                    }
                    StatCard(stringResource(R.string.stat_peak), "%.1f".format(r.maxKmh), "km/h", AvColors.blue, Modifier.weight(1f).height(statH))
                }

                // ③ 心率曲线（有心率数据才显示）：逐点 BPM + 标尺
                if (hrList.size >= 2) {
                    GlassCard(Modifier.height(130.dp), accent = AvColors.pink) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.card_hr),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.hr_chart_sub),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    stringResource(R.string.hr_avg_fmt, hrList.average().toInt()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AvColors.onColor(AvColors.pink)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.hr_peak_fmt, hrList.max().toInt()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AvColors.onColor(AvColors.red)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Sparkline(
                                hrList,
                                AvColors.pink,
                                Modifier.fillMaxSize(),
                                showScale = true
                            )
                        }
                    }
                    // ④ 心率区间分布：Z1-Z5 时长占比（复用轨迹详情同款组件）
                    ZoneBarsCard(rpts)
                }

                // ⑤ 海拔剖面：标尺（最高/最低参考线+数值）+ 颜色说明
                if (pts.size > 2) {
                    val eleList = pts.map { it.ele }
                    GlassCard(Modifier.height(130.dp), accent = AvColors.green) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.profile_detail),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.profile_sub),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    stringResource(R.string.ele_max_fmt, eleList.max()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AvColors.onColor(AvColors.green)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.ele_min_fmt, eleList.min()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AvColors.onColor(AvColors.cyan)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Sparkline(
                                eleList,
                                AvColors.green,
                                Modifier.fillMaxSize(),
                                showScale = true
                            )
                        }
                    }
                }

                // ⑥ 运动解读卡：自动生成的文字报告（对比 / 配速 / 爬升 / 心率强度）
                GlassCard(accent = AvColors.cyan.copy(alpha = 0.5f)) {
                    Column {
                        CardTitleRow(stringResource(R.string.report_title), AvColors.cyan)
                        Spacer(Modifier.height(8.dp))
                        reportLines(r, prev, km, hrList, stringResource(sportOf(r).labelRes), ctx).forEach { line ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Box(
                                    Modifier
                                        .padding(top = 7.dp)
                                        .size(5.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(AvColors.cyan)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ⑦ 打卡点（照片横滑，点击放大）
                if (wps.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(wps) { w ->
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        when (w.kind) {
                                            "M" -> AvColors.yellow.copy(alpha = 0.15f)
                                            "K" -> AvColors.blue.copy(alpha = 0.15f)
                                            "C" -> AvColors.green.copy(alpha = 0.15f)
                                            else -> AvColors.purple.copy(alpha = 0.15f)
                                        }
                                    )
                                    .clickable(enabled = w.photo != null) { zoomPath = w.photo!! }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                w.photo?.let { path ->
                                    val bmp = remember(path) { decodeThumb(path) }
                                    bmp?.let {
                                        androidx.compose.foundation.Image(
                                            it.asImageBitmap(), null,
                                            Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                }
                                Text(w.label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ⑧ 导出 GPX / 生成分享图
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(50.dp)
                            .glassCard(dark)
                            .clickable {
                                scope.launch(Dispatchers.IO) {
                                    val name = "altivane_" +
                                            SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA)
                                                .format(Date(r.startTime))
                                    val gpx = GpxExporter.buildGpx(name, pts, wps)
                                    val f = GpxExporter.save(ctx, "$name.gpx", gpx)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, ctx.getString(R.string.toast_gpx_done), Toast.LENGTH_SHORT).show()
                                        shareFile(ctx, f, "application/gpx+xml")
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.btn_export_gpx),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(50.dp)
                            .glassCard(dark, AvColors.cyan)
                            .clickable(onClick = onShare),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Share, null,
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.btn_share_image),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }

                // ⑨ 存为我的路线（v0.8.46）：轨迹降采样存入 local_routes，在 我的·路线 页查看
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .glassCard(dark, AvColors.green)
                        .clickable {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val step = maxOf(1, pts.size / 300)
                                    val arr = org.json.JSONArray()
                                    for (i in pts.indices step step) {
                                        arr.put(
                                            org.json.JSONArray()
                                                .put(pts[i].lat).put(pts[i].lon).put(pts[i].ele)
                                        )
                                    }
                                    app.db.dao().insertLocalRoute(
                                        com.altivane.app.data.LocalRouteEntity(
                                            name = SimpleDateFormat("MM.dd HH:mm", Locale.CHINA)
                                                .format(Date(r.startTime)),
                                            dateMs = r.startTime,
                                            sport = r.sportType,
                                            distKm = r.distanceM / 1000.0,
                                            gainM = r.elevGainM,
                                            durS = r.movingS,
                                            ptsJson = arr.toString()
                                        )
                                    )
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            ctx, ctx.getString(R.string.lr_saved), Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    com.altivane.app.domain.AppLog.e("Route", "save local route", e)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.lr_save),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    // 打卡照片全屏查看（EXIF 转正 / 按比例占满 / 动态播放 / 相册查看）
    zoomPath?.let { p ->
        PhotoZoomDialog(p) { zoomPath = null }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.IO) {
                        app.db.dao().deletePoints(id)
                        app.db.dao().deleteWaypoints(id)
                        app.db.dao().deleteRide(id)
                        withContext(Dispatchers.Main) { onBack() }
                    }
                }) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.btn_cancel)) } },
            title = { Text(stringResource(R.string.dlg_del_title)) },
            text = { Text(stringResource(R.string.dlg_del_text)) }
        )
    }
}

/** 运动解读文案：对比上次 / 速度配速 / 爬升海拔 / 心率强度判读 */
private fun reportLines(
    r: RideEntity, prev: RideEntity?, km: Double, hrList: List<Double>, sportName: String,
    ctx: android.content.Context
): List<String> {
    val sport = sportOf(r)
    val lines = mutableListOf<String>()
    // ① 里程与上次对比
    if (prev != null) {
        val prevKm = prev.distanceM / 1000.0
        val d = km - prevKm
        lines += ctx.getString(
            R.string.rep_vs_prev, sportName, km, if (d >= 0) "+" else "", abs(d), prevKm
        )
        lines += ctx.getString(
            R.string.rep_moving_vs, fmtCardDur(r.movingS * 1000L), fmtCardDur(prev.movingS * 1000L)
        )
    } else {
        lines += ctx.getString(R.string.rep_first, sportName, km, fmtCardDur(r.movingS * 1000L))
    }
    // ② 速度 / 配速
    if (sport == SportType.RIDE) {
        lines += ctx.getString(R.string.rep_avg_peak, r.avgKmh, r.maxKmh)
    } else {
        val pace = if (r.avgKmh > 0.1) Coach.paceText(60.0 / r.avgKmh) else "--"
        lines += ctx.getString(R.string.rep_pace, pace, r.maxKmh)
    }
    // ③ 爬升 / 海拔 / 消耗
    lines += ctx.getString(R.string.rep_climb_elev_cal, r.elevGainM, r.maxAltM, r.kcal.toInt())
    // ④ 心率强度判读：平均心率占本次峰值的比例
    if (hrList.size >= 2) {
        val avg = hrList.average()
        val max = hrList.max()
        val verdict = when {
            avg / max < 0.75 -> ctx.getString(R.string.rep_hr_aero)
            avg / max < 0.88 -> ctx.getString(R.string.rep_hr_lactate)
            else -> ctx.getString(R.string.rep_hr_hard)
        }
        lines += ctx.getString(R.string.rep_hr_fmt, avg.toInt(), max.toInt(), verdict)
    }
    return lines
}

private fun sportOf(r: RideEntity): SportType =
    try { SportType.valueOf(r.sportType) } catch (_: Exception) { SportType.RIDE }

/** 统计卡时长：数字冒号格式（1:23:45 / 5:32），与主页时长卡统一 */
private fun fmtCardDur(ms: Long): String {
    val sec = ms / 1000
    val h = sec / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (sec % 3600) / 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)
}

/** 打卡照片缩略图（降采样防 OOM） */
private fun decodeThumb(path: String): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val sample = maxOf(1, bounds.outWidth / 200)
    android.graphics.BitmapFactory.decodeFile(
        path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    )
} catch (_: Exception) {
    null
}

fun shareFile(ctx: android.content.Context, f: File, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, null))
    } catch (e: Exception) {
        // 路径不在 FileProvider 白名单等场景：提示而非闪退
        android.widget.Toast.makeText(
            ctx, ctx.getString(R.string.toast_share_fail, e.message ?: "-"), android.widget.Toast.LENGTH_SHORT
        ).show()
        com.altivane.app.domain.AppLog.e("Share", "shareFile ${f.name} failed", e)
    }
}
