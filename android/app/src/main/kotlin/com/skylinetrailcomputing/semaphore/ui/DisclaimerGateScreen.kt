package com.skylinetrailcomputing.semaphore.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The first-launch disclaimer gate ([6b-9], #87) — the **active** click-through
 * shown before the Home fork, the counterpart to the passive About surface
 * (6b-4). Maintainer-elective belt-and-suspenders for the Tier-A beta: the text
 * is distilled from the EULA (the full docs are one tap away). [SemaphoreApp]
 * shows this until the user accepts; on accept it persists the doc's content
 * hash, so the gate stays dismissed until the doc is edited. The accept button
 * sits outside the scroll region so it's always reachable on small screens. The
 * iOS twin is `DisclaimerGateView`.
 */
@Composable
fun DisclaimerGateScreen(
    document: DisclaimerDocument,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Text(
                document.title,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            document.body.forEach { paragraph ->
                Text(paragraph, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(4.dp))
            LinkRow("End User License Agreement", document.eulaUrl)
            Spacer(Modifier.height(8.dp))
            LinkRow("Privacy Policy", document.privacyUrl)
            Spacer(Modifier.height(16.dp))
            Text(document.agreement, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Version ${document.version}",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
            )
        }
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
        ) {
            Text(
                document.acceptLabel,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * A tappable link that opens [url] in the browser. Cyan + underlined for affordance
 * over the black gate, paralleling iOS's `.cyan`-tinted `Link`. Built as a Compose
 * `LinkAnnotation` (1.7+) so TalkBack announces it as a **link**, not plain text
 * (#92). Passing a `linkInteractionListener` replaces the default UriHandler open,
 * so the guarded Intent stays: a device with no browser fails silent rather than
 * throwing `ActivityNotFoundException` and crashing the gate (the links are
 * non-essential — the full docs are hosted).
 */
@Composable
private fun LinkRow(label: String, url: String) {
    val context = LocalContext.current
    val annotated =
        buildAnnotatedString {
            withLink(
                LinkAnnotation.Url(
                    url,
                    styles =
                        TextLinkStyles(
                            SpanStyle(
                                color = Color(0xFF80DEEA),
                                textDecoration = TextDecoration.Underline,
                            )
                        ),
                    linkInteractionListener = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                )
            ) {
                append(label)
            }
        }
    Text(annotated, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

/**
 * Fail-closed screen shown when the bundled disclaimer can't be loaded ([6b-9],
 * #87). A missing/corrupt `disclaimer.json` is a build-packaging bug; the legal
 * gate blocks entry rather than silently passing, surfacing the bug instead of
 * granting unconsented access. The iOS twin is `DisclaimerUnavailableView`.
 */
@Composable
fun DisclaimerUnavailableScreen(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Couldn’t load the required notice.",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This is a packaging error. Please reinstall the app.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}
