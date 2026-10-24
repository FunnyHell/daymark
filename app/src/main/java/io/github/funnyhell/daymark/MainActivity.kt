package io.github.funnyhell.daymark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.funnyhell.daymark.ui.DaymarkRoot
import io.github.funnyhell.daymark.ui.theme.DaymarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DaymarkApplication).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            DaymarkTheme(themeMode = s?.themeMode ?: io.github.funnyhell.daymark.data.repo.ThemeMode.SYSTEM, dynamicColor = s?.dynamicColor ?: true) {
                if (s != null) DaymarkRoot(onboardingDone = s.onboardingDone)
            }
        }
    }
}
