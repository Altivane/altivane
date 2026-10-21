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
import com.altivane.app.domain.RoutesData
import com.altivane.app.domain.SportType
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
                        profile.name.ifEmpty { "Altivane 用户" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    val extra = listOfNotNull(
                        if (profile.age > 0) "${profile.age}岁" else null,
                        if (profile.heightCm > 0) "${profile.heightCm}cm" else null,
                        if (profile.weightKg > 0) "%.0fkg".format(profile.weightKg) else null,
                        if (profile.blood.isNotEmpty()) "${profile.blood}型" else null
                    ).joinToString(" · ")
                    Text(
                        if (extra.isEmpty()) "累计 %.2f km".format(totalKm) + " · ${rides.size} 次 · ${totalMin / 60} 小时"
                        else "$extra · 累计 %.2f km".format(totalKm),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 统计三卡
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("总里程", "%.0f".format(totalKm), "km", AvColors.blue, Modifier.weight(1f))
            StatCard("总时长", "${totalMin / 60}h", "", AvColors.cyan, Modifier.weight(1f))
            StatCard("总消耗", "${rides.sumOf { it.kcal }.toInt()}", "kcal", AvColors.pink, Modifier.weight(1f))
        }

        // 菜单
        val menus: List<Triple<String, String, () -> Unit>> = listOf(
            Triple("全部记录", "每次运动的轨迹与数据", onOpenHistory),
            Triple("我的路线", "内置经典线路 · 热门路线", onOpenRoutes),
            Triple("我的成就", "里程碑 · 三山五岳 · 打卡景点", onOpenAchievements),
            Triple("年度报告", "今年运动数据一览", onOpenReport),
            Triple("我的收藏", "收藏的路线与记录", {
                Toast.makeText(ctx, "收藏功能开发中", Toast.LENGTH_SHORT).show()
            }),
            Triple("设置", "主题 · 蓝牙外设 · 保活 · 关于", onOpenMore)
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

/** 我的路线：内置经典线路 + 距您距离（热门路线预留区块） */
@Composable
fun RoutesScreen(onBack: () -> Unit, onOpen: (Int) -> Unit) {
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
                Text("我的路线", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "内置经典线路 · 示意 GPS",
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
                    if (d >= 1000.0) "距您 %.0f km".format(d / 1000.0)
                    else "距您 %.0f m".format(d)
                } ?: "距您 --"
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
                                "$distTxt · ${r.region} · 主峰 ${r.summitEle.toInt()} m · ${r.distKm} km",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // 热门路线预留区块
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .glassCard(isDark(), null)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("当地热门路线", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "即将上线：基于你所在城市的运动热力推荐",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("soon", style = MaterialTheme.typography.labelMedium,
                            color = AvColors.cyan)
                    }
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
                    "海拔剖面：${r.pts.first().ele.toInt()} m → ${r.summitEle.toInt()} m",
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
                Text("线路信息", style = MaterialTheme.typography.titleMedium)
                Text(
                    "主峰海拔 ${r.summitEle.toInt()} m\n" +
                            "全程约 ${r.distKm} km\n" +
                            "参考登顶 ${r.hours} 小时\n" +
                            r.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim
                )
                Text(
                    "提示：坐标为经典线路简化示意，实爬请开启徒步模式记录",
                    style = MaterialTheme.typography.labelMedium,
                    color = AvColors.yellow
                )
            }
        }
    }
}

