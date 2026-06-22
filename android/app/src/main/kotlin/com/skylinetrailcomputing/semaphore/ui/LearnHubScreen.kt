package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Learn fork ([6a], #70). The "Sign / Learn" pill lands here and offers two
 * front-camera surfaces: **free practice** (sign anything, watch the decode) and
 * a **passage drill** (stay-until-success over a fixed passage, with a HUD +
 * celebrate). Both push the same [SemaphoreScreen]; the drill differs only by an
 * injected passage. No camera or permission prompt fires here — that's deferred to
 * the pushed screen, exactly as on Home. The iOS twin is `LearnChooserView`.
 */
@Composable
fun LearnHubScreen(
    onFreePractice: () -> Unit,
    onDrill: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Learn", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            ModePill(
                title = "Free practice",
                subtitle = "Sign anything — see the letters",
                onClick = onFreePractice,
            )
            Spacer(Modifier.height(16.dp))
            ModePill(
                title = "Drill a passage",
                subtitle = "Sign “$STARTER_PASSAGE”, letter by letter",
                onClick = onDrill,
            )
        }
        BackButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

/**
 * The hardcoded starter passage for the 6a-2 spine; custom / sight-read sources
 * are 6a-3 (#71). Kept in lockstep with the iOS `LearnChooserView.starterPassage`.
 */
internal const val STARTER_PASSAGE = "HELLO"
