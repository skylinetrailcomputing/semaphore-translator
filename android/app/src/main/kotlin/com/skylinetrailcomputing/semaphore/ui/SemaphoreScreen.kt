package com.skylinetrailcomputing.semaphore.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import com.skylinetrailcomputing.semaphore.core.Committer
import com.skylinetrailcomputing.semaphore.core.ContractLoader
import com.skylinetrailcomputing.semaphore.core.Keypoint
import com.skylinetrailcomputing.semaphore.core.Keypoints
import com.skylinetrailcomputing.semaphore.core.Mode
import com.skylinetrailcomputing.semaphore.capture.PoseCaptureSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The **Learn** screen ([5b], #48) — the front-camera view a signer uses to sign
 * into the camera and read their decode. The committed text is the visual hero;
 * the "no signer detected" state (NFR4) and clear/reset (FR5) are kept.
 *
 * The Epic-4 debug chrome — the 6-keypoint skeleton overlay and the raw per-frame
 * readout (L/R position ids, mode badge, indeterminate `·`) — is hidden in this
 * clean view and gated behind [developerMode], surfaced by the Developer-mode
 * toggle (#5d). That overlay is still the only visual confirmation of the adapter
 * flips (autonomy guardrail-d), so it is gated, not deleted. [developerMode] is
 * passed by `SemaphoreApp` from the persisted [AppSettings] flag the Settings
 * toggle writes (#50); the `false` default is for previews/tests. The iOS twin is
 * SwiftUI's `ContentView`.
 */
@Composable
fun SemaphoreScreen(
    developerMode: Boolean = false,
    cameraLens: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCamera = granted
        }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!hasCamera) launcher.launch(Manifest.permission.CAMERA)
    }

    when {
        !hasCamera ->
            Message(
                "Camera access needed",
                "Semaphore Translator reads flag positions from the camera. " +
                    "Grant camera access to use the live preview.",
            )
        else -> CameraScreen(developerMode, cameraLens)
    }
}

/** State the overlay + readout panel render. */
private data class PreviewState(
    val keypoints: Keypoints? = null,
    val leftId: Int? = null,
    val rightId: Int? = null,
    val character: String = "",
    val mode: Mode = Mode.LETTERS,
    val signerPresent: Boolean = false,
)

