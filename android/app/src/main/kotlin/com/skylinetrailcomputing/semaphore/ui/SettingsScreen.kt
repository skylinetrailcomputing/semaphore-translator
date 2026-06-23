package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.clickable
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
 * The regular-user **Settings** surface ([6a-4], #72) — the gear destination from
 * Home. Distinct from the dev-ish [DeveloperSettingsScreen] it was split from: this
 * is the surface a beta tester sees, and the home for the assist/practice-mode
 * toggles (6a-5 #73, 6a-6 #74) that land in their own section above About. It hosts
 * the always-reachable **About** entry ([6b-4], #82) and a **Developer** row that
 * pushes the maintainer smoke surface one level deeper, off this user-facing
 * screen.
 *
 * Uses a Scaffold + [TopAppBar] (Up arrow + title) — the conventional Android
 * affordance for a non-immersive pushed screen, paralleling the titled inline nav
 * bar on iOS's `SettingsView`. Colors are set explicitly because the app isn't
 * wrapped in a `MaterialTheme`. The iOS twin is `SettingsView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    showAssist: Boolean,
    onShowAssistChange: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onDeveloper: () -> Unit,
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
        Column(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            // Assist & practice-mode toggles (6a-5 #73, 6a-6 #74) — the primary
            // regular-user content, above the nav rows.
            AssistToggleRow(checked = showAssist, onCheckedChange = onShowAssistChange)

            // The always-reachable About/privacy surface ([6b-4], #82): app version,
            // AS-IS beta line, and the hosted EULA + Privacy links.
            SettingsRow("About", onClick = onAbout)
            // Developer mode lives one level deeper ([5d], #50): a maintainer smoke
            // surface, not regular-user config, so it gets its own screen.
            SettingsRow("Developer", onClick = onDeveloper)
        }
    }
}

/**
 * The "Show assist figure" toggle (6a-5, #73) — the regular-user on/off for the
 * contract-derived drill assist. Mirrors the Developer-mode switch styling; the
 * green on-track is set explicitly because the app isn't wrapped in a MaterialTheme.
 */
@Composable
private fun AssistToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Show assist figure", color = Color.White, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Shows a stick figure of the arm positions to copy for each letter " +
                    "while practising a passage in Learn.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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

/** A tappable settings row — label on the lead, a disclosure chevron on the end. */
@Composable
private fun SettingsRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text("›", color = Color.White.copy(alpha = 0.4f), fontSize = 22.sp)
    }
}
