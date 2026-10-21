package com.altivane.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altivane.app.MainActivity
import org.json.JSONObject

/**
 * 用户资料：SharedPreferences JSON 存储（不动 Room schema）。
 * state 为 Compose 状态，编辑/头像变更后所有引用处自动刷新。
 */
object ProfileStore {
    private const val PREF = "altivane_profile"

    data class UserProfile(
        val name: String = "",
        val gender: String = "",    // "M" / "F" / ""
        val age: Int = 0,
        val heightCm: Int = 0,
        val weightKg: Double = 0.0,
        val blood: String = "",     // A / B / AB / O
        val avatarPath: String = ""
    )

    val state = mutableStateOf(UserProfile())

    fun init(ctx: Context) {
        state.value = read(ctx)
    }

    private fun read(ctx: Context): UserProfile {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("json", null)
            ?: return UserProfile()
        return try {
            val j = JSONObject(raw)
            UserProfile(
                name = j.optString("name", ""),
                gender = j.optString("gender", ""),
                age = j.optInt("age", 0),
                heightCm = j.optInt("heightCm", 0),
                weightKg = j.optDouble("weightKg", 0.0),
                blood = j.optString("blood", ""),
                avatarPath = j.optString("avatarPath", "")
            )
        } catch (_: Exception) {
            UserProfile()
        }
    }

    fun save(ctx: Context, p: UserProfile) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(
                "json",
                JSONObject()
                    .put("name", p.name)
                    .put("gender", p.gender)
                    .put("age", p.age)
                    .put("heightCm", p.heightCm)
                    .put("weightKg", p.weightKg)
                    .put("blood", p.blood)
                    .put("avatarPath", p.avatarPath)
                    .toString()
            ).apply()
        state.value = p
    }

    fun setAvatar(ctx: Context, path: String) {
        save(ctx, state.value.copy(avatarPath = path))
    }
}

/**
 * 默认矢量头像：男（平短发+方肩）/ 女（两侧长发+刘海），白色人形 + 品牌色底。
 */
@Composable
fun DefaultAvatar(gender: String, modifier: Modifier = Modifier) {
    val female = gender == "F"
    val bg = if (female) Color(0xFFFF6B81) else AvColors.teal
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val base = Path().apply { addOval(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)) }
        clipPath(base) {
            drawRect(bg)
            val white = Color.White
            // 肩部
            drawCircle(white, radius = w * 0.30f, center = Offset(w * 0.50f, h * 0.94f))
            // 脖子
            drawRoundRect(
                white,
                topLeft = Offset(w * 0.44f, h * 0.52f),
                size = androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.16f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f)
            )
            if (female) {
                // 两侧长发：从头两侧垂到肩
                val hair = Path().apply {
                    moveTo(w * 0.30f, h * 0.30f)
                    cubicTo(w * 0.22f, h * 0.44f, w * 0.22f, h * 0.62f, w * 0.28f, h * 0.72f)
                    lineTo(w * 0.40f, h * 0.72f)
                    lineTo(w * 0.40f, h * 0.46f)
                    close()
                }
                drawPath(hair, white)
                val hair2 = Path().apply {
                    moveTo(w * 0.70f, h * 0.30f)
                    cubicTo(w * 0.78f, h * 0.44f, w * 0.78f, h * 0.62f, w * 0.72f, h * 0.72f)
                    lineTo(w * 0.60f, h * 0.72f)
                    lineTo(w * 0.60f, h * 0.46f)
                    close()
                }
                drawPath(hair2, white)
            }
            // 头
            drawCircle(white, radius = w * 0.17f, center = Offset(w * 0.50f, h * 0.40f))
            if (female) {
                // 刘海：底色圆弧盖住头顶上缘
                drawCircle(bg, radius = w * 0.175f, center = Offset(w * 0.50f, h * 0.325f))
                drawCircle(white, radius = w * 0.15f, center = Offset(w * 0.50f, h * 0.425f))
            } else {
                // 男：平短发——头顶一条底色平切
                drawRect(
                    bg,
                    topLeft = Offset(w * 0.30f, h * 0.20f),
                    size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.055f)
                )
            }
        }
    }
}

