package com.altivane.app.ui

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 主题模式：跟随系统 / 浅色 / 深色 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

/** 主题状态（SharedPreferences 持久化，设置页切换即时生效） */
object AppTheme {
    private const val PREF = "altivane_theme"
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    fun init(ctx: Context) {
        val name = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("mode", null)
        mode = runCatching { ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(ctx: Context, m: ThemeMode) {
        mode = m
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("mode", m.name).apply()
    }

    /** 当前是否深色：设置优先，SYSTEM 跟随系统 */
    @Composable
    fun dark(): Boolean = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/**
 * Altivane「暗夜竞速」× MD3 Expressive 视觉体系：
 * 深空黑 × 电光青 × 荧光绿；浅色模式为「日光竞速」。
 * Expressive：加粗的显示级字体阶梯、大圆角形状分级、弹性（spring）动效。
 */
object AvColors {
    val cyan = Color(0xFF22D3EE)     // 主 accent / 速度 / 时长
    val blue = Color(0xFF38BDF8)     // 里程
    val green = Color(0xFFA3E635)    // 荧光绿 GO / 轨迹
    val yellow = Color(0xFFFDE047)   // 配速 / 打卡
    val orange = Color(0xFFF0A63C)   // 消耗
    val pink = Color(0xFFFF6B81)     // 心率
    val purple = Color(0xFF9D7BFF)   // 踏频
    val violet = Color(0xFF7DD3FC)   // 气压（天空蓝）
    val teal = Color(0xFF2DD4BF)     // 指南针
    val fuchsia = Color(0xFFE879F9)  // 海拔图
    val red = Color(0xFFFF5252)      // 最高时速 / 指北针 / SOS
    val deepInk = Color(0xFF0D1117)  // 彩色底上的深色文字

    /** 卡片底色：暗夜=深蓝灰卡，日光=纯白卡 */
    @Composable
    fun card(): Color = if (AppTheme.dark()) Color(0xFF1A2230) else Color.White

    /** 强调色用于文字/描边时：浅色模式取同族深色变体保证可读性 */
    @Composable
    fun onColor(c: Color): Color = if (AppTheme.dark()) c else when (c) {
        cyan -> Color(0xFF0E7490)
        blue -> Color(0xFF0369A1)
        green -> Color(0xFF4D7C0F)
        yellow -> Color(0xFFA16207)
        orange -> Color(0xFFB45309)
        pink -> Color(0xFFBE123C)
        purple -> Color(0xFF6D28D9)
        violet -> Color(0xFF0369A1)
        teal -> Color(0xFF0F766E)
        fuchsia -> Color(0xFFA21CAF)
        red -> Color(0xFFB91C1C)
        else -> Color(0xFF0369A1)
    }

    /** 彩虹轨迹：速度 → 色相（慢=紫蓝 → 快=红），max 按运动类型定标 */
    fun rainbow(speedKmh: Double, maxKmh: Double): Color {
        val t = (speedKmh / maxKmh.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val hue = 270.0 - t * 270.0
        return Color.hsv(hue.toFloat(), 0.80f, 0.98f)
    }

    fun rainbowMax(sport: com.altivane.app.domain.SportType): Double = when (sport) {
        com.altivane.app.domain.SportType.RIDE -> 35.0
        com.altivane.app.domain.SportType.HIKE -> 10.0
    }
}

/** MD3 Expressive 动效：弹性 spring 曲线（M3E emphasized / bouncy 近似） */
object AvMotion {
    fun <T> emphasized(): SpringSpec<T> =
        spring(dampingRatio = 0.80f, stiffness = 380f)

    fun <T> bouncy(): SpringSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 320f)

    fun <T> snappy(): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
}

private val LightScheme = lightColorScheme(
    background = Color(0xFFF1F3F0),
    onBackground = Color(0xFF10171D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10171D),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5C6B78),
    outline = Color(0xFFE2E5E1),
    primary = Color(0xFF0E7490),
    onPrimary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF6D28D9),
    inverseSurface = Color(0xFF10171D),
    inverseOnSurface = Color(0xFFF1F3F0)
)

private val DarkScheme = darkColorScheme(
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFF2F5FA),
    surface = Color(0xFF1A2230),
    onSurface = Color(0xFFF2F5FA),
    surfaceVariant = Color(0xFF1A2230),
    onSurfaceVariant = Color(0xFF98A2B6),
    outline = Color(0xFF2A3245),
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF04222B),
    tertiary = Color(0xFF9D7BFF),
    inverseSurface = Color(0xFFF2F5FA),
    inverseOnSurface = Color(0xFF0D1117)
)

/** Expressive 字体阶梯：显示级加粗加大、标题重、正文清晰 */
private val RideTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 88.sp, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Black, fontSize = 64.sp, letterSpacing = (-1.5).sp),
    displaySmall = TextStyle(fontWeight = FontWeight.Black, fontSize = 48.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp)
)

/** Expressive 形状分级：小 12 / 中 20 / 大 28 / 特大 32 */
private val RideShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
)

@Composable
fun RideTheme(content: @Composable () -> Unit) {
    val dark = AppTheme.dark()
    val scheme = if (dark) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = RideTypography,
        shapes = RideShapes
    ) {
        // 根修复：MaterialTheme 不提供 LocalContentColor（默认黑色），
        // 无显式颜色的 Text 在暗色卡上会发黑 —— 这里统一提供前景色。
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            content()
        }
    }
}
