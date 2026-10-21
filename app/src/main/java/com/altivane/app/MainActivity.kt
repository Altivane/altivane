package com.altivane.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.altivane.app.domain.CardType
import com.altivane.app.service.RideRecordingService
import com.altivane.app.ui.AvColors
import com.altivane.app.ui.DetailScreen
import com.altivane.app.ui.HistoryScreen
import com.altivane.app.ui.MineScreen
import com.altivane.app.ui.RideScreen
import com.altivane.app.ui.RideTheme
import com.altivane.app.ui.ShareScreen
import com.altivane.app.ui.glassCard
import com.altivane.app.ui.isDark
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>
    private var pendingPhoto: File? = null
    private var pendingAvatar: File? = null
    private var configReceiver: android.content.BroadcastReceiver? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val f = pendingPhoto ?: return@registerForActivityResult
            val app = application as RideApp
            val last = app.session.points.value.lastOrNull()
            if (last != null) {
                // 逆地理查位置名（POI 优先，如「黄山迎客松」），4s 超时兜底用时间
                var done = false
                fun finish(place: String) {
                    if (done) return
                    done = true
                    stampPhoto(f, place)
                    val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date(System.currentTimeMillis()))
                    app.session.addWaypoint("M", place.ifBlank { "打卡 $hhmm" }, f.absolutePath)
                }
                reverseGeo(last.lat, last.lon) { finish(it) }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish("") }, 4000)
            } else {
                stampPhoto(f, "")
                app.session.addWaypoint("M", "打卡", f.absolutePath)
            }
        }
    }

    /** 逆地理（Nominatim/OSM）：坐标 → 位置名（POI→道路→村镇三级兜底），回调主线程；失败返回空串 */
    private fun reverseGeo(lat: Double, lon: Double, cb: (String) -> Unit) {
        lifecycleScope.launch {
            var name = ""
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL(
                        "https://nominatim.openstreetmap.org/reverse?format=jsonv2" +
                                "&lat=$lat&lon=$lon&zoom=18&accept-language=zh-CN&addressdetails=1"
                    )
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    // Nominatim 政策强制唯一 User-Agent，缺失会被 403
                    conn.setRequestProperty("User-Agent", "Altivane/0.8.28 (personal hiking app)")
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val obj = org.json.JSONObject(body)
                        name = obj.optString("name")
                        if (name.isBlank()) {
                            val ad = obj.optJSONObject("address")
                            val road = ad?.optString("road")?.takeIf { it.isNotBlank() }
                            val town = ad?.optString("city")?.takeIf { it.isNotBlank() }
                                ?: ad?.optString("town")?.takeIf { it.isNotBlank() }
                                ?: ad?.optString("village")?.takeIf { it.isNotBlank() }
                            name = listOfNotNull(road, town).joinToString("")
                            if (name.isBlank()) name = obj.optString("display_name")
                        }
                    }
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
            cb(name)
        }
    }

    /** 照片烙印：底部半透明条 + 位置名 + 时间（分享/详情共用同一张图） */
    private fun stampPhoto(f: File, place: String) {
        try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts) ?: return
            val out = if (bmp.isMutable) bmp
            else bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }
            val c = android.graphics.Canvas(out)
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
            val text = if (place.isBlank()) "Altivane · $ts" else "$place · $ts"
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = out.width * 0.036f
                setShadowLayer(4f, 0f, 0f, 0x88000000.toInt())
            }
            val lineH = p.textSize * 1.7f
            val bg = android.graphics.Paint().apply { color = 0x59000000 }
            c.drawRect(0f, out.height - lineH, out.width.toFloat(), out.height.toFloat(), bg)
            c.drawText(text, (out.width - p.measureText(text)) / 2f, out.height - lineH * 0.30f, p)
            java.io.FileOutputStream(f).use {
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }
            out.recycle()
        } catch (_: Exception) {
        }
    }

    // 头像：拍照
    private val avatarTake = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingAvatar?.let {
            com.altivane.app.ui.ProfileStore.setAvatar(applicationContext, it.absolutePath)
        }
    }

    // 头像：相册选择（复制到本机 profile 目录）
    private val avatarPick = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val dir = File(getExternalFilesDir(null), "profile").apply { mkdirs() }
                val f = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
                com.altivane.app.ui.ProfileStore.setAvatar(applicationContext, f.absolutePath)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：内容延伸到状态栏/导航栏后面（各页面已用 systemBarsPadding 让出内容区）
        enableEdgeToEdge()
        // 系统深浅切换不会触发 widget 更新 —— 动态监听配置变化，全量重推桌面卡
        configReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                com.altivane.app.widget.WidgetPush.pushAll(applicationContext)
            }
        }
        registerReceiver(
            configReceiver,
            android.content.IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        )
        permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { }
        setContent {
            RideTheme {
                AppRoot()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        configReceiver?.let { runCatching { unregisterReceiver(it) } }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台全量刷新桌面卡（黑白主题 / 罗盘航向 / SOS 状态 / 运动数据）
        com.altivane.app.widget.WidgetPush.pushAll(this)
        // 蓝牙自动重连兜底：刚授权蓝牙权限 / 首次保存设备后回到前台时挂上记住的设备
        (application as RideApp).ble.reconnectSaved()
    }

    fun requestPerms() {
        permLauncher.launch(neededPermissions())
    }

    /** 徒步拍照打卡：系统相机拍完自动在当前位置写打卡点 */
    fun launchCamera() {
        val dir = File(getExternalFilesDir("Pictures"), "waypoints").apply { mkdirs() }
        val f = File(dir, "wp_${System.currentTimeMillis()}.jpg")
        pendingPhoto = f
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        takePicture.launch(uri)
    }

    /** 个人资料：拍照设置头像 */
    fun launchAvatarCamera() {
        val dir = File(getExternalFilesDir(null), "profile").apply { mkdirs() }
        val f = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
        pendingAvatar = f
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        avatarTake.launch(uri)
    }

    /** 个人资料：相册选择头像 */
    fun launchAvatarPick() {
        avatarPick.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }
}

