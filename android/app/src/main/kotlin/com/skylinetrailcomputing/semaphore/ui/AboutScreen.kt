package com.skylinetrailcomputing.semaphore.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The passive **About** surface ([6b-4], #82) — the always-reachable counterpart
 * to the first-launch disclaimer gate ([6b-9], #87). Reached from the Settings
 * surface (today's [SettingsScreen]; the 6a-4 regular-user surface, #72, will
 * re-home the entry row). Shows the app version/build, a one-line AS-IS beta
 * notice, and links to the *hosted* EULA + Privacy Policy. The links reuse the
 * URLs from the bundled `disclaimer.json` (via [DisclaimerDocument]) — the same
 * single source the gate uses, so About and the gate can never point at different
 * hosts. Uses a Scaffold + [TopAppBar] like [SettingsScreen]. The iOS twin is
 * `AboutView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versionLine = remember { appVersionLine(context) }
    // Reuse the hosted URLs from the bundled disclaimer doc (present by the time
    // About is reachable — the gate loads it first); degrade to hidden links if it
    // somehow can't load rather than failing the screen.
    val links = remember { runCatching { DisclaimerDocument.loadFromAssets(context) }.getOrNull() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
        Column(
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                "Semaphore Translator",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Version $versionLine",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Pre-release beta software, provided “as is”, without warranty of any kind.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
            )
            if (links != null) {
                Spacer(Modifier.height(20.dp))
                AboutLink("End User License Agreement", links.eulaUrl)
                Spacer(Modifier.height(12.dp))
                AboutLink("Privacy Policy", links.privacyUrl)
                Spacer(Modifier.height(20.dp))
                Text(
                    "Camera frames are processed on your device and are never " +
                        "stored or transmitted.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * The installed app's "name (build)" line, read from `PackageManager` at runtime
 * so it always reflects what's actually installed — no `BuildConfig` toggle, the
 * parity match for iOS reading the bundle's Info.plist. `longVersionCode` on API
 * 28+, the deprecated `versionCode` below it (minSdk is 26).
 */
@Suppress("DEPRECATION")
private fun appVersionLine(context: Context): String =
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        "${info.versionName} ($code)"
    }.getOrDefault("—")

/**
 * A tappable link that opens [url] in the browser — cyan + underlined, the same
 * affordance as the disclaimer gate's links. Guarded so a device with no browser
 * fails silent (the docs are non-essential, hosted) rather than crashing.
 */
@Composable
private fun AboutLink(label: String, url: String) {
    val context = LocalContext.current
    Text(
        label,
        color = Color(0xFF80DEEA),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline,
        modifier =
            Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
    )
}
