package com.skylinetrailcomputing.semaphore

import com.skylinetrailcomputing.semaphore.core.SemaphoreConfig
import com.skylinetrailcomputing.semaphore.core.SharedFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards that every frozen constant in `semaphore_config.json` (spec §4.4) is
 * parsed, not silently dropped. The temporal-commit constants were added in #31
 * (ADR 0004); this pins their values so a contract drift -- or a DTO that forgets
 * a field again -- fails loudly. The Swift `ConfigContractTests` asserts the same
 * values, so the two platforms read one contract identically.
 */
class ConfigContractTest {
    @Test
    fun allConstantsParse() {
        val config = SharedFiles.load<SemaphoreConfig>("semaphore_config.json")
        assertEquals(20.0, config.angleToleranceDeg, 0.0)
        assertEquals(0.5, config.minKeypointConfidence, 0.0)
        assertEquals(600.0, config.commitHoldMs, 0.0)
        assertEquals(5, config.smoothingWindow)
        assertEquals(300.0, config.interCharGapMs, 0.0)
    }
}
