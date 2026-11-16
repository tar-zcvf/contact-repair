package app.contactrepair.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

private val SeedColor = Color(0xFF0B57D0)

private val LightColors = lightColorScheme(
    primary = SeedColor,
    onPrimary = Color.White,
    secondary = Color(0xFF4A6572),
    background = Color(0xFFFBFBFB),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color.Black,
    secondary = Color(0xFF93C5FD),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

@Composable
fun ContactRepairTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
