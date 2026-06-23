package com.skylinetrailcomputing.semaphore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skylinetrailcomputing.semaphore.core.PassageSource

/**
 * The Learn source picker ([6a], #71). The "Sign / Learn" pill lands here and
 * offers three front-camera surfaces: **free practice** (sign anything, watch the
 * decode) and two passage-drill sources — **type a passage** (custom text,
 * sanitised) and **sight-read a stock passage** (a bundled passage you haven't
 * seen). All push the same [SemaphoreScreen]; the drills differ only by an injected,
 * sanitised passage. No camera or permission prompt fires here. The iOS twin is
 * `LearnChooserView` + the custom/stock views.
 */
@Composable
fun LearnHubScreen(
    onFreePractice: () -> Unit,
    onCustom: () -> Unit,
    onStock: () -> Unit,
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
                title = "Type a passage",
                subtitle = "Drill your own words, letter by letter",
                onClick = onCustom,
            )
            Spacer(Modifier.height(16.dp))
            ModePill(
                title = "Sight-read a passage",
                subtitle = "Drill a surprise passage you haven’t seen",
                onClick = onStock,
            )
        }
        BackButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

/**
 * The **custom passage** source ([6a-3], #71): a text field whose live, sanitised
 * preview is exactly what the drill will use ([PassageSource.sanitize]). Start is
 * disabled until the sanitised result is a non-empty passage within
 * [PassageSource.MAX_TARGETS], so the drill never gets an empty target sequence
 * (drill_contract `empty_targets`). Prefilled with a friendly starter. The iOS twin
 * is `CustomPassageView`.
 */
@Composable
fun CustomPassageScreen(
    onStart: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable, not remember: keyed to this destination's back-stack entry,
    // the typed text survives the forward-nav into the drill camera and the
    // chevron pop back (the entry stays on the stack — only its composition is
    // disposed). Popping further back to the Learn hub destroys the entry, so a
    // later re-entry resets to the starter. Matches the iOS twin, where the pushed
    // camera leaves CustomPassageView's @State intact on pop-back.
    var text by rememberSaveable { mutableStateOf("HELLO") }
    // The sanitiser is the single source of truth, so the preview can't disagree
    // with what is actually drilled.
    val sanitized = PassageSource.sanitize(text)
    val withinCap = sanitized.length <= PassageSource.MAX_TARGETS
    val canStart = sanitized.isNotEmpty() && withinCap

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(40.dp)) // clear the floating back chevron
            Text("Type a passage", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Type anything — letters, digits, and spaces. Other characters are ignored.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
            )
            OutlinedTextField(
                value = text,
                // No raw-length cap: the sanitiser is O(n)-cheap and the *visible*
                // sanitised counter (below) is the only bound, so input is never
                // silently truncated.
                onValueChange = { text = it },
                label = { Text("Your passage") },
                keyboardOptions =
                    KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedLabelColor = Color.White.copy(alpha = 0.7f),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                        cursorColor = Color.White,
                    ),
            )
            PreviewCard(sanitized, withinCap)
            StartButton(canStart) { onStart(sanitized) }
        }
        BackButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

/**
 * The live preview = the sanitised target string (spaces shown as `␣` so trims/
 * collapses are legible) + a target counter. Empty-state copy names the supported
 * set so a field of only-dropped characters doesn't read as a broken Start button.
 */
@Composable
private fun PreviewCard(sanitized: String, withinCap: Boolean) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Preview", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        if (sanitized.isEmpty()) {
            Text(
                "No supported characters yet (A–Z, 0–9, space).",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 15.sp,
            )
        } else {
            Text(
                sanitized.replace(" ", "␣"),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
            )
            // length == target count: the sanitiser's output is pure ASCII, so one
            // Char is exactly one drill target.
            Text(
                "${sanitized.length} / ${PassageSource.MAX_TARGETS}",
                color = if (withinCap) Color.White.copy(alpha = 0.6f) else Color.Red,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            if (!withinCap) {
                Text(
                    "Too long — shorten to ${PassageSource.MAX_TARGETS} characters.",
                    color = Color.Red,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun StartButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(
                if (enabled) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(14.dp),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Start drill",
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

/**
 * The **sight-read** source ([6a-3], #71): a list of bundled stock passages shown by
 * their content-neutral hint only — the text is never displayed here, so the signer
 * sight-reads it one HUD target at a time. Loads `shared/stock_passages.json` from
 * assets; fails **soft** (an "unavailable" message, not a crash) if the asset is
 * missing/corrupt, leaving the custom source usable. The iOS twin is `StockPassageView`.
 */
@Composable
fun StockPassageScreen(
    onStart: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val passages = remember {
        runCatching { StockPassages.loadFromAssets(context).passages }.getOrDefault(emptyList())
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (passages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Sight-read passages are unavailable.",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Try “Type a passage” instead.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(40.dp)) // clear the floating back chevron
                Text("Sight-read", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Pick one and sign it as it’s revealed, letter by letter.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Start,
                )
                for (passage in passages) {
                    val targets = PassageSource.sanitize(passage.text)
                    ModePill(
                        title = passage.hint,
                        subtitle = "${targets.length} steps",
                        onClick = { onStart(targets) },
                    )
                }
            }
        }
        BackButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}
