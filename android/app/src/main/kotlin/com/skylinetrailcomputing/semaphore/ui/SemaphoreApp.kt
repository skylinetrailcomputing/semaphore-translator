package com.skylinetrailcomputing.semaphore.ui

import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * The app's composition root. On launch it shows the first-launch disclaimer gate
 * ([6b-9], #87) until the user accepts the *current* disclaimer doc, then mounts
 * [MainNavHost] (the Home → mode fork). Consent is keyed on the doc's content
 * hash (persisted via [AppSettings]), so the gate reappears only when the bundled
 * `shared/disclaimer.json` changes. The iOS twin is `SemaphoreTranslatorApp`'s
 * `RootView`.
 */
@Composable
fun SemaphoreApp() {
    val context = LocalContext.current
    // Load the bundled first-launch disclaimer once. A packaging failure (missing
    // or corrupt asset) is captured so the gate fails closed rather than silently
    // skipping it.
    val disclaimer = remember { runCatching { DisclaimerDocument.loadFromAssets(context) } }
    val document = disclaimer.getOrNull()
    // Seeded from the persisted hash; flipped true on accept. The gate reappears
    // only when the bundled doc's hash stops matching the stored one.
    var accepted by remember {
        mutableStateOf(
            document != null &&
                !DisclaimerDocument.needsConsent(
                    acceptedHash = AppSettings.acceptedDisclaimerHash(context),
                    documentHash = document.contentHash,
                )
        )
    }

    when {
        document == null -> DisclaimerUnavailableScreen()
        !accepted ->
            DisclaimerGateScreen(
                document,
                onAccept = {
                    AppSettings.recordDisclaimerAccepted(
                        context,
                        document.contentHash,
                        System.currentTimeMillis(),
                    )
                    accepted = true
                },
            )
        else -> MainNavHost()
    }
}

/**
 * The app's navigation host ([5a], #47). Opens on the no-camera [HomeScreen] and
 * forks to the two camera modes. The camera (and its permission prompt) only
 * starts once a mode destination composes — `NavHost` composes just the current
 * destination, so nothing camera-related runs on Home, and navigating away from
 * Learn disposes [SemaphoreScreen], which cancels its capture flow and unbinds
 * the camera. The iOS twin is `SemaphoreTranslatorApp`'s `NavigationStack`.
 */
@Composable
private fun MainNavHost() {
    val context = LocalContext.current
    val navController = rememberNavController()
    // Developer-mode flag ([5d], #50) hoisted here so a single source of truth
    // feeds both the Settings toggle and the Learn screen. Seeded from the
    // persisted value, then mirrored to [AppSettings] on each change — so it is
    // reactive in-session (Learn reflects a flip on the next entry) and survives
    // launches. The iOS twin is `@AppStorage`, shared across views by key.
    var developerMode by remember { mutableStateOf(AppSettings.developerMode(context)) }

    NavHost(navController, startDestination = Route.HOME) {
        composable(Route.HOME) {
            HomeScreen(
                onLearn = { navController.navigate(Route.LEARN_HUB) },
                onInterpret = { navController.navigate(Route.INTERPRET) },
                onSettings = { navController.navigate(Route.SETTINGS) },
            )
        }
        composable(Route.LEARN_HUB) {
            // The Learn fork ([6a], #70): a no-camera chooser between free-form
            // practice and a guided passage drill. Both push the same camera screen
            // below, so nothing camera-related runs here. The iOS twin is
            // `LearnChooserView`.
            LearnHubScreen(
                onFreePractice = { navController.navigate(Route.LEARN) },
                onDrill = { navController.navigate(Route.DRILL) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.LEARN) {
            // Full-bleed camera screen with a floating back chevron over it — the
            // Android parallel of iOS's transparent nav bar on the Learn screen.
            Box(Modifier.fillMaxSize()) {
                SemaphoreScreen(developerMode = developerMode)
                BackButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        composable(Route.DRILL) {
            // The same front-camera screen as free-form Learn, driven as a passage
            // drill by an injected target string (6a-2, #70). The drill HUD +
            // celebrate live in [SemaphoreScreen]; the custom source lands in 6a-3.
            Box(Modifier.fillMaxSize()) {
                SemaphoreScreen(
                    developerMode = developerMode,
                    emptyHint = "Sign the letter shown above",
                    drillTargets = STARTER_PASSAGE,
                )
                BackButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        composable(Route.INTERPRET) {
            // The same shared camera+decode screen as Learn, only rear-facing
            // (#46/#60) — full-bleed with a floating back chevron over it. Lens +
            // display mirror are handled inside [SemaphoreScreen]; the decode path
            // is identical (ADR 0006 — rear needs no adapter change).
            Box(Modifier.fillMaxSize()) {
                SemaphoreScreen(
                    developerMode = developerMode,
                    cameraLens = CameraSelector.DEFAULT_BACK_CAMERA,
                    emptyHint = "Point at someone signing",
                )
                BackButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        composable(Route.SETTINGS) {
            SettingsScreen(
                developerMode = developerMode,
                onDeveloperModeChange = {
                    developerMode = it
                    AppSettings.setDeveloperMode(context, it)
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Navigation routes for the [SemaphoreApp] graph. */
private object Route {
    const val HOME = "home"
    const val LEARN_HUB = "learn_hub"
    const val LEARN = "learn"
    const val DRILL = "drill"
    const val INTERPRET = "interpret"
    const val SETTINGS = "settings"
}

/**
 * Floating back affordance for the immersive camera screen — the visible
 * counterpart to the system back button (which also pops to Home). Mirrors iOS's
 * transparent-bar back chevron. The circle gives the icon contrast over a bright
 * camera frame; the vector [ArrowBack] is geometrically centered (a text glyph
 * sat low and shifted with the device font). Insets itself below the status bar.
 */
@Composable
internal fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .systemBarsPadding()
            .padding(8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}
