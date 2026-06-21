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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun HomeScreen(onLearn: () -> Unit, onInterpret: () -> Unit, modifier: Modifier = Modifier) {
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
    }
}

/**
 * One tappable mode option on Home — title + subtitle, trailing chevron, in a
 * rounded card. The iOS twin is `HomeView`'s `ModePill`.
 */
@Composable
private fun ModePill(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
        Text("›", color = Color.White.copy(alpha = 0.4f), fontSize = 22.sp)
    }
}
