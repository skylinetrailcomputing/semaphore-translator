package com.skylinetrailcomputing.semaphore.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import com.skylinetrailcomputing.semaphore.core.AssistGeometry
import com.skylinetrailcomputing.semaphore.core.AssistPoint
import com.skylinetrailcomputing.semaphore.core.AssistPose
import com.skylinetrailcomputing.semaphore.core.Committer
import com.skylinetrailcomputing.semaphore.core.ContractLoader
import com.skylinetrailcomputing.semaphore.core.DrillSession
import com.skylinetrailcomputing.semaphore.core.Keypoint
import com.skylinetrailcomputing.semaphore.core.Keypoints
import com.skylinetrailcomputing.semaphore.core.Mode
import com.skylinetrailcomputing.semaphore.capture.PoseCaptureSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The shared camera+decode screen — driven by **Learn** (front lens, #48) and
 * **Interpret** (rear lens, #46/#60), which differ only by [cameraLens] + the
 * lens-derived display mirror and the empty-state [emptyHint]. The committed text
 * is the visual hero; the "no signer detected" state (NFR4) and clear/reset (FR5)
 * are kept.
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
    emptyHint: String = "Sign a letter to begin",
    drillTargets: String? = null,
    showAssist: Boolean = false,
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
                "Semaphore Translator reads semaphore flag positions from the camera " +
                    "on-device. Frames are processed live and never stored or transmitted. " +
                    "Grant camera access to use the live preview.",
            )
        else -> CameraScreen(developerMode, cameraLens, emptyHint, drillTargets, showAssist)
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
private fun CameraScreen(
    developerMode: Boolean,
    cameraLens: CameraSelector,
    emptyHint: String,
    drillTargets: String?,
    showAssist: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    val decoder = remember { runCatching { ContractLoader.makeDecoder(context) }.getOrNull() }
    // Contract-derived assist-figure geometry (#73, 6a-5), loaded only for drill
    // screens. A parse failure is non-fatal — the figure just doesn't draw.
    val assistGeometry =
        remember(drillTargets) {
            if (drillTargets != null)
                runCatching { ContractLoader.makeAssistGeometry(context) }.getOrNull()
            else null
        }
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

    // Drill engine (6a-1, ADR 0007) for a passage-drill screen, or null for
    // free-form Learn/Interpret. Strictly downstream of the committer: it observes
    // only committed characters, never the decode/adapter/commit core. The HUD
    // snapshot + flash are hoisted alongside committedText so the frame loop can
    // republish them and the celebrate-card replay can reset them. The iOS twin is
    // `PreviewViewModel.drillHUD` / `drillFlash`.
    val drill = remember(drillTargets) { drillTargets?.let { DrillSession(it) } }
    var drillUi by
        remember(drill) {
            mutableStateOf(
                drill?.let { DrillUi(it.currentTarget, it.index, it.count, it.isComplete) })
        }
    // null = neutral, true = matched (green), false = miss (red). Cleared a beat
    // after each commit, keyed off [flashTick] so repeated same-value flashes retrigger.
    var drillFlash by remember(drill) { mutableStateOf<Boolean?>(null) }
    var flashTick by remember(drill) { mutableStateOf(0) }
    // Each commit bumps flashTick, cancelling the prior timer and restarting it, so
    // the highlight always clears ~450ms after the latest commit.
    androidx.compose.runtime.LaunchedEffect(flashTick) {
        if (flashTick > 0) {
            delay(450)
            drillFlash = null
        }
    }

    // Build the Preview use case once and point it at the PreviewView's surface,
    // then collect the keypoint stream bound to the same camera. The temporal
    // committer (#4.5) smooths/holds/debounces per frame; mode lives in the
    // committer and flips only on a committed control pose (§4.5), so it is no
    // longer threaded across frames here.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        var lastFrameAt = 0L
        // Dev-only coordinate probe (#58 / ADR 0006); throttled to one line per id change.
        var lastProbeIds: Pair<Int?, Int?>? = null

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
            if (emitted.isNotEmpty()) {
                committedText += emitted
                // Drill (6a-2, #70): feed the committed character to the engine.
                // Stay-until-success is the engine's own policy (a hit advances, a
                // miss is a no-op), so we just republish the HUD + flash the result.
                // No-op once complete, so post-celebrate commits don't flash. A rest
                // between letters commits a SPACE; don't penalise that as a miss
                // unless a space is actually the current target (6a-2 smoke nit) — a
                // rest is signing rhythm, not a wrong attempt. A space still advances
                // when the target IS a space (multi-word 6a-3 sources).
                if (drill != null && !drill.isComplete &&
                    !(emitted == " " && drill.currentTarget != ' ')) {
                    val step = drill.observe(emitted)
                    drillUi = DrillUi(drill.currentTarget, step.index, drill.count, step.complete)
                    drillFlash = step.matched
                    flashTick++
                }
            }

            // Raw white-box readout: ids are mode-independent; only the per-frame
            // character is interpreted, in the committer's (possibly just-flipped) mode.
            val raw = decoder.decodeFrame(kp, committer.currentMode)

            // Dev-only coordinate probe (#58 / ADR 0006): log the post-adapter,
            // signer's-perspective x of each shoulder/wrist + ids + char, once per
            // id change. The numeric backstop for the rear-camera mirror smoke: a
            // correctly-oriented read has the signer's right shoulder at greater x
            // than the left (R.sh.x > L.sh.x) and `wrist.x > shoulder.x` for an arm
            // extended to the signer's right; a flipped rear buffer inverts both.
            // Reads post-adapter coords on purpose — the adapter transform is
            // byte-pinned by the native fixtures, so any rear chirality fault shows
            // up here without instrumenting the quarantined capture path (spec §3.2).
            if (developerMode) {
                val ids = raw.ids[0] to raw.ids[1]
                if (ids != lastProbeIds) {
                    lastProbeIds = ids
                    val lens =
                        if (cameraLens.lensFacing == CameraSelector.LENS_FACING_FRONT) "front"
                        else "rear"
                    android.util.Log.d(
                        "SemaphoreProbe",
                        "lens=%s ids=[%s,%s] char=%s  L(sh.x=%.3f wr.x=%.3f) R(sh.x=%.3f wr.x=%.3f)"
                            .format(
                                lens,
                                raw.ids[0]?.toString() ?: "—",
                                raw.ids[1]?.toString() ?: "—",
                                raw.emit.ifEmpty { "·" },
                                kp.leftShoulder.x, kp.leftWrist.x,
                                kp.rightShoulder.x, kp.rightWrist.x,
                            ),
                    )
                }
            }
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
        if (!state.signerPresent && drillUi?.complete != true) {
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
        // Drill HUD hero (6a-2, #70) — the letter to sign next + progress, at top.
        // Hidden once complete; the celebration overlay takes over.
        drillUi?.let { ui ->
            if (!ui.complete) {
                // Structural front-lens gate (#73, 6a-5): the figure only reads
                // right over the mirrored selfie preview, so a non-front lens (a
                // hypothetical future rear drill) yields no pose. `showAssist` is
                // the user's on/off; `drillTargets != null` made `assistGeometry`.
                val isFront = cameraLens.lensFacing == CameraSelector.LENS_FACING_FRONT
                val assistPose =
                    if (showAssist && isFront) assistGeometry?.pose(ui.target) else null
                DrillTargetCard(
                    ui,
                    drillFlash,
                    assistPose,
                    Modifier.align(Alignment.TopCenter)
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Clear is the free-form reset; in a drill, "Practice again" on the
            // celebrate card is the reset path, so Clear is hidden to keep the
            // visible text from desyncing the drill's target index.
            CommittedHero(
                committedText,
                emptyHint,
                onClear = { committedText = "" },
                showClear = drill == null,
            )
            if (developerMode) Readout(state)
        }
        if (drillUi?.complete == true) {
            CelebrationOverlay(
                onReplay = {
                    drill?.reset()
                    committer.reset()
                    committedText = ""
                    drillFlash = null
                    drillUi =
                        drill?.let { DrillUi(it.currentTarget, it.index, it.count, it.isComplete) }
                },
                Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Snapshot of the drill HUD (6a-2, #70); mirrors the [DrillSession] getters. */
private data class DrillUi(
    val target: Char?,
    val index: Int,
    val count: Int,
    val complete: Boolean,
)

private const val SIGNER_TIMEOUT_MS = 500L
private val leftColor = Color.Cyan
private val rightColor = Color(0xFFFF9800)
private val assistBodyColor = Color.White.copy(alpha = 0.85f)

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
private fun CommittedHero(
    text: String,
    emptyHint: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    showClear: Boolean = true,
) {
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
                emptyHint,
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
            if (showClear) {
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
}

/**
 * The drill HUD hero (6a-2, #70): the letter to sign next, a progress bar through
 * the passage, and an `index / count` tally. The card flashes green on a matched
 * commit and red on a miss (stay-until-success — a miss never advances), then
 * settles back to neutral. The iOS twin is `ContentView.drillTargetCard`.
 */
@Composable
private fun DrillTargetCard(
    ui: DrillUi,
    flash: Boolean?,
    assistPose: AssistPose?,
    modifier: Modifier = Modifier,
) {
    val targetBg =
        when (flash) {
            true -> Color(0xFF2E7D32).copy(alpha = 0.85f) // green — matched
            false -> Color(0xFFC62828).copy(alpha = 0.80f) // red — miss
            null -> Color.Black.copy(alpha = 0.55f)
        }
    val bg by animateColorAsState(targetBg, label = "drillFlash")
    Column(
        modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Sign this letter", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        Text(targetGlyph(ui.target), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        // The contract-derived assist figure (#73, 6a-5): the pose to make for this
        // target, drawn at the exact alphabet angles. Non-null only when the caller's
        // setting + front-lens gate pass.
        if (assistPose != null) {
            AssistFigure(assistPose, Modifier.size(132.dp))
        }
        LinearProgressIndicator(
            progress = { if (ui.count == 0) 0f else ui.index.toFloat() / ui.count },
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${ui.index} / ${ui.count}",
            color = Color.White.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

/**
 * The contract-derived assist figure (#73 / 6a-5): a compact stick figure whose two
 * arms are drawn at the *exact* `semaphore_alphabet.json` angles for the current
 * drill target ([AssistGeometry.endpoints]), so the teaching aid is
 * perspective-correct by construction and can't drift from the contract. Left arm
 * cyan / right arm orange — the same legend as [SkeletonOverlay], in the same
 * mirrored-front convention so the user mirrors the pose directly. The iOS twin is
 * `AssistFigureView`.
 */
@Composable
private fun AssistFigure(pose: AssistPose, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pts = AssistGeometry.endpoints(pose)
        // Draw into a centered square so the arm angles are never distorted by a
        // non-square canvas (a 45° arm must look 45°).
        val side = minOf(size.width, size.height)
        val ox = (size.width - side) / 2f
        val oy = (size.height - side) / 2f
        fun at(p: AssistPoint) = Offset(ox + p.x.toFloat() * side, oy + p.y.toFloat() * side)

        // Body: neck→head + torso (neck→hip) + shoulder line + a stroked head.
        drawLine(assistBodyColor, at(pts.neck), at(pts.head), strokeWidth = 4f)
        drawLine(assistBodyColor, at(pts.neck), at(pts.hip), strokeWidth = 4f)
        drawLine(assistBodyColor, at(pts.leftShoulder), at(pts.rightShoulder), strokeWidth = 4f)
        drawCircle(assistBodyColor, radius = side * 0.06f, center = at(pts.head), style = Stroke(width = 4f))

        // Arms, colour-coded, with a dot at each wrist (the flag end).
        drawLine(leftColor, at(pts.leftShoulder), at(pts.leftWrist), strokeWidth = 7f, cap = StrokeCap.Round)
        drawLine(
            rightColor, at(pts.rightShoulder), at(pts.rightWrist), strokeWidth = 7f, cap = StrokeCap.Round)
        drawCircle(leftColor, radius = 6f, center = at(pts.leftWrist))
        drawCircle(rightColor, radius = 6f, center = at(pts.rightWrist))
    }
}

/**
 * The small celebration shown on COMPLETE (6a-2): a centered card with a replay
 * affordance that resets the drill to run the passage again. The iOS twin is
 * `ContentView.celebrationOverlay`.
 */
@Composable
private fun CelebrationOverlay(onReplay: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(24.dp))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("🎉", fontSize = 56.sp)
        Text("Passage complete!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(
            "Practice again",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier =
                Modifier.clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.2f))
                    .clickable(onClick = onReplay)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/** Current target as a glyph: `␣` for SPACE, `✓` once complete (no current target). */
private fun targetGlyph(target: Char?): String =
    when {
        target == null -> "✓"
        target == ' ' -> "␣"
        else -> target.toString()
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
