package com.altivane.app.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.data.RideEntity
import com.altivane.app.domain.Coach
import com.altivane.app.domain.RoutesData
import com.altivane.app.domain.SportType
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 我的：个人资料 / 成就 / 我的路线 / 收藏 / 年度报告 / 全部记录。
 */
@Composable
fun MineScreen(
    app: RideApp,
    onOpenHistory: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenMore: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val ctx = LocalContext.current
    val rides by app.db.dao().rides().collectAsState(initial = emptyList())
    val profile = ProfileStore.state.value

    val totalKm = rides.sumOf { it.distanceM } / 1000.0
    val totalMin = rides.sumOf { it.movingS } / 60

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.nav_mine),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 个人资料卡（点击进入编辑）
        Box(
            Modifier
                .fillMaxWidth()
                .glassCard(isDark(), AvColors.cyan)
                .clickable(onClick = onOpenProfile)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(profile, Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.name.ifEmpty { stringResource(R.string.default_user) },
                        style = MaterialTheme.typography.titleMedium
                    )
                    val extra = listOfNotNull(
                        if (profile.age > 0) stringResource(R.string.age_fmt, profile.age) else null,
                        if (profile.heightCm > 0) "${profile.heightCm}cm" else null,
                        if (profile.weightKg > 0) "%.0fkg".format(profile.weightKg) else null,
                        if (profile.blood.isNotEmpty()) stringResource(R.string.blood_fmt, profile.blood) else null
                    ).joinToString(" · ")
                    Text(
                        if (extra.isEmpty()) stringResource(R.string.profile_total, totalKm, rides.size, totalMin / 60)
                        else stringResource(R.string.profile_total_extra, extra, totalKm),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 统计三卡
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(stringResource(R.string.stat_total_km), "%.0f".format(totalKm), "km", AvColors.blue, Modifier.weight(1f))
            StatCard(stringResource(R.string.stat_total_dur), "${totalMin / 60}h", "", AvColors.cyan, Modifier.weight(1f))
            StatCard(stringResource(R.string.lbl_total_cals), "${rides.sumOf { it.kcal }.toInt()}", "kcal", AvColors.pink, Modifier.weight(1f))
        }

        // 菜单
        val menus: List<Triple<String, String, () -> Unit>> = listOf(
            Triple(stringResource(R.string.menu_history), stringResource(R.string.menu_history_sub), onOpenHistory),
            Triple(stringResource(R.string.menu_routes), stringResource(R.string.menu_routes_sub), onOpenRoutes),
            Triple(stringResource(R.string.menu_achv), stringResource(R.string.menu_achv_sub), onOpenAchievements),
            Triple(stringResource(R.string.menu_report), stringResource(R.string.menu_report_sub), onOpenReport),
            Triple(stringResource(R.string.menu_fav), stringResource(R.string.menu_routes_sub), {
                Toast.makeText(ctx, ctx.getString(R.string.toast_fav_todo), Toast.LENGTH_SHORT).show()
            }),
            Triple(stringResource(R.string.menu_settings), stringResource(R.string.menu_settings_sub), onOpenMore)
        )
        menus.forEach { (title, sub, action) ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassCard(isDark(), null)
                    .clickable(onClick = action)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(sub, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** 缓存定位：读取系统最后一次已知位置（免 GMS，权限已随首页申请） */
private fun lastKnownLoc(ctx: android.content.Context): android.location.Location? {
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    for (p in listOf(
        android.location.LocationManager.GPS_PROVIDER,
        android.location.LocationManager.NETWORK_PROVIDER,
        android.location.LocationManager.PASSIVE_PROVIDER
    )) {
        try {
            if (lm.isProviderEnabled(p)) {
                lm.getLastKnownLocation(p)?.let { if (it.latitude != 0.0 || it.longitude != 0.0) return it }
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    return null
}

/** 我的路线：内置经典线路 + 本地保存的运动路线 */
@Composable
fun RoutesScreen(
    app: RideApp,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    onOpenLocal: (Long) -> Unit
) {
    val ctx = LocalContext.current
    val loc = remember { lastKnownLoc(ctx) }

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column {
                Text(stringResource(R.string.menu_routes), style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    stringResource(R.string.routes_sub),
                    style = MaterialTheme.typography.labelMedium,
                    color = AvColors.green
                )
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(RoutesData.all) { idx, r ->
                val distTxt = loc?.let {
                    val d = com.altivane.app.domain.RideStats.haversine(
                        it.latitude, it.longitude, r.pts.first().lat, r.pts.first().lon
                    )
                    if (d >= 1000.0) stringResource(R.string.dist_km_fmt, d / 1000.0)
                    else stringResource(R.string.dist_m_fmt, d)
                } ?: stringResource(R.string.dist_none)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .glassCard(isDark(), null)
                        .clickable { onOpen(idx) }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium,
                                    fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(r.tag, style = MaterialTheme.typography.labelMedium,
                                    color = AvColors.orange)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "$distTxt · ${r.region} · ${stringResource(R.string.summit_fmt, r.summitEle)} · ${r.distKm} km",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // 本地路线：运动轨迹「存为我的路线」保存的线路（v0.8.46）
            item {
                val lrs by app.db.dao().localRoutes().collectAsState(initial = emptyList())
                var delId by remember { mutableStateOf<Long?>(null) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .glassCard(isDark(), null)
                        .padding(14.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.local_routes),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${lrs.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = AvColors.cyan
                            )
                        }
                        if (lrs.isEmpty()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                stringResource(R.string.routes_soon),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            lrs.take(10).forEach { lr ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenLocal(lr.id) }
                                        .padding(top = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            lr.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "%.2f km · %s · ↑%.0f m".format(
                                                lr.distKm, Coach.fmtDur(lr.durS * 1000L), lr.gainM
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { delId = lr.id }) {
                                        Icon(
                                            androidx.compose.material.icons.Icons.Filled.Delete, null,
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                delId?.let { id ->
                    AlertDialog(
                        onDismissRequest = { delId = null },
                        confirmButton = {
                            TextButton(onClick = {
                                delId = null
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    app.db.dao().deleteLocalRoute(id)
                                }
                            }) { Text(stringResource(R.string.btn_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { delId = null }) { Text(stringResource(R.string.btn_cancel)) }
                        },
                        title = { Text(stringResource(R.string.lr_delete_confirm)) }
                    )
                }
            }
        }
    }
}

/** 路线详情：矢量线路图 + 海拔剖面 + 信息 */
@Composable
fun RouteDetailScreen(idx: Int, onBack: () -> Unit) {
    val r = RoutesData.all.getOrNull(idx) ?: return
    val dark = isDark()
    val fg = MaterialTheme.colorScheme.onBackground
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column {
                Text("${r.name} · ${r.tag}", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(r.region, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 线路切换 chips（每山多条登山线路）
        var lineIdx by remember(r) { mutableStateOf(0) }
        val line = r.lines[lineIdx.coerceIn(0, r.lines.size - 1)]
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(r.lines) { i, ln ->
                val sel = i == lineIdx
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (sel) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                        )
                        .clickable { lineIdx = i }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        ln.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sel) MaterialTheme.colorScheme.background
                        else MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        // 地图（OpenFreeMap 矢量，可切卫星）：景点节点平滑连线 = 推荐线路（OSRM 路网
        // 会把登山步道绕到环山公路，实测泰山红门线完全跑偏，v0.8.32 起弃用）
        val mapH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 2 / 3
        MapTrackView(
            pts = line.pts.map { TrackPt(it.lat, it.lon) },
            speeds = emptyList(),
            maxSpeed = 0.0,
            waypoints = line.pts.map { WpPt(it.lat, it.lon, "", it.name) },
            presetMarkers = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(mapH.dp)
                .clip(RoundedCornerShape(18.dp))
        )

        // 海拔剖面
        GlassCard(Modifier.height(90.dp), accent = AvColors.orange) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(
                        R.string.profile_fmt,
                        line.pts.first().ele.toInt(),
                        line.pts.maxOf { it.ele }.toInt()
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = dim
                )
                Sparkline(
                    r.pts.map { it.ele }, AvColors.orange,
                    Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
                )
            }
        }

        // 信息卡
        GlassCard(accent = AvColors.green) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.route_info), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.route_info_fmt, r.summitEle, "${r.distKm}", r.hours) +
                            "\n" + r.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim
                )
                Text(
                    stringResource(R.string.route_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = AvColors.yellow
                )
            }
        }
    }
}

/** 本地路线详情（存为我的路线）：地图 + 海拔剖面 + 统计 + 删除 */
@Composable
fun LocalRouteDetailScreen(app: RideApp, id: Long, onBack: () -> Unit) {
    var lr by remember { mutableStateOf<com.altivane.app.data.LocalRouteEntity?>(null) }
    LaunchedEffect(id) {
        lr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.db.dao().localRoute(id)
        }
    }
    val r = lr ?: return
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    // ptsJson [[lat,lon,ele],...] → 轨迹点
    val pts = remember(r) {
        try {
            val arr = org.json.JSONArray(r.ptsJson)
            (0 until arr.length()).map { i ->
                val a = arr.getJSONArray(i)
                Triple(a.getDouble(0), a.getDouble(1), a.getDouble(2))
            }
        } catch (_: Exception) {
            emptyList()
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column {
                Text(r.name, style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                        .format(java.util.Date(r.dateMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val mapH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 2 / 3
        MapTrackView(
            pts = pts.map { TrackPt(it.first, it.second) },
            speeds = emptyList(),
            maxSpeed = 0.0,
            waypoints = emptyList(),
            modifier = Modifier
                .fillMaxWidth()
                .height(mapH.dp)
                .clip(RoundedCornerShape(18.dp))
        )

        GlassCard(Modifier.height(90.dp), accent = AvColors.orange) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    if (pts.size >= 2)
                        stringResource(
                            R.string.profile_fmt,
                            pts.first().third.toInt(),
                            pts.maxOf { it.third }.toInt()
                        )
                    else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = dim
                )
                Sparkline(
                    pts.map { it.third }, AvColors.orange,
                    Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "%.2f".format(r.distKm) to stringResource(R.string.unit_km_dist),
                Coach.fmtDur(r.durS * 1000L) to stringResource(R.string.card_duration),
                "%.0f".format(r.gainM) to stringResource(R.string.unit_m_climb)
            ).forEach { (v, l) ->
                Box(
                    Modifier
                        .weight(1f)
                        .glassCard(isDark(), null)
                        .padding(vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text(l, style = MaterialTheme.typography.labelMedium, color = dim)
                    }
                }
            }
        }
    }
}

