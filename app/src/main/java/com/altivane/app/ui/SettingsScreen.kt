package com.altivane.app.ui

import android.app.Activity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.domain.AppLang
import com.altivane.app.sensor.BleManager
import kotlinx.coroutines.launch

/** 设置：语言、主题、蓝牙外设、保活提示、离线地图、运行日志、关于 */
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
                stringResource(R.string.set_title),
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
            // ---- 语言：跟随系统 / 中文 / English（应用内切换，recreate 生效） ----
            GlassCard(accent = AvColors.blue) {
                Column {
                    Text(
                        stringResource(R.string.set_lang),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val cur = AppLang.get(ctx)
                        listOf(AppLang.SYSTEM to stringResource(R.string.set_lang_sys), AppLang.ZH to "中文", AppLang.EN to "English")
                            .forEach { (lang, name) ->
                                val sel = cur == lang
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (sel) AvColors.blue.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f)
                                        )
                                        .border(
                                            1.dp,
                                            if (sel) AvColors.blue
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            if (cur != lang) {
                                                AppLang.set(ctx, lang)
                                                (ctx as? Activity)?.recreate()
                                            }
                                        }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (sel) AvColors.onColor(AvColors.blue)
                                        else MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                    }
                }
            }

            GlassCard(accent = AvColors.purple) {
                Column {
                    Text(stringResource(R.string.set_theme), style = MaterialTheme.typography.titleMedium)
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
                            ThemeMode.LIGHT -> stringResource(R.string.set_theme_light)
                            ThemeMode.DARK -> stringResource(R.string.set_theme_dark)
                            ThemeMode.SYSTEM -> stringResource(R.string.set_theme_sys)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            GlassCard(accent = AvColors.cyan) {
                Column {
                    Text(stringResource(R.string.set_ble), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    BlePanel(app.ble)
                }
            }

            GlassCard(accent = AvColors.orange) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.set_keepalive), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.set_keepalive_text),
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
                        Text(stringResource(R.string.set_offline_map), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.set_offline_manage),
                            style = MaterialTheme.typography.labelMedium,
                            color = AvColors.onColor(AvColors.teal)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.set_offline_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            GlassCard(accent = AvColors.yellow) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.set_log), style = MaterialTheme.typography.titleMedium)
                    val logFile = com.altivane.app.domain.AppLog.file()
                    val crash = com.altivane.app.domain.AppLog.crashFile()
                    val sizeKb = remember { (logFile?.length() ?: 0L) / 1024 }
                    Text(
                        buildString {
                            append(stringResource(R.string.set_log_desc, sizeKb))
                            if (crash != null) append(stringResource(R.string.set_log_crash_suffix))
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
                        }) {
                            Text(
                                stringResource(if (crash != null) R.string.btn_export_crash else R.string.btn_export_log),
                                color = AvColors.cyan
                            )
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            com.altivane.app.domain.AppLog.clear()
                        }) { Text(stringResource(R.string.btn_clear), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            GlassCard(accent = AvColors.green) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.set_about), style = MaterialTheme.typography.titleMedium)
                    val ver = remember {
                        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }
                        catch (_: Exception) { "?" }
                    }
                    Text(
                        "v$ver · ${stringResource(R.string.sport_ride)} / ${stringResource(R.string.sport_hike)}\n" +
                                stringResource(R.string.about_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.about_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

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
                Text(stringResource(R.string.card_hr), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (hr > 0) hr.toString() else "--",
                        fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = if (hr > 0) AvColors.pink else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("  " + stringResource(R.string.ble_bpm), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (hrName.isNotEmpty()) hrName
                    else if (link.values.any { it == "connected" }) stringResource(R.string.ble_connected)
                    else stringResource(R.string.ble_disconnected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.card_cad), style = MaterialTheme.typography.labelMedium,
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
                    else if (link.values.any { it == "connected" }) stringResource(R.string.ble_cad_missing)
                    else stringResource(R.string.ble_disconnected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 已保存设备 ----
        if (savedDevs.isNotEmpty()) {
            Text(stringResource(R.string.ble_saved), style = MaterialTheme.typography.titleSmall)
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
                            stringResource(com.altivane.app.sensor.classifyDevice(d.name, d.isHr, d.isCad).labelRes) + " · " +
                                    stringResource(if (d.isHr) R.string.card_hr else if (d.isCad) R.string.card_cad else R.string.dev_other) +
                                    (if (inUse) stringResource(R.string.ble_in_use) else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (inUse) AvColors.green
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!inUse) {
                        androidx.compose.material3.TextButton(onClick = { ble.connect(d.addr) }) {
                            Text(stringResource(R.string.btn_connect), color = AvColors.cyan)
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { ble.removeSaved(d.addr) }) {
                        Text(stringResource(R.string.btn_remove), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(stringResource(R.string.ble_found), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedButton(
                onClick = { if (!scanning) { scanning = true; ble.startScan(scope) } else ble.stopScan() },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(
                    stringResource(if (scanning) R.string.ble_scanning else R.string.ble_scan),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (!ble.hasBtPermission()) {
            Text(stringResource(R.string.ble_no_permission), style = MaterialTheme.typography.bodySmall)
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
                            stringResource(if (d.isHr) R.string.card_hr else R.string.card_cad),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (d.isHr) AvColors.pink else AvColors.purple
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                stringResource(com.altivane.app.sensor.classifyDevice(d.name, d.isHr, d.isCad).labelRes) +
                                        " · ${d.rssi} dBm",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.TextButton(onClick = { ble.connect(d.addr) }) {
                            Text(stringResource(R.string.btn_connect), color = AvColors.cyan, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            } else if (scanning) {
                Text(
                    stringResource(R.string.ble_none_found),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.ble_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
