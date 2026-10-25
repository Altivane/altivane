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

    /** 应用内语言包装（设置页切换后 recreate 生效） */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.altivane.app.domain.AppLang.wrap(newBase))
    }

    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>
    private var pendingMediaUri: android.net.Uri? = null
    private var pendingMediaLoc: Pair<Double, Double>? = null
    private var pendingAvatar: File? = null
    private var configReceiver: android.content.BroadcastReceiver? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val uri = pendingMediaUri ?: return@registerForActivityResult
            val app = application as RideApp
            val latLon = pendingMediaLoc
            // 释放 IS_PENDING：原片（含 EXIF 方向/GPS/动态视频数据）立即在系统相册可见
            try {
                contentResolver.update(
                    uri,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null, null
                )
            } catch (_: Exception) {
            }
            var done = false
            fun finish(place: String) {
                if (done) return
                done = true
                val path = processWaypointPhoto(uri, place, latLon)
                if (path != null) {
                    val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date(System.currentTimeMillis()))
                    app.session.addWaypoint("M", place.ifBlank { getString(R.string.wp_tag) + " $hhmm" }, path)
                }
            }
            val last = app.session.points.value.lastOrNull()
            if (last != null) {
                // 逆地理查位置名（POI 优先，如「黄山迎客松」），4s 超时兜底用时间
                reverseGeo(last.lat, last.lon) { finish(it) }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish("") }, 4000)
            } else {
                finish("")
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

    /**
     * 打卡照片处理（v0.8.46 重写）：
     * ① 读 MediaStore 原片字节 → ② 提取动态照片内嵌视频（GCamera/Huawei XMP MicroVideo，存同名 .mp4）
     * ③ EXIF 方向转正解码（旧流程 BitmapFactory 无视方向 = 照片横躺的根因）
     * ④ 水印底部条 + 文字靠左 → ⑤ 存 app 打卡目录 + 写 GPS EXIF（地理信息入库，不上画面）。
     * 系统相册里的原片保持相机输出原样（方向/动态照片/GPS 完整）。
     */
    private fun processWaypointPhoto(
        uri: android.net.Uri, place: String, latLon: Pair<Double, Double>?
    ): String? = try {
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val dir = File(getExternalFilesDir("Pictures"), "waypoints").apply { mkdirs() }
        val base = "wp_${System.currentTimeMillis()}"
        // ① 动态视频提取（best-effort）：XMP 里 GCamera:MicroVideoOffset = 文件尾段视频长度
        try {
            val xmpEnd = findAscii(raw, "</x:xmpmeta>")
            if (xmpEnd > 0) {
                val xmp = String(raw, 0, minOf(raw.size, xmpEnd + 400), Charsets.ISO_8859_1)
                val off = Regex("GCamera:MicroVideoOffset=\"(\\d+)\"")
                    .find(xmp)?.groupValues?.get(1)?.toIntOrNull()
                if (off != null && off in 1024..raw.size) {
                    File(dir, "$base.mp4").writeBytes(raw.copyOfRange(raw.size - off, raw.size))
                }
            }
        } catch (_: Exception) {
        }
        // ② EXIF 方向
        val orientation = android.media.ExifInterface(java.io.ByteArrayInputStream(raw))
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        // ③ 降采样解码 + 转正
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val m = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        }
        val oriented = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (oriented != bmp) bmp.recycle()
        // ④ 水印：底部半透明条 + 左对齐文字
        val out = if (oriented.isMutable) oriented
        else oriented.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
        val text = if (place.isBlank()) "Altivane · $ts" else "$place · $ts"
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = out.width * 0.036f
            setShadowLayer(4f, 0f, 0f, 0x88000000.toInt())
        }
        val lineH = p.textSize * 1.7f
        val pad = out.width * 0.035f
        val bg = android.graphics.Paint().apply { color = 0x59000000 }
        c.drawRect(0f, out.height - lineH, out.width.toFloat(), out.height.toFloat(), bg)
        c.drawText(text, pad, out.height - lineH * 0.30f, p)
        val f = File(dir, "$base.jpg")
        java.io.FileOutputStream(f).use {
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }
        // ⑤ GPS EXIF 写入（纬度/经度 DMS 格式）
        if (latLon != null) {
            try {
                val e2 = android.media.ExifInterface(f.absolutePath)
                e2.setAttribute(
                    android.media.ExifInterface.TAG_GPS_LATITUDE,
                    toDms(latLon.first)
                )
                e2.setAttribute(
                    android.media.ExifInterface.TAG_GPS_LATITUDE_REF,
                    if (latLon.first >= 0) "N" else "S"
                )
                e2.setAttribute(
                    android.media.ExifInterface.TAG_GPS_LONGITUDE,
                    toDms(latLon.second)
                )
                e2.setAttribute(
                    android.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
                    if (latLon.second >= 0) "E" else "W"
                )
                e2.saveAttributes()
            } catch (_: Exception) {
            }
        }
        out.recycle()
        f.absolutePath
    } catch (e: Exception) {
        com.altivane.app.domain.AppLog.e("Wp", "photo process", e)
        null
    }

    private fun findAscii(a: ByteArray, s: String): Int {
        val pat = s.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..a.size - pat.size) {
            for (j in pat.indices) if (a[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun toDms(v: Double): String {
        val d = kotlin.math.abs(v)
        val deg = d.toInt()
        val minF = (d - deg) * 60
        val min = minF.toInt()
        val sec = (minF - min) * 60
        return "$deg/1,$min/1,${(sec * 1000).toInt()}/1000"
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

    /**
     * 徒步拍照打卡（v0.8.46）：照片直接写入 MediaStore Pictures/Altivane——
     * 系统相册立即可见（含动态照片模式输出的原片），app 侧再处理出带水印的工作副本。
     */
    fun launchCamera() {
        val loc = (application as RideApp).session.points.value.lastOrNull()
        pendingMediaLoc = loc?.let { it.lat to it.lon }
        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                "Altivane_wp_${System.currentTimeMillis()}.jpg"
            )
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Altivane")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            pendingMediaLoc?.let { ll ->
                put(android.provider.MediaStore.Images.ImageColumns.LATITUDE, ll.first)
                put(android.provider.MediaStore.Images.ImageColumns.LONGITUDE, ll.second)
            }
        }
        val uri = contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: run {
            // MediaStore 不可用兜底：私有文件（FileProvider 可读）
            val dir = File(getExternalFilesDir("Pictures"), "waypoints").apply { mkdirs() }
            FileProvider.getUriForFile(
                this, "$packageName.fileprovider",
                File(dir, "wp_${System.currentTimeMillis()}.jpg")
            )
        }
        pendingMediaUri = uri
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
    data class LocalRoute(val id: Long) : Nav
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
            is Nav.LocalRoute -> Nav.Routes
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
                    app,
                    onBack = { nav = if (routesFromRide) Nav.Ride else Nav.Mine },
                    onOpen = { nav = Nav.RouteDetail(it) },
                    onOpenLocal = { nav = Nav.LocalRoute(it) }
                )
                is Nav.RouteDetail -> com.altivane.app.ui.RouteDetailScreen(
                    n.idx, onBack = { nav = Nav.Routes }
                )
                is Nav.LocalRoute -> com.altivane.app.ui.LocalRouteDetailScreen(
                    app, n.id, onBack = { nav = Nav.Routes }
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
