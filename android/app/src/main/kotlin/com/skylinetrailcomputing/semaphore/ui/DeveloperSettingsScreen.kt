package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The **Developer** surface ([5d], #50) — the dev-ish settings split out of the
 * regular-user [SettingsScreen] by 6a-4 (#72). Hosts the Developer-mode toggle,
 * which gates the Learn screen's skeleton overlay + raw per-frame readout (the
 * maintainer smoke surface). It sits one level below the regular-user Settings so a
 * beta tester meets assist/About config first, not dev internals. The toggle's
 * state is hoisted to `SemaphoreApp`, which persists each change via [AppSettings]
 * and passes the current value down — so it survives launches and the Learn screen
 * sees the same flag. Contract knobs (angle-tolerance, commit-hold) are
 * deliberately out of scope (FR7). The iOS twin is `DeveloperSettingsView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    developerMode: Boolean,
    onDeveloperModeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Developer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                    ),
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Developer mode", color = Color.White, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Shows the skeleton overlay and raw per-frame readout in " +
                            "Learn mode — the maintainer smoke surface.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = developerMode,
                    onCheckedChange = onDeveloperModeChange,
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF4CAF50),
                            uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.20f),
                            uncheckedBorderColor = Color.White.copy(alpha = 0.30f),
                        ),
                )
            }
        }
    }
}
