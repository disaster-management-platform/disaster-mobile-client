package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CommandBlueLight,
    onPrimary = Color.White,
    primaryContainer = CommandBlueDark,
    onPrimaryContainer = Color.White,
    secondary = CommandSecondary,
    onSecondary = Color.White,
    surface = Color(0xFF121B28),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E2A3A),
    onSurfaceVariant = Color(0xFFD0D7E1)
)

private val LightColorScheme = lightColorScheme(
    primary = CommandBlue,
    onPrimary = Color.White,
    primaryContainer = CommandBlue,
    onPrimaryContainer = Color.White,
    secondary = CommandSecondary,
    onSecondary = Color.White,
    surface = CommandBlueSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF334155)
)

@Composable
fun DisastermobileclientTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
