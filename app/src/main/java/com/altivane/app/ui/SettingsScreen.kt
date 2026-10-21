package com.altivane.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altivane.app.RideApp
import com.altivane.app.sensor.BleManager
import kotlinx.coroutines.launch

/** 设置：主题、蓝牙外设、保活提示、离线地图、关于 */
@Composable
fun SettingsScreen(app: RideApp, onBack: () -> Unit, onOpenOfflineMap: () -> Unit = {}) {
    val ctx = LocalContext.current
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
            Text(
                "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassCard(accent = AvColors.purple) {
                Column {
                    Text("主题", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { m ->
                            val sel = AppTheme.mode == m
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (sel) AvColors.purple.copy(alpha = 0.16f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f)
                                    )
                                    .border(
                                        1.dp,
                                        if (sel) AvColors.purple
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { AppTheme.set(ctx, m) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    m.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (sel) AvColors.onColor(AvColors.purple)
                                    else MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (AppTheme.mode) {
                            ThemeMode.LIGHT -> "日光竞速：米白底 · 墨青字"
                            ThemeMode.DARK -> "暗夜竞速：深空黑 · 电光青"
                            ThemeMode.SYSTEM -> "跟随系统深浅色自动切换"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            GlassCard(accent = AvColors.cyan) {
                Column {
                    Text("蓝牙外设", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    BlePanel(app.ble)
                }
            }

            GlassCard(accent = AvColors.orange) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("后台记录保活", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1. 位置权限设为「始终允许」，否则锁屏后轨迹中断\n" +
                                "2. 电池优化对本应用设为「不限制」，国产 ROM 必须设置\n" +
                                "3. 最近任务里锁定本应用，防止划掉被杀",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 离线地图场景：城市离线包下载/更新/删除（独立页管理，含存储授权引导）
            GlassCard(accent = AvColors.teal) {
                Column(
                    Modifier.clickable { onOpenOfflineMap() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("离线地图", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "下载管理 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = AvColors.onColor(AvColors.teal)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "按城市/省份下载地图离线包，山区/郊区无网也能看清轨迹底图；" +
                                "存储在公共目录（文件管理器可见），支持下载/暂停/删除/更新",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            GlassCard(accent = AvColors.yellow) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("运行日志", style = MaterialTheme.typography.titleMedium)
                    val logFile = com.altivane.app.domain.AppLog.file()
                    val crash = com.altivane.app.domain.AppLog.crashFile()
                    val sizeKb = remember { (logFile?.length() ?: 0L) / 1024 }
                    Text(
                        buildString {
                            append("记录 App 运行与崩溃信息（${sizeKb}KB），闪退后分享日志便于定位修复")
                            if (crash != null) append(" · 检测到崩溃记录！")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (crash != null) AvColors.pink else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.TextButton(onClick = {
                            val f = crash ?: logFile
                            if (f != null) {
                                shareFile(
                                    ctx, f,
                                    if (f.name.endsWith(".txt")) "text/plain" else "text/plain"
                                )
                            }
                        }) { Text("导出${if (crash != null) "崩溃日志" else "日志"}", color = AvColors.cyan) }
                        androidx.compose.material3.TextButton(onClick = {
                            com.altivane.app.domain.AppLog.clear()
                        }) { Text("清空", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            GlassCard(accent = AvColors.green) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("关于 Altivane", style = MaterialTheme.typography.titleMedium)
                    val ver = remember {
                        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }
                        catch (_: Exception) { "?" }
                    }
                    Text(
                        "v$ver · 骑行 / 徒步\n" +
                                "GPS 轨迹 · BLE 心率踏频 · 气压海拔 · 矢量罗盘\n" +
                                "GPX 1.1 导出（gpxtpx 扩展 + 打卡点）\n" +
                                "数据仅存本机，无账号无上传",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "声明：本应用处于开发测试阶段，仅供个人学习使用，请勿商用。" +
                                "（当前接入的地图厂商 SDK 为非商用授权，商用需另行取得厂商许可）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

/** 蓝牙扫描/连接面板（从主页移入设置） */
/** 蓝牙外设面板（M3 卡片化）：实时状态 / 已保存设备 / 扫描连接 */
@Composable
fun BlePanel(ble: BleManager) {
    val scope = rememberCoroutineScope()
    val devices by ble.scanResults.collectAsState()
    val hr by ble.hr.collectAsState()
    val cad by ble.cad.collectAsState()
    val hrName by ble.hrName.collectAsState()
    val cadName by ble.cadName.collectAsState()
    val savedDevs by ble.saved.collectAsState()
    val link by ble.linkState.collectAsState()
    val activeHr by ble.activeHr.collectAsState()
    val activeCad by ble.activeCad.collectAsState()
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(scanning) {
        if (scanning) { kotlinx.coroutines.delay(8500); scanning = false }
    }

    Column {
        // ---- 实时状态卡 ----
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("心率", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (hr > 0) hr.toString() else "--",
                        fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = if (hr > 0) AvColors.pink else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("  次/分", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (hrName.isNotEmpty()) hrName
                    else if (link.values.any { it == "connected" }) "已连接"
                    else "未连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
            Column(Modifier.weight(1f)) {
                Text("踏频", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (cad >= 0) cad.toString() else "--",
                        fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = if (cad >= 0) AvColors.purple else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("  rpm", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (cadName.isNotEmpty()) cadName
                    else if (link.values.any { it == "connected" }) "已连接 · 未接踏频传感器"
                    else "未连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 已保存设备 ----
        if (savedDevs.isNotEmpty()) {
            Text("已保存设备", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            savedDevs.forEach { d ->
                val state = link[d.addr]          // connecting / connected / null
                // 绿点只标"当前生效"设备（心率或踏频数据来源），其余已保存设备不标
                val inUse = activeHr == d.addr || activeCad == d.addr
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(8.dp).background(
                            when {
                                inUse -> AvColors.green
                                state == "connecting" -> AvColors.yellow
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            androidx.compose.foundation.shape.CircleShape
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            com.altivane.app.sensor.classifyDevice(d.name, d.isHr, d.isCad).label + " · " +
                                    (if (d.isHr) "心率" else if (d.isCad) "踏频" else "外设") +
                                    (if (inUse) " · 使用中" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (inUse) AvColors.green
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!inUse) {
                        androidx.compose.material3.TextButton(onClick = { ble.connect(d.addr) }) {
                            Text("连接", color = AvColors.cyan)
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { ble.removeSaved(d.addr) }) {
                        Text("移除", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(4.dp))

        // ---- 扫描：标题行 + 小按钮（结果行才是主角） ----
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("发现新设备", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedButton(
                onClick = { if (!scanning) { scanning = true; ble.startScan(scope) } else ble.stopScan() },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(
                    if (scanning) "扫描中…点击停止" else "扫描",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (!ble.hasBtPermission()) {
            Text("缺少蓝牙权限，请到系统设置开启", style = MaterialTheme.typography.bodySmall)
        } else {
            // 过滤掉已保存设备，避免与上方列表重复
            val fresh = devices.filterNot { d -> savedDevs.any { it.addr == d.addr } }
            if (fresh.isNotEmpty()) {
                fresh.forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (d.isHr) "心率" else "踏频",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (d.isHr) AvColors.pink else AvColors.purple
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                com.altivane.app.sensor.classifyDevice(d.name, d.isHr, d.isCad).label +
                                        " · ${d.rssi} dBm",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.TextButton(onClick = { ble.connect(d.addr) }) {
                            Text("连接", color = AvColors.cyan, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            } else if (scanning) {
                Text(
                    "未发现新设备。手环连接状态下不会广播：若刚断开/移除，等几秒让它恢复广播，或关掉再打开「心率共享」后重扫。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "提示：手环请开启「心率共享/广播」；App 启动、回到前台、点 GO 时会自动连接已保存设备，意外断线后也会自动挂回。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
