package com.altivane.app.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altivane.app.RideApp
import com.altivane.app.domain.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** CARTO Voyager（与在线同 URL，离线包才生效）；桌面基准 100 瓦片 0 失败 17s，
 *  OpenFreeMap 同测挂 10 个 59s——离线"慢且断续"根因即源限流。免费源仍保留退避重试兜底 */
private const val OM_STYLE = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"
private const val OM_MIN_ZOOM = 9.0
private const val OM_MAX_ZOOM = 12.0
private const val OM_TILE_LIMIT = 3000L

/** 城市 bbox（south, west, north, east，紧凑覆盖建成区+主要山域） */
private val CITIES: List<Pair<String, DoubleArray>> = listOf(
    "北京" to doubleArrayOf(39.7, 116.2, 40.1, 116.7),
    "上海" to doubleArrayOf(30.9, 121.1, 31.5, 121.9),
    "天津" to doubleArrayOf(38.9, 116.9, 39.4, 117.6),
    "重庆" to doubleArrayOf(29.3, 106.2, 29.8, 106.9),
    "广州" to doubleArrayOf(22.9, 113.0, 23.3, 113.7),
    "深圳" to doubleArrayOf(22.4, 113.7, 22.8, 114.4),
    "珠海" to doubleArrayOf(22.1, 113.4, 22.3, 113.7),
    "杭州" to doubleArrayOf(30.0, 119.9, 30.5, 120.6),
    "宁波" to doubleArrayOf(29.6, 121.3, 30.1, 121.9),
    "南京" to doubleArrayOf(31.8, 118.5, 32.2, 119.1),
    "苏州" to doubleArrayOf(30.9, 120.3, 31.4, 121.0),
    "武汉" to doubleArrayOf(30.3, 114.1, 30.8, 114.7),
    "成都" to doubleArrayOf(30.4, 103.8, 30.8, 104.4),
    "西安" to doubleArrayOf(33.9, 108.7, 34.5, 109.2),
    "郑州" to doubleArrayOf(34.5, 113.4, 34.9, 113.9),
    "登封（嵩山）" to doubleArrayOf(34.3, 112.9, 34.6, 113.3),
    "济南" to doubleArrayOf(36.4, 116.8, 36.9, 117.4),
    "青岛" to doubleArrayOf(35.9, 119.9, 36.4, 120.6),
    "泰安（泰山）" to doubleArrayOf(36.0, 117.0, 36.4, 117.4),
    "石家庄" to doubleArrayOf(37.9, 114.2, 38.2, 114.8),
    "太原" to doubleArrayOf(37.6, 112.3, 38.0, 112.8),
    "大同（恒山）" to doubleArrayOf(39.9, 113.2, 40.3, 113.7),
    "沈阳" to doubleArrayOf(41.5, 123.1, 42.0, 123.7),
    "大连" to doubleArrayOf(38.7, 121.2, 39.1, 121.8),
    "长春" to doubleArrayOf(43.5, 125.1, 43.9, 125.7),
    "哈尔滨" to doubleArrayOf(45.5, 126.3, 45.9, 127.0),
    "昆明" to doubleArrayOf(24.8, 102.5, 25.3, 103.1),
    "丽江" to doubleArrayOf(26.7, 100.1, 27.1, 100.6),
    "贵阳" to doubleArrayOf(26.4, 106.4, 26.8, 106.9),
    "长沙" to doubleArrayOf(27.9, 112.7, 28.4, 113.3),
    "衡阳（衡山）" to doubleArrayOf(27.1, 112.4, 27.5, 112.9),
    "九江（庐山）" to doubleArrayOf(29.5, 115.8, 29.9, 116.2),
    "南昌" to doubleArrayOf(28.4, 115.7, 28.8, 116.3),
    "黄山" to doubleArrayOf(29.9, 118.1, 30.4, 118.6),
    "合肥" to doubleArrayOf(31.6, 117.0, 32.1, 117.5),
    "福州" to doubleArrayOf(25.8, 118.9, 26.3, 119.6),
    "厦门" to doubleArrayOf(24.3, 117.8, 24.7, 118.3),
    "南宁" to doubleArrayOf(22.6, 108.1, 23.1, 108.6),
    "桂林" to doubleArrayOf(24.9, 110.1, 25.4, 110.7),
    "张家界" to doubleArrayOf(29.0, 110.3, 29.5, 110.9),
    "渭南（华山）" to doubleArrayOf(34.4, 110.0, 34.7, 110.5),
    "海口" to doubleArrayOf(19.9, 110.1, 20.2, 110.6),
    "兰州" to doubleArrayOf(35.9, 103.4, 36.2, 103.9),
    "西宁" to doubleArrayOf(36.5, 101.5, 36.8, 102.0),
    "拉萨" to doubleArrayOf(29.4, 90.8, 29.9, 91.4),
    "乌鲁木齐" to doubleArrayOf(43.5, 87.3, 44.0, 88.0),
    "呼和浩特" to doubleArrayOf(40.6, 111.4, 41.0, 112.0),
    "香港" to doubleArrayOf(22.15, 113.8, 22.6, 114.5),
    "澳门" to doubleArrayOf(22.1, 113.5, 22.3, 113.6),
    "台北" to doubleArrayOf(24.9, 121.4, 25.2, 121.8),
)