fun neededPermissions(): Array<String> {
    val ps = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        // 手电筒（罗盘详情 SOS 卡）setTorchMode 需要 CAMERA 权限
        Manifest.permission.CAMERA
    )
    if (Build.VERSION.SDK_INT >= 31) {
        ps += Manifest.permission.BLUETOOTH_CONNECT
        ps += Manifest.permission.BLUETOOTH_SCAN
    }
    if (Build.VERSION.SDK_INT >= 33) {
        ps += Manifest.permission.POST_NOTIFICATIONS
    }
    return ps.toTypedArray()
}

fun serviceIntent(ctx: Context, action: String): Intent =
    Intent(ctx, RideRecordingService::class.java).setAction(action)

sealed interface Nav {
    data object Ride : Nav
    data object Mine : Nav
    data object History : Nav
    data object More : Nav
    data class Detail(val id: Long) : Nav
    data class Share(val id: Long) : Nav
    data class CardDetail(val type: CardType) : Nav
    data object Routes : Nav
    data class RouteDetail(val idx: Int) : Nav
    data object EditCards : Nav
    data object Achievements : Nav
    data object YearlyReport : Nav
    data object Profile : Nav
    data object OfflineMap : Nav
}

@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as RideApp
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    var nav by remember { mutableStateOf<Nav>(Nav.Ride) }
    // 路线页来源：从主页进入返回主页，从"我的"进入返回我的
    var routesFromRide by remember { mutableStateOf(false) }
    // 分享页来源：从轨迹详情页进入 → 返回轨迹详情；从运动详情进入 → 返回运动详情
    var shareFromTrack by remember { mutableStateOf(false) }
    // 主页网格滚动状态提升：进出路线/详情页后保持主页位置
    val rideGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 主页大卡下拉的全屏地图：状态必须在 AppRoot——全屏时隐藏底部导航条，
    // 否则覆盖层只能铺到内容 Box 为止，玻璃条永远压在地图上（v0.8.38 实测）
    var rideFullMap by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { activity?.requestPerms() }

    BackHandler(enabled = nav !is Nav.Ride) {
        nav = when (val n = nav) {
            is Nav.Share -> if (shareFromTrack) Nav.CardDetail(CardType.TRACK) else Nav.Detail(n.id)
            is Nav.Detail -> Nav.History
            is Nav.RouteDetail -> Nav.Routes
            is Nav.CardDetail -> Nav.Ride
            Nav.Routes -> if (routesFromRide) Nav.Ride else Nav.Mine
            Nav.History, Nav.More -> Nav.Mine
            Nav.OfflineMap -> Nav.More
            Nav.EditCards -> Nav.Ride
            Nav.Achievements, Nav.YearlyReport, Nav.Profile -> Nav.Mine
            else -> Nav.Ride
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(Modifier.weight(1f)) {
            when (val n = nav) {
                Nav.Ride -> RideScreen(
                    app,
                    onStart = {
                        val fine = ContextCompat.checkSelfPermission(
                            ctx, Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        if (fine == PackageManager.PERMISSION_GRANTED) {
                            try {
                                ContextCompat.startForegroundService(
                                    ctx, serviceIntent(ctx, RideRecordingService.ACTION_START)
                                )
                            } catch (e: Exception) {
                                com.altivane.app.domain.AppLog.e("Ride", "start service failed", e)
                                android.widget.Toast.makeText(
                                    ctx, "启动失败：${e.message}", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            activity?.requestPerms()
                        }
                    },
                    onServiceAction = { action ->
                        try {
                            ctx.startService(serviceIntent(ctx, action))
                        } catch (e: Exception) {
                            com.altivane.app.domain.AppLog.e("Ride", "service action $action failed", e)
                        }
                    },
                    onViewRide = { nav = Nav.Detail(it) },
                    onOpenCard = { nav = Nav.CardDetail(it) },
                    onPhotoWaypoint = { activity?.launchCamera() },
                    onEditCards = { nav = Nav.EditCards },
                    onOpenRoutes = {
                        routesFromRide = true
                        nav = Nav.Routes
                    },
                    gridState = rideGrid,
                    fullMap = rideFullMap,
                    onFullMapChange = { rideFullMap = it }
                )
                Nav.Mine -> MineScreen(
                    app,
                    onOpenHistory = { nav = Nav.History },
                    onOpenRoutes = {
                        routesFromRide = false
                        nav = Nav.Routes
                    },
                    onOpenMore = { nav = Nav.More },
                    onOpenAchievements = { nav = Nav.Achievements },
                    onOpenReport = { nav = Nav.YearlyReport },
                    onOpenProfile = { nav = Nav.Profile }
                )
                Nav.History -> HistoryScreen(app, onOpen = { nav = Nav.Detail(it) }, onBack = { nav = Nav.Mine })
                Nav.More -> com.altivane.app.ui.SettingsScreen(
                    app, onBack = { nav = Nav.Mine },
                    onOpenOfflineMap = { nav = Nav.OfflineMap }
                )
                is Nav.Detail -> DetailScreen(
                    app, n.id,
                    onBack = { nav = Nav.History },
                    onShare = { shareFromTrack = false; nav = Nav.Share(n.id) }
                )
                is Nav.Share -> ShareScreen(
                    app, n.id,
                    onBack = { nav = if (shareFromTrack) Nav.CardDetail(CardType.TRACK) else Nav.Detail(n.id) }
                )
                is Nav.CardDetail -> com.altivane.app.ui.CardDetailScreen(
                    app, n.type,
                    onBack = { nav = Nav.Ride },
                    onShareRide = { shareFromTrack = n.type == CardType.TRACK; nav = Nav.Share(it) }
                )
                Nav.Routes -> com.altivane.app.ui.RoutesScreen(
                    onBack = { nav = if (routesFromRide) Nav.Ride else Nav.Mine },
                    onOpen = { nav = Nav.RouteDetail(it) }
                )
                is Nav.RouteDetail -> com.altivane.app.ui.RouteDetailScreen(
                    n.idx, onBack = { nav = Nav.Routes }
                )
                Nav.EditCards -> com.altivane.app.ui.EditCardsScreen(
                    app, onBack = { nav = Nav.Ride }
                )
                Nav.Achievements -> com.altivane.app.ui.AchievementsScreen(
                    app, onBack = { nav = Nav.Mine }
                )
                Nav.YearlyReport -> com.altivane.app.ui.YearlyReportScreen(
                    app, onBack = { nav = Nav.Mine }
                )
                Nav.Profile -> com.altivane.app.ui.ProfileScreen(
                    onBack = { nav = Nav.Mine }
                )
                Nav.OfflineMap -> com.altivane.app.ui.OfflineMapScreen(
                    app, onBack = { nav = Nav.More }
                )
            }
        }
        if ((nav is Nav.Ride || nav is Nav.Mine) && !rideFullMap) {
            GlassBottomBar(
                tab = if (nav is Nav.Ride) 0 else 1,
                onSelect = { nav = if (it == 0) Nav.Ride else Nav.Mine }
            )
        }
    }
}

/** 底部玻璃导航条 */
@Composable
fun GlassBottomBar(tab: Int, onSelect: (Int) -> Unit) {
    val dark = isDark()
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .glassCard(dark, null)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomItem(stringResource(R.string.nav_ride), Icons.Filled.PlayArrow, 0, tab, onSelect, Modifier.weight(1f), AvColors.cyan)
        BottomItem(stringResource(R.string.nav_mine), Icons.Filled.Person, 1, tab, onSelect, Modifier.weight(1f), AvColors.green)
    }
}

@Composable
private fun BottomItem(
    label: String,
    icon: ImageVector,
    index: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color
) {
    val on = selected == index
    Column(
        modifier.clickable { onSelect(index) },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon, null,
            tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
