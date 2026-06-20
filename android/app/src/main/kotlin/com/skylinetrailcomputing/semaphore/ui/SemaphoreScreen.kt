package com.skylinetrailcomputing.semaphore.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
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
 * The live debug screen ([3.5], #23): camera preview + 6-keypoint skeleton
 * overlay + per-frame decoded readout, plus the "no signer detected" state
 * (NFR4). The human smoke surface for "is the mirror actually right in the live
 * app" — there is no PR-preview deploy, so this on-device overlay is the only
 * visual confirmation of the adapter flips (autonomy guardrail-d). The Android
 * counterpart to iOS's SwiftUI `ContentView`.
 */
@Composable
fun SemaphoreScreen() {
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
        else -> CameraScreen()
    }
}

/** State the overlay + readout panel render. Raw per-frame — no smoothing. */
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
private fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    val decoder = remember { runCatching { ContractLoader.makeDecoder(context) }.getOrNull() }
    if (decoder == null) {
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

    // Build the Preview use case once and point it at the PreviewView's surface,
    // then collect the keypoint stream bound to the same camera. Per-frame decode,
    // no smoothing/commit (Epic 4). Mode is threaded across frames because that is
    // the decoder's own state machine (spec §4.5), not temporal smoothing.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        var mode = Mode.LETTERS
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
                    state = state.copy(signerPresent = false, keypoints = null)
                }
            }
        }

        capture.keypoints(lifecycleOwner, preview).collectLatest { kp ->
            lastFrameAt = System.currentTimeMillis()
            val result = decoder.decodeFrame(kp, mode)
            mode = result.mode
            state =
                PreviewState(
                    keypoints = kp,
                    leftId = result.ids[0],
                    rightId = result.ids[1],
                    character = result.emit,
                    mode = mode,
                    signerPresent = true,
                )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        SkeletonOverlay(state.keypoints, Modifier.fillMaxSize())
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
        Readout(state, Modifier.align(Alignment.BottomCenter).padding(16.dp))
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
 * **signer's** perspective (`+x` = signer's right). `PreviewView` shows the front
 * camera **mirrored** (the natural selfie view — its default), which is the
 * signer's-perspective view, so `+x` already matches screen-right; only the y-up
 * needs undoing for the top-left display origin:
 *   - signer's perspective ↔ mirrored preview:  `screen_x = kp.x · W`
 *   - undo y-up:                                 `screen_y = (1 − kp.y) · H`
 * (Verified on a Pixel 9a, #23: with `(1 − x)` the overlay was flipped relative
 * to the mirrored preview; `x` lands it on the limbs.) This is purely a *display*
 * transform; it never touches the adapter mirror. If the live overlay ever lands
 * flipped/rotated, this mapping and the `PreviewView` mirroring/rotation are the
 * knobs (tuned in lockstep) — a second adapter flip is never the fix (it would
 * desync the platforms and the parity harness). Registration is approximate under
 * FILL_CENTER cropping, fine for the orientation/mirror smoke this screen exists for.
 */
@Composable
private fun SkeletonOverlay(keypoints: Keypoints?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val k = keypoints ?: return@Canvas
        fun at(p: Keypoint) = Offset(p.x.toFloat() * size.width, (1f - p.y.toFloat()) * size.height)

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
