package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.Alphabet
import com.skylinetrailcomputing.semaphore.core.AssistGeometry
import com.skylinetrailcomputing.semaphore.core.AssistPose
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "light shared check" for the contract-derived assist figure (#73 / 6a-5):
 * the rendered pose must match the frozen `semaphore_alphabet.json` angles. Two
 * halves — the target→angles mapping and the signer-frame-angle → screen-direction
 * layout the `Canvas` draws.
 *
 * The geometry [AssistGeometry] is built from a `SharedFiles`-parsed alphabet (the
 * same Gson path as [ParityTest]/[DrillParityTest]) so it runs in the fast JVM
 * `src/test` set with no `Context`. The thin `org.json` asset wrapper
 * `ContractLoader.makeAssistGeometry(context)` shares `makeDecoder`'s
 * instrumented-only coverage gap (#37). The Swift twin is `AssistGeometryTests`;
 * both assert the same numbers read from the same `shared/` file, so the two
 * figures agree by construction.
 */
class AssistGeometryTest {
    private fun geometry(): AssistGeometry {
        val alphabet = SharedFiles.load<Alphabet>("semaphore_alphabet.json")
        val octantAngles = alphabet.positionModel.positions.values.associate { it.id to it.angleDeg }
        val letterPairs =
            buildMap {
                alphabet.letters.forEach { (symbol, ids) -> put(symbol, ids.left to ids.right) }
                put("REST", alphabet.controlSignals.rest.left to alphabet.controlSignals.rest.right)
            }
        return AssistGeometry(octantAngles, letterPairs, alphabet.numericMode.digitMap)
    }

    // --- pose() against the contract angles ---

    @Test
    fun letterPosesMatchContractAngles() {
        val g = geometry()
        // A = (left 0 = -90 down, right 1 = -45 down-right)
        assertEquals(AssistPose(-90.0, -45.0), g.pose('A'))
        // R = (left 6 = 180 out-left, right 2 = 0 out-right)
        assertEquals(AssistPose(180.0, 0.0), g.pose('R'))
        // U = (left 5 = 135 up-left, right 3 = 45 up-right)
        assertEquals(AssistPose(135.0, 45.0), g.pose('U'))
        // G = (left 7 = -135 down-left, right 0 = -90 down)
        assertEquals(AssistPose(-135.0, -90.0), g.pose('G'))
    }

    @Test
    fun lowercaseTargetMapsLikeUppercase() {
        val g = geometry()
        assertEquals(g.pose('A'), g.pose('a'))
    }

    @Test
    fun digitTargetsReverseTheDigitMap() {
        val g = geometry()
        assertEquals(g.pose('A'), g.pose('1'))
        assertEquals(g.pose('G'), g.pose('7'))
        // K = 0 is the only non-sequential digit mapping — exercise it explicitly.
        assertEquals(g.pose('K'), g.pose('0'))
    }

    @Test
    fun spaceIsRest() {
        assertEquals(AssistPose(-90.0, -90.0), geometry().pose(' '))
    }

    @Test
    fun unsupportedTargetsHaveNoPose() {
        val g = geometry()
        assertNull(g.pose('!'))
        assertNull(g.pose(null))
    }

    // --- endpoints(): signer-frame angle → screen direction (mirrored front) ---

    @Test
    fun endpointsMapAnglesToScreenDirections() {
        // Out-right (0°): wrist to screen-right of the shoulder, same height.
        val outRight = AssistGeometry.endpoints(AssistPose(0.0, 0.0))
        assertTrue(outRight.rightWrist.x > outRight.rightShoulder.x)
        assertEquals(outRight.rightShoulder.y, outRight.rightWrist.y, 1e-9)
        // Up (90°): smaller screen-y (display origin is top-left).
        val up = AssistGeometry.endpoints(AssistPose(90.0, 90.0))
        assertTrue(up.rightWrist.y < up.rightShoulder.y)
        // Out-left (180°): wrist to screen-left of the shoulder.
        val outLeft = AssistGeometry.endpoints(AssistPose(180.0, 180.0))
        assertTrue(outLeft.rightWrist.x < outLeft.rightShoulder.x)
        // Down (-90°): larger screen-y.
        val down = AssistGeometry.endpoints(AssistPose(-90.0, -90.0))
        assertTrue(down.rightWrist.y > down.rightShoulder.y)
    }

    @Test
    fun rightShoulderIsScreenRightUnderMirroredFront() {
        // The signer's right shoulder sits at greater screen-x than the left — the
        // mirrored-selfie convention the user copies (same as SkeletonOverlay).
        val pts = AssistGeometry.endpoints(AssistPose(-90.0, -90.0))
        assertTrue(pts.rightShoulder.x > pts.leftShoulder.x)
    }
}
