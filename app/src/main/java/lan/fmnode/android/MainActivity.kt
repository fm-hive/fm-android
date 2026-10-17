package lan.fmnode.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lan.fmnode.android.ui.StartScreen
import lan.fmnode.android.ui.theme.FmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FmTheme {
                StartScreen()
            }
        }
    }
}
