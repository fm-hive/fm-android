package lan.fmnode.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFF9ECE6A),
    background = Color(0xFF0F1320),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF161B2B),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF232A3D),
    onSurfaceVariant = Color(0xFFB6BFD3),
    error = Color(0xFFF7768E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    secondary = Color(0xFF2F9E44),
    background = Color(0xFFF8F9FB),
    surface = Color(0xFFFFFFFF),
)

/** FM Client theme; follows the system light/dark setting. */
@Composable
fun FmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
