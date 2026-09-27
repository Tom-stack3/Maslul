package com.maslul.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maslul.app.data.ThemeMode
import com.maslul.app.data.TransitMode

val Brand = Color(0xFF1F6FEB)

/** Semantic colours beyond Material's scheme. */
@Immutable
data class Extra(
    val live: Color,
    val late: Color,
    val early: Color,
    val walk: Color,
    val subtle: Color,
    val divider: Color,
    val isDark: Boolean,
)

val LocalExtra = staticCompositionLocalOf {
    Extra(Color(0xFF12A150), Color(0xFFE5484D), Color(0xFF1F6FEB), Color(0xFF8A94A6), Color(0xFF6B7280), Color(0x14000000), false)
}

object ModeColors {
    val Bus = Color(0xFF12A150)
    val Train = Color(0xFF1F4E9E)
    val LightRail = Color(0xFFE5484D)
    val Metro = Color(0xFF8E4EC6)
    val Cable = Color(0xFF0E9AA7)
    val Ferry = Color(0xFF0091FF)
    val Other = Color(0xFF6B7280)

    fun of(mode: TransitMode): Color = when (mode) {
        TransitMode.BUS -> Bus
        TransitMode.TRAIN -> Train
        TransitMode.LIGHT_RAIL -> LightRail
        TransitMode.METRO -> Metro
        TransitMode.CABLE_CAR -> Cable
        TransitMode.FERRY -> Ferry
        TransitMode.WALK, TransitMode.OTHER -> Other
    }
}

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EDFF),
    onPrimaryContainer = Color(0xFF0B2E6B),
    secondary = Brand,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EDFF),
    onSecondaryContainer = Color(0xFF0B2E6B),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF0F1115),
    surface = Color.White,
    onSurface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFECEEF1),
    surfaceContainerHighest = Color(0xFFE5E7EB),
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainerLowest = Color.White,
    outline = Color(0xFFD0D5DD),
    outlineVariant = Color(0xFFE4E7EC),
    error = Color(0xFFE5484D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF00214F),
    primaryContainer = Color(0xFF16325C),
    onPrimaryContainer = Color(0xFFD7E5FF),
    secondary = Color(0xFF6EA8FF),
    onSecondary = Color(0xFF00214F),
    secondaryContainer = Color(0xFF16325C),
    onSecondaryContainer = Color(0xFFD7E5FF),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF22262E),
    onSurfaceVariant = Color(0xFF9AA3B2),
    surfaceContainer = Color(0xFF1C2027),
    surfaceContainerHigh = Color(0xFF232830),
    surfaceContainerHighest = Color(0xFF2B313A),
    surfaceContainerLow = Color(0xFF15181D),
    surfaceContainerLowest = Color(0xFF0F1115),
    outline = Color(0xFF3A414C),
    outlineVariant = Color(0xFF2A3038),
    error = Color(0xFFFF6B6B),
)

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val Numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun MaslulTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val extra = if (dark) {
        Extra(Color(0xFF3DD68C), Color(0xFFFF6B6B), Color(0xFF6EA8FF), Color(0xFF7D8696), Color(0xFF9AA3B2), Color(0x1FFFFFFF), true)
    } else {
        Extra(Color(0xFF12A150), Color(0xFFE5484D), Color(0xFF1F6FEB), Color(0xFF8A94A6), Color(0xFF6B7280), Color(0x14000000), false)
    }
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp),
            ),
            content = content,
        )
    }
}
