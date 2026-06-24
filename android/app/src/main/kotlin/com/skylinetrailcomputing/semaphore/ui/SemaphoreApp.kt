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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
    // Assist-figure on/off (6a-5, #73) hoisted alongside developerMode: one source
    // of truth feeds the Settings toggle and every drill-capable screen, reactive
    // in-session (the drill reflects a flip on the next entry) and persisted. The
    // iOS twin is `@AppStorage("showAssistFigure")`, shared across views by key.
    var showAssist by remember { mutableStateOf(AppSettings.showAssist(context)) }
    // NUMERALS-indicator on/off (6a-14, #103) hoisted alongside the others: one
    // source of truth feeds the Settings toggle and every live camera screen,
    // reactive in-session and persisted. The iOS twin is
    // `@AppStorage("showNumeralsIndicator")`, shared across views by key.
    var showNumeralsIndicator by remember {
        mutableStateOf(AppSettings.showNumeralsIndicator(context))
    }
    // Framing-hint banner on/off (6a-18 #112, polish 6a-20 #125) hoisted alongside the
    // others: one source of truth feeds the Settings toggle and every Learn camera
    // screen, reactive in-session and persisted. The iOS twin is
    // `@AppStorage("showFramingHint")`, shared across views by key.
    var showFramingHint by remember { mutableStateOf(AppSettings.showFramingHint(context)) }
    // Forgiving "easy mode" drill readout on/off (6a-10, #95) hoisted alongside the
    // others: one source of truth feeds the Settings toggle and the drill screen,
    // reactive in-session (a flip shows on the next drill entry) and persisted. The
    // iOS twin is `@AppStorage("drillMatchedOnlyReadout")`, shared across views by key.
    var matchedOnlyReadout by remember { mutableStateOf(AppSettings.matchedOnlyReadout(context)) }
    // Drill auto-reset on/off (6a-12, #97) hoisted alongside the others: one source
    // of truth feeds the Settings toggle and the drill screen, reactive in-session
    // and persisted. The iOS twin is `@AppStorage("autoResetOnComplete")`, shared
    // across views by key.
    var autoResetOnComplete by remember { mutableStateOf(AppSettings.autoResetOnComplete(context)) }
    // The active drill passage (6a-3, #71), set by the custom/stock source just
    // before it navigates to the DRILL route. rememberSaveable so it survives a
    // configuration change / process death (a plain remember would drop it and the
    // restored DRILL route would build an empty-complete session); the string is
    // small (capped at PassageSource.MAX_TARGETS) so it's safe in saved state. It is
    // always overwritten on each onStart, so a stale value can't reach the screen,
    // and the DRILL route null-guards regardless.
    var drillTargets by rememberSaveable { mutableStateOf<String?>(null) }

    NavHost(navController, startDestination = Route.HOME) {
        composable(Route.HOME) {
            HomeScreen(
                onLearn = { navController.navigate(Route.LEARN_HUB) },
                onInterpret = { navController.navigate(Route.INTERPRET) },
                onSettings = { navController.navigate(Route.SETTINGS) },
            )
        }
        composable(Route.LEARN_HUB) {
            // The Learn source picker ([6a], #71): a no-camera chooser between
            // free-form practice and two passage-drill sources (custom / sight-read).
            // All push the same camera screen below, so nothing camera-related runs
            // here. The iOS twin is `LearnChooserView`.
            LearnHubScreen(
                onFreePractice = { navController.navigate(Route.LEARN) },
                onCustom = { navController.navigate(Route.CUSTOM_PASSAGE) },
                onStock = { navController.navigate(Route.STOCK_PASSAGE) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.LEARN) {
            // Full-bleed camera screen with a floating back chevron over it — the
            // Android parallel of iOS's transparent nav bar on the Learn screen.
            Box(Modifier.fillMaxSize()) {
                // Free-practice has no drill target, so the assist never draws here;
                // the flag is threaded uniformly so gating stays by drill-state +
                // lens, not by which route built the screen (iOS parity).
                SemaphoreScreen(
                    developerMode = developerMode,
                    showAssist = showAssist,
                    showNumeralsIndicator = showNumeralsIndicator,
                    showFramingHint = showFramingHint,
                    autoResetOnComplete = autoResetOnComplete,
                )
                BackButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        composable(Route.CUSTOM_PASSAGE) {
            // Type-a-passage source (6a-3, #71): sanitises the typed text and hands
            // the target string up before navigating into the drill. The iOS twin is
            // `CustomPassageView`.
            CustomPassageScreen(
                onStart = { targets ->
                    drillTargets = targets
                    navController.navigate(Route.DRILL)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.STOCK_PASSAGE) {
            // Sight-read source (6a-3, #71): pick a bundled passage by its hint; its
            // sanitised text becomes the drill targets. The iOS twin is `StockPassageView`.
            StockPassageScreen(
                onStart = { targets ->
                    drillTargets = targets
                    navController.navigate(Route.DRILL)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.DRILL) {
            // The same front-camera screen as free-form Learn, driven as a passage
            // drill by the active target string set by the custom/stock source
            // (6a-3, #71). The drill HUD + celebrate live in [SemaphoreScreen].
            // `drillTargets` is rememberSaveable, so it survives config change /
            // process death; the null-guard pops back if the route is somehow reached
            // without a passage, so we never build an empty-complete session.
            val targets = drillTargets
            if (targets == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                Box(Modifier.fillMaxSize()) {
                    SemaphoreScreen(
                        developerMode = developerMode,
                        emptyHint = "Sign the letter shown above",
                        drillTargets = targets,
                        showAssist = showAssist,
                        showNumeralsIndicator = showNumeralsIndicator,
                        showFramingHint = showFramingHint,
                        // Drill-only (#95): the forgiving readout has no effect off a
                        // drill, so it's threaded only here, not to LEARN/INTERPRET.
                        matchedOnlyReadout = matchedOnlyReadout,
                        autoResetOnComplete = autoResetOnComplete,
                    )
                    BackButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
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
                    showAssist = showAssist,
                    showNumeralsIndicator = showNumeralsIndicator,
                    // Threaded for call-site uniformity; the banner is front-lens-only
                    // (poseHint stays null on the rear lens), so it never shows here.
                    showFramingHint = showFramingHint,
                    autoResetOnComplete = autoResetOnComplete,
                )
                BackButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        composable(Route.SETTINGS) {
            // The regular-user settings surface ([6a-4], #72): About + a Developer
            // row. The dev-mode toggle itself lives one level deeper on the
            // DEVELOPER route, off this user-facing screen.
            SettingsScreen(
                showAssist = showAssist,
                onShowAssistChange = {
                    showAssist = it
                    AppSettings.setShowAssist(context, it)
                },
                showNumeralsIndicator = showNumeralsIndicator,
                onShowNumeralsIndicatorChange = {
                    showNumeralsIndicator = it
                    AppSettings.setShowNumeralsIndicator(context, it)
                },
                showFramingHint = showFramingHint,
                onShowFramingHintChange = {
                    showFramingHint = it
                    AppSettings.setShowFramingHint(context, it)
                },
                matchedOnlyReadout = matchedOnlyReadout,
                onMatchedOnlyReadoutChange = {
                    matchedOnlyReadout = it
                    AppSettings.setMatchedOnlyReadout(context, it)
                },
                autoResetOnComplete = autoResetOnComplete,
                onAutoResetOnCompleteChange = {
                    autoResetOnComplete = it
                    AppSettings.setAutoResetOnComplete(context, it)
                },
                onAbout = { navController.navigate(Route.ABOUT) },
                onDeveloper = { navController.navigate(Route.DEVELOPER) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.DEVELOPER) {
            // The dev-ish settings split out by 6a-4 (#72) — the Developer-mode
            // toggle (maintainer smoke surface). State stays hoisted here so the
            // Learn screen sees the same flag and it persists across launches.
            DeveloperSettingsScreen(
                developerMode = developerMode,
                onDeveloperModeChange = {
                    developerMode = it
                    AppSettings.setDeveloperMode(context, it)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.ABOUT) {
            // The passive About/privacy surface ([6b-4], #82) — pushed from
            // Settings. No camera; the iOS twin is `AboutView`.
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** Navigation routes for the [SemaphoreApp] graph. */
private object Route {
    const val HOME = "home"
    const val LEARN_HUB = "learn_hub"
    const val LEARN = "learn"
    const val CUSTOM_PASSAGE = "custom_passage"
    const val STOCK_PASSAGE = "stock_passage"
    const val DRILL = "drill"
    const val INTERPRET = "interpret"
    const val SETTINGS = "settings"
    const val DEVELOPER = "developer"
    const val ABOUT = "about"
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
