package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The no-camera landing screen ([5a], #47) — the app's entry point. Forks to the
 * two camera modes via two pills; no camera or permission prompt fires here
 * (deferred until a mode is entered). The iOS twin is `HomeView`.
 */
@Composable
fun HomeScreen(
    onLearn: () -> Unit,
    onInterpret: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Semaphore Translator",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Signal with flags, read with the camera.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            ModePill(
                title = "Sign / Learn semaphore",
                subtitle = "Practice signing — front camera",
                onClick = onLearn,
            )
            Spacer(Modifier.height(16.dp))
            ModePill(
                title = "Interpret semaphore",
                subtitle = "Read someone else’s flags",
                onClick = onInterpret,
            )
        }
        // A single gear in the top-end corner is the Settings entry point
        // ([5d], #50) — kept minimal per the grooming decision. Insets below the
        // status bar; the iOS twin is `HomeView`'s top-trailing gear overlay.
        IconButton(
            onClick = onSettings,
            modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * One tappable mode option on Home — title + subtitle, trailing chevron, in a
 * rounded card. Reused by the Learn chooser ([6a], #70). The iOS twin is
 * `HomeView`'s `ModePill`.
 */
@Composable
internal fun ModePill(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Dimmed + non-actionable when disabled — the Learn sight-read picker (#127)
            // uses this for a number passage while numerals are off. Default `true`
            // keeps every existing call site unchanged.
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            // Merge title + subtitle into one Button node so TalkBack reads the pill
            // as a single control; the decorative "›" is cleared below (#92). A disabled
            // pill announces as such.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        if (enabled) {
            Text(
                "›",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 22.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
