package com.skylinetrailcomputing.semaphore.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.skylinetrailcomputing.semaphore.core.Keypoints
import com.skylinetrailcomputing.semaphore.core.MlKitPoseAdapter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Front-camera capture → ML Kit body pose → adapter → [Keypoints], surfaced as a
 * [Flow] (Issue #22, spec §3.1/§3.2, FR1/FR2). The Android counterpart to iOS's
 * `PoseCaptureSession` (which surfaces an `AsyncStream`).
 *
 * **Not unit-tested.** There is no camera in the local JVM unit-test runtime, so
 * this path is compiled and reviewed but its live orientation + mirror behaviour
 * is an on-device smoke item. The *math* it feeds is pinned by
 * `MlKitCalibrationTest`. The `ImageAnalysis` stream is left **non-mirrored**
 * (CameraX does not mirror analysis frames; we add no mirror), so the live buffer
 * matches the observer-perspective fixtures the adapter is calibrated against and
 * the single mirror in [MlKitPoseAdapter] is correct for both — the parallel of
 * iOS keeping `isVideoMirrored = false`.
 *
 * Each [keypoints] collection owns its own detector + use case and tears down
 * exactly those on cancellation (mirroring iOS's fresh-per-`start()` actor), so
 * the session is safely re-collectable. The live debug screen ([3.5], #23)
 * passes a [Preview] use case so the camera preview and the analysis stream bind
 * to the same front camera together — the CameraX analogue of iOS sharing one
 * `AVCaptureSession` between the preview layer and the data output.
 */
class PoseCaptureSession(
    private val context: Context,
    private val adapter: MlKitPoseAdapter = MlKitPoseAdapter(),
) {
    /**
     * Bind a front-camera [ImageAnalysis] use case (and, for the live screen, an
     * optional [preview] use case) to [lifecycleOwner] and emit adapted
     * [Keypoints] for every frame that yields a full upper-body skeleton. The
     * flow unbinds *its* use cases and closes its detector when the collector is
     * cancelled. [preview] is null in headless contexts (no display surface).
     */
    @ExperimentalGetImage
    fun keypoints(
        lifecycleOwner: LifecycleOwner,
        preview: Preview? = null,
    ): Flow<Keypoints> = callbackFlow {
        val executor = ContextCompat.getMainExecutor(context)
        val detector =
            PoseDetection.getClient(
                PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
            )
        val analysis =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

        analysis.setAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null) {
                proxy.close()
                return@setAnalyzer
            }
            val rotation = proxy.imageInfo.rotationDegrees
            // ML Kit returns landmarks in the upright (rotation-applied) frame, so
            // normalize by the upright dimensions: width/height swap at 90°/270°.
            val upright = rotation == 90 || rotation == 270
            val normWidth = if (upright) proxy.height else proxy.width
            val normHeight = if (upright) proxy.width else proxy.height
            detector
                .process(InputImage.fromMediaImage(media, rotation))
                .addOnSuccessListener { pose ->
                    pose.toMlKitSkeleton()?.let { skeleton ->
                        trySend(adapter.adapt(skeleton, normWidth, normHeight))
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }

        // Suspends rather than blocking the collector's thread (awaitInstance vs
        // the blocking Future.get()), so a Main-dispatched collector can't ANR.
        val useCases = listOfNotNull(preview, analysis).toTypedArray()
        val provider = ProcessCameraProvider.awaitInstance(context)
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases)

        awaitClose {
            provider.unbind(*useCases)
            detector.close()
        }
    }
}
