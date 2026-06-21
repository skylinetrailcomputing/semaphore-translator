package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The lean **Settings** surface ([5d], #50). Its only content for now is the
 * Developer-mode toggle, which gates the Learn screen's skeleton overlay + raw
 * per-frame readout (the maintainer smoke surface). State is hoisted to
 * `SemaphoreApp`, which persists each change via [AppSettings] and passes the
 * current value down — so the toggle survives launches and the Learn screen sees
 * the same flag. Contract knobs (angle-tolerance, commit-hold) are deliberately
 * out of scope (FR7).
 *
 * Mirrors [InterpretStub]'s Scaffold + [TopAppBar] (Up arrow + title) — the
 * conventional Android affordance for a non-immersive pushed screen, paralleling
 * the titled inline nav bar on iOS's `SettingsView`. Colors are set explicitly
 * because the app isn't wrapped in a `MaterialTheme`. The iOS twin is
 * `SettingsView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
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
                title = { Text("Settings") },
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
        Box(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
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
