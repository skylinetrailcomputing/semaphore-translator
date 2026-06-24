package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    showNumeralsIndicator: Boolean,
    onShowNumeralsIndicatorChange: (Boolean) -> Unit,
    matchedOnlyReadout: Boolean,
    onMatchedOnlyReadoutChange: (Boolean) -> Unit,
    autoResetOnComplete: Boolean,
    onAutoResetOnCompleteChange: (Boolean) -> Unit,
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
            // Assist & practice-mode + display toggles (6a-5 #73, 6a-14 #103) — the
            // primary regular-user content, above the nav rows.
            SettingsToggleRow(
                title = "Show assist figure",
                subtitle =
                    "Shows a stick figure of the arm positions to copy for each " +
                        "letter while practising a passage in Learn.",
                checked = showAssist,
                onCheckedChange = onShowAssistChange,
            )
            // The live NUMERALS mode indicator (6a-14, #103) — a display toggle for
            // the camera screen; the iOS twin is the matching toggle in `SettingsView`.
            SettingsToggleRow(
                title = "Show numerals indicator",
                subtitle =
                    "Shows a badge over the camera while the decoder is reading " +
                        "digits, and hides it when it returns to letters.",
                checked = showNumeralsIndicator,
                onCheckedChange = onShowNumeralsIndicatorChange,
            )
            // The forgiving "easy mode" drill readout (6a-10, #95) — a Learn-drill
            // behaviour toggle, so it sits with the other regular-user toggles.
            SettingsToggleRow(
                title = "Forgiving drill readout",
                subtitle =
                    "While drilling a passage, the readout fills in only the letters " +
                        "you've matched — wrong letters and pauses are ignored. Turn " +
                        "off to show everything you sign.",
                checked = matchedOnlyReadout,
                onCheckedChange = onMatchedOnlyReadoutChange,
            )
            // Drill auto-reset (6a-12, #97) — a Learn drill-flow affordance, so it
            // sits with the other regular-user toggles. The iOS twin is the matching
            // toggle in `SettingsView`.
            SettingsToggleRow(
                title = "Auto-reset after a passage",
                subtitle =
                    "When you finish a drill, a short countdown clears it and starts " +
                        "the same passage again — so you can keep practising " +
                        "hands-free. Tap “Stay” on the celebration to keep it up instead.",
                checked = autoResetOnComplete,
                onCheckedChange = onAutoResetOnCompleteChange,
            )

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
 * A regular-user settings toggle row — a title + explanatory subtitle on the lead,
 * a green-on-track [Switch] on the end. Shared by the assist-figure (6a-5, #73) and
 * numerals-indicator (6a-14, #103) toggles. Mirrors the Developer-mode switch
 * styling; the colors are set explicitly because the app isn't wrapped in a
 * MaterialTheme.
 */
@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        // The whole row is the toggle target so TalkBack reads the title + subtitle as
        // the switch's label and announces its on/off state — a bare Switch otherwise
        // focuses separately from its label. The Switch's own onCheckedChange is null:
        // the row owns the action (#92).
        Modifier.fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
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
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(
            "›",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 22.sp,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
