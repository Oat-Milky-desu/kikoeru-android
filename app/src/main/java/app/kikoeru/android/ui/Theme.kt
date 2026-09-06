package app.kikoeru.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF4B6096), onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF), onPrimaryContainer = Color(0xFF10224D),
    secondary = Color(0xFF585F73), secondaryContainer = Color(0xFFDDE2F5),
    tertiary = Color(0xFF765572), surface = Color(0xFFFAF8FF), background = Color(0xFFFAF8FF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB3C5FF), onPrimary = Color(0xFF1B3063),
    primaryContainer = Color(0xFF33487C), secondary = Color(0xFFC1C6DC),
    tertiary = Color(0xFFE5BBDD), surface = Color(0xFF121318), background = Color(0xFF121318),
)

@Composable
fun KikoeruTheme(mode: String, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = mode == "dark" || mode == "system" && isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = colors, content = content)
}