/**
 * 个人资料编辑页：头像（拍照/相册/默认男女）+ 姓名/性别/年龄/身高/体重/血型。
 */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val p = ProfileStore.state.value
    val dark = isDark()

    // 编辑弹窗：key -> 当前值
    var editKey by remember { mutableStateOf<String?>(null) }

    // 头像操作弹窗
    var showAvatarMenu by remember { mutableStateOf(false) }

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
                Text("个人资料", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text("头像与身体数据 · 仅存本机",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 头像卡
        Box(
            Modifier
                .fillMaxWidth()
                .glassCard(dark, AvColors.cyan)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clickable { showAvatarMenu = true }
                ) {
                    AvatarImage(p, Modifier.fillMaxSize())
                    // 相机角标
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(AvColors.cyan)
                            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(Modifier.size(14.dp)) {
                            // 简笔相机：机身圆角矩形 + 镜头圆
                            drawRoundRect(
                                Color(0xFF04222B),
                                topLeft = Offset(size.width * 0.08f, size.height * 0.22f),
                                size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.62f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.2f)
                            )
                            drawCircle(
                                AvColors.cyan,
                                radius = size.width * 0.17f,
                                center = Offset(size.width * 0.5f, size.height * 0.53f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击更换头像",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 字段列表
        val rows = listOf(
            Triple("姓名", p.name.ifEmpty { "未设置" }, "name"),
            Triple(
                "性别",
                when (p.gender) { "M" -> "男"; "F" -> "女"; else -> "未设置" },
                "gender"
            ),
            Triple("年龄", if (p.age > 0) "${p.age} 岁" else "未设置", "age"),
            Triple("身高", if (p.heightCm > 0) "${p.heightCm} cm" else "未设置", "height"),
            Triple(
                "体重",
                if (p.weightKg > 0) "%.1f kg".format(p.weightKg) else "未设置",
                "weight"
            ),
            Triple("血型", if (p.blood.isNotEmpty()) p.blood else "未设置", "blood")
        )
        rows.forEach { (title, value, key) ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassCard(dark, null)
                    .clickable { editKey = key }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (value == "未设置") MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    // 头像菜单
    if (showAvatarMenu) {
        AlertDialog(
            onDismissRequest = { showAvatarMenu = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAvatarMenu = false }) { Text("取消") } },
            title = { Text("设置头像") },
            text = {
                Column {
                    TextButton(onClick = {
                        showAvatarMenu = false
                        activity?.launchAvatarCamera()
                    }) { Text("拍照") }
                    TextButton(onClick = {
                        showAvatarMenu = false
                        activity?.launchAvatarPick()
                    }) { Text("从相册选择") }
                    TextButton(onClick = {
                        showAvatarMenu = false
                        ProfileStore.save(ctx, p.copy(avatarPath = ""))
                    }) { Text("清除头像") }
                }
            }
        )
    }

    // 字段编辑
    editKey?.let { key ->
        val cur = when (key) {
            "name" -> p.name
            "age" -> if (p.age > 0) p.age.toString() else ""
            "height" -> if (p.heightCm > 0) p.heightCm.toString() else ""
            "weight" -> if (p.weightKg > 0) "%.1f".format(p.weightKg) else ""
            else -> ""
        }
        if (key == "gender" || key == "blood") {
            val options = if (key == "gender") listOf("M" to "男", "F" to "女")
            else listOf("A" to "A 型", "B" to "B 型", "AB" to "AB 型", "O" to "O 型")
            var sel by remember(key) { mutableStateOf(if (key == "gender") p.gender else p.blood) }
            AlertDialog(
                onDismissRequest = { editKey = null },
                confirmButton = {
                    TextButton(onClick = {
                        ProfileStore.save(
                            ctx,
                            if (key == "gender") p.copy(gender = sel) else p.copy(blood = sel)
                        )
                        editKey = null
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editKey = null }) { Text("取消") } },
                title = { Text(if (key == "gender") "性别" else "血型") },
                text = {
                    Column {
                        options.forEach { (v, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { sel = v }
                            ) {
                                RadioButton(selected = sel == v, onClick = { sel = v })
                                Text(label)
                            }
                        }
                    }
                }
            )
        } else {
            var input by remember(key) { mutableStateOf(cur) }
            val title = when (key) {
                "name" -> "姓名"; "age" -> "年龄（岁）"
                "height" -> "身高（cm）"; else -> "体重（kg）"
            }
            AlertDialog(
                onDismissRequest = { editKey = null },
                confirmButton = {
                    TextButton(onClick = {
                        val np = when (key) {
                            "name" -> p.copy(name = input.trim())
                            "age" -> p.copy(age = input.trim().toIntOrNull()?.coerceIn(1, 120) ?: 0)
                            "height" -> p.copy(heightCm = input.trim().toIntOrNull()?.coerceIn(50, 250) ?: 0)
                            else -> p.copy(weightKg = input.trim().toDoubleOrNull()?.coerceIn(20.0, 300.0) ?: 0.0)
                        }
                        ProfileStore.save(ctx, np)
                        editKey = null
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { editKey = null }) { Text("取消") } },
                title = { Text(title) },
                text = {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { v ->
                            input = if (key == "name") v
                            else v.filter { it.isDigit() || it == '.' }
                        },
                        singleLine = true
                    )
                }
            )
        }
    }
}

/** 头像渲染：自定义照片优先，否则中性剪影占位 */
@Composable
fun AvatarImage(p: ProfileStore.UserProfile, modifier: Modifier = Modifier) {
    if (p.avatarPath.isNotEmpty()) {
        val bmp = rememberPhotoBitmap(p.avatarPath, targetPx = 256)
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bmp, null, modifier.clip(CircleShape), contentScale = ContentScale.Crop
            )
            return
        }
    }
    // 中性占位：浅底 + 单色人形剪影（无男女之分）
    val c = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Box(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(58.dp)) {
            drawCircle(c, radius = size.width * 0.15f, center = Offset(size.width * 0.5f, size.height * 0.30f))
            val body = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.94f)
                quadraticBezierTo(size.width * 0.5f, size.height * 0.44f, size.width * 0.78f, size.height * 0.94f)
                close()
            }
            drawPath(body, c)
        }
    }
}
