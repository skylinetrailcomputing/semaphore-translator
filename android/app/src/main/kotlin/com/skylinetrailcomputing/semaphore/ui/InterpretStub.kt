package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Placeholder for the rear-camera **Interpret** mode ([5c]). The functional
 * decode (rear camera = the opposite mirror geometry, the §3.2 seam the parity
 * harness can't catch) is deferred to Epic 5.2 (#46); for now tapping Interpret
 * lands here. No camera starts.
 *
 * A standard Material [TopAppBar] (Up arrow + title) is the conventional Android
 * affordance for a non-immersive pushed screen — more discoverable than a bare
 * floating chevron — and it mirrors the titled inline nav bar on iOS's
 * `InterpretStubView`. (The immersive camera screen keeps the floating
 * [BackButton] instead; see `SemaphoreApp`.) Colors are set explicitly because
 * the app isn't wrapped in a `MaterialTheme`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterpretStub(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Interpret") },
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
        Box(
            Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Coming soon", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Point the rear camera at someone signing and read their flags. Not built yet.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