/** 行数据：已下载离线区域 */
private data class RegionRow(val name: String, val complete: Boolean, val sizeMb: Long, val region: OfflineRegion)

/**
 * 离线地图管理页（MapLibre 版）：OpenFreeMap 矢量瓦片按城市缓存。
 * 下载/暂停/继续/删除；数据存 app 私有目录（SDK 托管，免任何存储权限）。
 * 与在线地图共用同一 style URL（OM_STYLE），离线才生效。
 */
@Composable
fun OfflineMapScreen(app: RideApp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val progress = remember { mutableStateMapOf<String, Int>() }   // 省名 → %
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    val mgr = remember { OfflineManager.getInstance(ctx) }
    val scope = rememberCoroutineScope()
    // 免费源对离线批量拉取限流（REASON_SERVER timeout 实测 16 并发挂 12%）：指数退避重试至 6 次
    val retries = remember { java.util.concurrent.ConcurrentHashMap<String, Int>() }

    DisposableEffect(Unit) {
        try { mgr.setOfflineMapboxTileCountLimit(OM_TILE_LIMIT) } catch (e: Exception) {
            AppLog.e("Offline", "tileLimit", e)
        }
        onDispose { }
    }

    // 已下载区域列表（tick 驱动重读；状态为异步 getStatus 回调补全）
    var regions by remember(tick) { mutableStateOf<List<RegionRow>>(emptyList()) }
    DisposableEffect(tick) {
        try {
            mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(rs: Array<OfflineRegion>?) {
                    val rows = (rs ?: emptyArray()).map { r ->
                        val name = try {
                            String(r.metadata, Charsets.UTF_8).trim('"', '{', '}', ' ')
                                .substringAfter(":").trim('"', ' ')
                        } catch (_: Exception) { "未知区域" }
                        RegionRow(name, false, 0L, r)
                    }
                    regions = rows
                    rows.forEach { row ->
                        try {
                            row.region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                                override fun onStatus(status: OfflineRegionStatus?) {
                                    if (status == null) return
                                    regions = regions.map {
                                        if (it.region === row.region) it.copy(
                                            complete = status.isComplete,
                                            sizeMb = status.completedResourceSize / (1024L * 1024L)
                                        ) else it
                                    }
                                }

                                override fun onError(e: String?) {
                                    AppLog.e("Offline", "status ${row.name}: $e")
                                }
                            })
                        } catch (e: Exception) {
                            AppLog.e("Offline", "getStatus ${row.name}", e)
                        }
                    }
                }

                override fun onError(e: String) {
                    AppLog.e("Offline", "list regions: $e")
                }
            })
        } catch (e: Exception) {
            AppLog.e("Offline", "listOfflineRegions", e)
        }
        onDispose { }
    }

    val downloaded = regions.map { it.name }.toSet()
    val todo = CITIES.filter { it.first !in downloaded && it.first !in progress.keys }

    fun download(name: String, bbox: DoubleArray) {
        // 免费源离线政策 ~4 并发，同时只跑 1 个城市，避免叠加限流
        if (progress.isNotEmpty()) {
            Toast.makeText(ctx, "已有城市在下载中，请等待完成", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val def = OfflineTilePyramidRegionDefinition(
                OM_STYLE,
                LatLngBounds.from(bbox[2], bbox[3], bbox[0], bbox[1]),   // north, east, south, west
                OM_MIN_ZOOM, OM_MAX_ZOOM,
                ctx.resources.displayMetrics.density
            )
            val meta = "{\"name\":\"$name\"}".toByteArray(Charsets.UTF_8)
            retries[name] = 0
            mgr.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val pct = if (status.requiredResourceCount > 0)
                                (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt()
                            else 0
                            progress[name] = pct.coerceIn(0, 99)
                            if (status.isComplete) {
                                progress.remove(name)
                                retries.remove(name)
                                tick++
                                Toast.makeText(ctx, "$name 离线包完成", Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onError(e: org.maplibre.android.offline.OfflineRegionError) {
                            AppLog.e("Offline", "$name download: ${e.reason} ${e.message}")
                            val n = (retries[name] ?: 0) + 1
                            retries[name] = n
                            if (n <= 6) {
                                // 退避续传（4s→24s 递增等待，给免费源限流窗口降温；进度不清零）
                                progress[name] = 0
                                scope.launch {
                                    delay(4000L * n)
                                    try { region.setDownloadState(OfflineRegion.STATE_ACTIVE) }
                                    catch (ex: Exception) { AppLog.e("Offline", "retry $name", ex) }
                                }
                            } else {
                                retries.remove(name)
                                progress.remove(name)
                                error = "$name 多次失败：${e.reason}（免费源限流，稍后重试或换 Wi-Fi）"
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            }
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            AppLog.e("Offline", "$name tiles > $limit（范围过大）")
                            error = "$name 瓦片数超限，已暂停（范围过大）"
                            progress.remove(name)
                            retries.remove(name)
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            tick++
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(e: String) {
                    AppLog.e("Offline", "$name create region: $e")
                    error = "$name 创建区域失败：$e"
                    progress.remove(name)
                    retries.remove(name)
                }
            })
        } catch (e: Exception) {
            AppLog.e("Offline", "$name download()", e)
            error = "$name 启动失败：${e.message}"
        }
    }

    var search by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

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
            Column(Modifier.weight(1f)) {
                Text("离线地图", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "已缓存 ${regions.size} 个城市 · OSM 矢量（免 Key）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 搜索按钮：切换城市过滤框
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) search = ""
            }) {
                Icon(
                    Icons.Default.Search, "搜索城市",
                    tint = if (showSearch) AvColors.cyan else MaterialTheme.colorScheme.onBackground
                )
            }
        }

        if (showSearch) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入城市名，如：泰安") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            error?.let {
                item {
                    GlassCard(accent = AvColors.orange) {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = AvColors.onColor(AvColors.orange))
                    }
                }
            }

            // ---- 下载中 ----
            val downloading = progress.keys.toList()
            if (downloading.isNotEmpty()) {
                item {
                    Text("下载中", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground)
                }
                items(downloading, key = { "dl_$it" }) { name ->
                    val p = progress[name] ?: 0
                    GlassCard(accent = AvColors.cyan.copy(alpha = 0.5f)) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f))
                                Text("$p%", style = MaterialTheme.typography.titleSmall,
                                    color = AvColors.onColor(AvColors.cyan), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "暂停",
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            regions.firstOrNull { it.name == name }?.region?.let { r ->
                                                try {
                                                    r.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                                } catch (e: Exception) { AppLog.e("Offline", "pause", e) }
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.fillMaxWidth().height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth((p / 100f).coerceIn(0.02f, 1f))
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(AvColors.cyan)
                                )
                            }
                        }
                    }
                }
            }

            // ---- 已缓存 ----
            if (regions.isNotEmpty()) {
                item {
                    Text("已缓存", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground)
                }
                items(regions, key = { "rg_" + it.name }) { row ->
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (row.complete) "完整 · ${row.sizeMb} MB" else "未完成 · ${row.sizeMb} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!row.complete) {
                                Text(
                                    "继续",
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            try { row.region.setDownloadState(OfflineRegion.STATE_ACTIVE) }
                                            catch (e: Exception) { AppLog.e("Offline", "resume", e) }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AvColors.onColor(AvColors.cyan)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                "删除",
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        try {
                                            row.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                                override fun onDelete() { tick++ }
                                                override fun onError(e: String) {
                                                    AppLog.e("Offline", "delete ${row.name}: $e")
                                                }
                                            })
                                        } catch (e: Exception) { AppLog.e("Offline", "delete", e) }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = AvColors.orange
                            )
                        }
                    }
                }
            }

            // ---- 可下载省份 ----
            item {
                Text("按城市缓存（zoom 9-12 街道级，一次一个）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
            val q = search.trim()
            val shown = todo.filter { q.isBlank() || it.first.contains(q) }
            items(shown, key = { "pv_" + it.first }) { (name, bbox) ->
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "下载",
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { download(name, bbox) }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = AvColors.onColor(AvColors.cyan)
                        )
                    }
                }
            }
            if (shown.isEmpty() && q.isNotBlank()) {
                item {
                    Text("无匹配城市", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
