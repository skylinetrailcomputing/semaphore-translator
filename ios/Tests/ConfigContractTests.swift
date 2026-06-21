import XCTest

@testable import SemaphoreTranslator

/// Guards that every frozen constant in `semaphore_config.json` (spec §4.4) is
/// parsed, not silently dropped. The temporal-commit constants were added in #31
/// (ADR 0004); this pins their values so a contract drift — or a DTO that forgets
/// a field again — fails loudly. The Kotlin `ConfigContractTest` asserts the same
/// values, so the two platforms read one contract identically.
final class ConfigContractTests: XCTestCase {
    func testAllConstantsParse() throws {
        let config = try SharedFiles.load(SemaphoreConfig.self, "semaphore_config.json")
        XCTAssertEqual(config.angleToleranceDeg, 20)
        XCTAssertEqual(config.minKeypointConfidence, 0.5)
        XCTAssertEqual(config.commitHoldMs, 600)
        XCTAssertEqual(config.smoothingWindow, 5)
        XCTAssertEqual(config.interCharGapMs, 300)
    }
}
