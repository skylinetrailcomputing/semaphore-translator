package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * The app's navigation host ([5a], #47). Opens on the no-camera [HomeScreen] and
 * forks to the two camera modes. The camera (and its permission prompt) only
 * starts once a mode destination composes — `NavHost` composes just the current
 * destination, so nothing camera-related runs on Home, and navigating away from
 * Learn disposes [SemaphoreScreen], which cancels its capture flow and unbinds
 * the camera. The iOS twin is `SemaphoreTranslatorApp`'s `NavigationStack`.
 */
@Composable
fun SemaphoreApp() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = Route.HOME) {
        composable(Route.HOME) {
            HomeScreen(
                onLearn = { navController.navigate(Route.LEARN) },
                onInterpret = { navController.navigate(Route.INTERPRET) },
            )
        }
        composable(Route.LEARN) {
            // Full-bleed camera screen with a floating back chevron over it — the
            // Android parallel of iOS's transparent nav bar on the Learn screen.
            Box(Modifier.fillMaxSize()) {
                SemaphoreScreen()
                BackButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        composable(Route.INTERPRET) {
            InterpretStub(onBack = { navController.popBackStack() })
        }
    }
}

/** Navigation routes for the [SemaphoreApp] graph. */
private object Route {
    const val HOME = "home"
    const val LEARN = "learn"
    const val INTERPRET = "interpret"
}

/**
 * Floating back affordance for the pushed full-bleed screens — the visible
 * counterpart to the system back button (which also pops to Home). Mirrors iOS's
 * nav-bar back chevron. Insets itself below the status bar.
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
        Text("←", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
