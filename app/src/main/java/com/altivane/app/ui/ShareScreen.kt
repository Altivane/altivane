package com.altivane.app.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.altivane.app.R
import com.altivane.app.RideApp
import com.altivane.app.data.PointEntity
import com.altivane.app.data.RideEntity
import com.altivane.app.data.WaypointEntity
import com.altivane.app.domain.SportType
import com.altivane.app.share.ShareImageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter

@Composable
fun ShareScreen(app: RideApp, id: Long, onBack: () -> Unit) {
    var ride by remember { mutableStateOf<RideEntity?>(null) }
    var pts by remember { mutableStateOf<List<PointEntity>>(emptyList()) }
    var wps by remember { mutableStateOf<List<WaypointEntity>>(emptyList()) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    // 地图底图快照 + 全量点投影（pixelForLatLng，快照位图像素坐标 900x800）
    var mapBmp by remember { mutableStateOf<Bitmap?>(null) }
    var projPts by remember { mutableStateOf<List<FloatArray>?>(null) }
    var projWps by remember { mutableStateOf<List<FloatArray>?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val dark = isDark()

    LaunchedEffect(id) {
        ride = app.db.dao().ride(id)
        pts = app.db.dao().points(id)
        wps = app.db.dao().waypoints(id)
    }

    // 地图底图：MapSnapshotter 离线渲染（Liberty 在线样式或已缓存离线包），失败回退纯色海报
    DisposableEffect(ride, pts) {
        val r = ride
        val sn: MapSnapshotter? = if (r != null && pts.size >= 2) {
            MapLibre.getInstance(ctx)
            val latMin = pts.minOf { it.lat }; val latMax = pts.maxOf { it.lat }
            val lonMin = pts.minOf { it.lon }; val lonMax = pts.maxOf { it.lon }
            val s = MapSnapshotter(
                ctx,
                MapSnapshotter.Options(ShareImageRenderer.MAP_W, ShareImageRenderer.MAP_H)
                    .withStyle(MAP_STYLE_URL)
                    .withRegion(LatLngBounds.from(latMax, lonMax, latMin, lonMin))
                    .withPadding(30, 30, 30, 30)
                    .withLogo(false)
                    .withAttribution(false)
                    .withPixelRatio(1f)
            )
            s.start(
                { snap ->
                    mapBmp = snap.bitmap
                    projPts = pts.map { p ->
                        val f = snap.pixelForLatLng(LatLng(p.lat, p.lon))
                        floatArrayOf(f.x, f.y)
                    }
                    projWps = wps.map { w ->
                        val f = snap.pixelForLatLng(LatLng(w.lat, w.lon))
                        floatArrayOf(f.x, f.y)
                    }
                },
                object : MapSnapshotter.ErrorHandler {
                    override fun onError(error: String) {
                        com.altivane.app.domain.AppLog.e("Share", "snapshot: $error")
                    }
                }
            )
            s
        } else null
        onDispose { try { sn?.cancel() } catch (_: Exception) {} }
    }

    val sportName = stringResource(
        try {
            SportType.valueOf(ride?.sportType ?: "RIDE").labelRes
        } catch (_: Exception) { R.string.sport_ride }
    )

    LaunchedEffect(ride, pts, wps, mapBmp, projPts, projWps) {
        val r = ride
        if (r != null) {
            bmp = withContext(Dispatchers.Default) {
                ShareImageRenderer.render(pts, wps, r, mapBmp, projPts, projWps, dark, sportName)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                "分享",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            bmp?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "分享图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .glassCard(dark)
                    .clickable {
                        val b = bmp ?: return@clickable
                        scope.launch(Dispatchers.IO) {
                            val ok = saveToGallery(ctx, b)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    ctx,
                                    if (ok) "已保存到相册 Pictures/Altivane" else "保存失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "保存到相册",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .glassCard(dark)
                    .clickable {
                        val b = bmp ?: return@clickable
                        scope.launch(Dispatchers.IO) {
                            saveToGallery(ctx, b)
                            val f = saveShareFile(ctx, b)
                            withContext(Dispatchers.Main) {
                                shareFile(ctx, f, "image/png")
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "系统分享",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

private fun saveToGallery(ctx: Context, b: Bitmap): Boolean {
    return try {
        val name = "altivane_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Altivane")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            b.compress(Bitmap.CompressFormat.PNG, 95, os)
        } ?: return false
        true
    } catch (_: Exception) {
        false
    }
}

private fun saveShareFile(ctx: Context, b: Bitmap): java.io.File {
    val dir = java.io.File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ride")
    if (!dir.exists()) dir.mkdirs()
    val f = java.io.File(dir, "altivane_share.png")
    f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 95, it) }
    return f
}