@ExperimentalGetImage
@Composable
private fun CameraScreen(developerMode: Boolean, cameraLens: CameraSelector) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    val decoder = remember { runCatching { ContractLoader.makeDecoder(context) }.getOrNull() }
    val committer =
        remember(decoder) {
            decoder?.let { d ->
                runCatching { Committer(d, ContractLoader.makeCommitTiming(context)) }.getOrNull()
            }
        }
    if (decoder == null || committer == null) {
        Message("Contract unavailable", "Couldn’t load the bundled semaphore contract.")
        return
    }

    val capture = remember { PoseCaptureSession(context) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE (TextureView) so the Compose skeleton overlay reliably
            // draws on top; a PERFORMANCE-mode SurfaceView can z-order over it,
            // which would defeat the whole point of this overlay smoke screen.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var state by remember { mutableStateOf(PreviewState()) }
    // Hoisted out of PreviewState so the clear button can reset it independently of
    // the per-frame state; persists across a watchdog reset() like iOS's.
    var committedText by remember { mutableStateOf("") }

    // Build the Preview use case once and point it at the PreviewView's surface,
    // then collect the keypoint stream bound to the same camera. The temporal
    // committer (#4.5) smooths/holds/debounces per frame; mode lives in the
    // committer and flips only on a committed control pose (§4.5), so it is no
    // longer threaded across frames here.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        var lastFrameAt = 0L

        // Freshness watchdog: the stream simply stops yielding when a signer
        // leaves the frame (no full upper-body skeleton → no emit), so absence is
        // a timeout, not an event (NFR4). Mirrors iOS's watchdog.
        launch {
            while (isActive) {
                delay(250)
                if (state.signerPresent &&
                    System.currentTimeMillis() - lastFrameAt > SIGNER_TIMEOUT_MS
                ) {
                    // True signer-loss: hard-reset the committer (clear window,
                    // mode → LETTERS). `committedText` is preserved so the word
                    // just signed stays readable after the arms drop.
                    committer.reset()
                    state =
                        state.copy(
                            signerPresent = false,
                            keypoints = null,
                            mode = committer.currentMode,
                        )
                }
            }
        }

        capture.keypoints(lifecycleOwner, preview, cameraLens).collectLatest { frame ->
            // `lastFrameAt` is the watchdog's freshness clock (real-time liveness),
            // distinct from `frame.tMs` (the frame-aligned committer clock, #34) the
            // committer consumes below.
            lastFrameAt = System.currentTimeMillis()
            val kp = frame.keypoints

            // Temporal path: classify the mode-independent pose, then let the
            // committer smooth/hold/debounce it. A non-empty return is a committed
            // letter/digit/space.
            val symbol = decoder.classify(kp)
            val emitted = committer.process(symbol, frame.tMs)
            if (emitted.isNotEmpty()) committedText += emitted

            // Raw white-box readout: ids are mode-independent; only the per-frame
            // character is interpreted, in the committer's (possibly just-flipped) mode.
            val raw = decoder.decodeFrame(kp, committer.currentMode)
            state =
                PreviewState(
                    keypoints = kp,
                    leftId = raw.ids[0],
                    rightId = raw.ids[1],
                    character = raw.emit,
                    mode = committer.currentMode,
                    signerPresent = true,
                )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        if (developerMode) {
            // `PreviewView` auto-mirrors the front lens and auto-un-mirrors the
            // rear (CameraX default), so the preview itself needs no knob; the
            // overlay's x-map must follow it, keyed off the same lens (#57).
            val mirrored = cameraLens.lensFacing == CameraSelector.LENS_FACING_FRONT
            SkeletonOverlay(state.keypoints, mirrored, Modifier.fillMaxSize())
        }
        if (!state.signerPresent) {
            Text(
                "No signer detected",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CommittedHero(committedText, onClear = { committedText = "" })
            if (developerMode) Readout(state)
        }
    }
}

private const val SIGNER_TIMEOUT_MS = 500L
private val leftColor = Color.Cyan
private val rightColor = Color(0xFFFF9800)

/**
 * Draws the 6 post-adapter [Keypoints] over the preview — the human-visible
 * check that the L/R labels and "up" are right. Left arm cyan, right arm orange,
 * so the mirror is legible at a glance.
 *
 * **Signer-frame → display-frame mapping** (identical convention to iOS's
 * `SkeletonOverlay`). [Keypoints] are normalized `[0,1]`, **y-up**, in the
 * **signer's** perspective (`+x` = signer's right). The x-map follows whatever the
 * `PreviewView` shows, keyed off the lens-derived [mirrored] flag (#57):
 * `PreviewView` auto-**mirrors** the front camera (natural selfie view, its
 * default) and auto-**un-mirrors** the rear, so:
 *   - front, mirrored preview: `+x` already matches screen-right →
 *     `screen_x = kp.x · W`
 *   - rear, un-mirrored preview: undo the signer→observer flip →
 *     `screen_x = (1 − kp.x) · W`
 *   - undo y-up (both):                          `screen_y = (1 − kp.y) · H`
 * (Front verified on a Pixel 9a, #23: with `(1 − x)` the overlay was flipped
 * relative to the mirrored preview; `x` lands it on the limbs. The rear x-flip was
 * confirmed on a throwaway scratch branch, #57.) This is purely a *display*
 * transform; it never touches the adapter mirror. If the live overlay ever lands
 * flipped/rotated, this mapping and the `PreviewView` mirroring/rotation are the
 * knobs (tuned in lockstep) — a second adapter flip is never the fix (it would
 * desync the platforms and the parity harness). Registration is approximate under
 * FILL_CENTER cropping, fine for the orientation/mirror smoke this screen exists for.
 */
@Composable
private fun SkeletonOverlay(
    keypoints: Keypoints?,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val k = keypoints ?: return@Canvas
        fun at(p: Keypoint): Offset {
            val x = if (mirrored) p.x.toFloat() else (1f - p.x.toFloat())
            return Offset(x * size.width, (1f - p.y.toFloat()) * size.height)
        }

        // Torso (shoulder line), then each arm shoulder→elbow→wrist.
        drawLine(Color.White.copy(alpha = 0.7f), at(k.leftShoulder), at(k.rightShoulder), strokeWidth = 6f)
        for ((a, b, c, color) in
            listOf(
                Quad(k.leftShoulder, k.leftElbow, k.leftWrist, leftColor),
                Quad(k.rightShoulder, k.rightElbow, k.rightWrist, rightColor),
            )) {
            drawLine(color, at(a), at(b), strokeWidth = 8f)
            drawLine(color, at(b), at(c), strokeWidth = 8f)
        }
        for ((p, color) in
            listOf(
                k.leftShoulder to leftColor, k.leftElbow to leftColor, k.leftWrist to leftColor,
                k.rightShoulder to rightColor, k.rightElbow to rightColor, k.rightWrist to rightColor,
            )) {
            drawCircle(color, radius = 10f, center = at(p))
        }
    }
}

/** Carries the two arm joints + color for one limb through the overlay loop. */
private data class Quad(val a: Keypoint, val b: Keypoint, val c: Keypoint, val color: Color)

/**
 * The committed output (#4.5) as the Learn screen's hero — the debounced text the
 * committer emits, large and centered. Empty shows a gentle hint; non-empty shows
 * the text plus Clear (FR5). Ellipsis keeps the layout stable as the string grows.
 */
@Composable
private fun CommittedHero(text: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (text.isEmpty()) {
            Text(
                "Sign a letter to begin",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 18.sp,
            )
        } else {
            Text(
                text,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Clear",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onClear).padding(8.dp),
            )
        }
    }
}

/** Per-frame readout: position ids per arm, emitted character, decoder mode. */
@Composable
private fun Readout(state: PreviewState, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "L ${idText(state.leftId)}   R ${idText(state.rightId)}",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                if (state.mode == Mode.NUMERIC) "NUMERIC" else "LETTERS",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "● L   ● R",
                fontSize = 11.sp,
                color = leftColor, // legend: cyan = signer's left arm, orange = right
            )
        }
        Text(
            displayChar(state.character),
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun idText(id: Int?): String = id?.toString() ?: "—"

/** `" "` (REST/space) and `""` (indeterminate) need visible glyphs. */
private fun displayChar(c: String): String =
    when {
        c == " " -> "␣"
        c.isEmpty() -> "·"
        else -> c
    }

@Composable
private fun Message(title: String, detail: String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(detail, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }
    }
}
