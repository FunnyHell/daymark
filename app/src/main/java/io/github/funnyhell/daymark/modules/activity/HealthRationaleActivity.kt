package io.github.funnyhell.daymark.modules.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.ui.theme.DaymarkTheme

/**
 * Explains which Health Connect data Daymark reads and why. Health Connect opens it from its permission screen
 * and from the app's entry in its settings; it is required for the permissions to be granted.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DaymarkTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text(stringResource(R.string.activity_rationale_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.activity_rationale_body), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 24.dp)) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
        }
    }
}
