import Foundation

/// One bundled sight-read stock passage (Epic 6a, #71 / 6a-3). The Android twin is
/// `StockPassage` in `ui/StockPassages.kt`.
struct StockPassage: Decodable, Identifiable, Equatable {
    let id: String
    /// A content-neutral teaser shown in the picker — **never** the passage text.
    /// The drill HUD reveals one target at a time; that one-letter-at-a-time reveal
    /// is what "sight-read" means.
    let hint: String
    /// The drill passage, authored already-clean (`sanitize(text) == text`). Still
    /// run through `PassageSource.sanitize` on use so there is a single code path.
    let text: String
}

/// The bundled sight-read stock passages, loaded verbatim from
/// `shared/stock_passages.json` (bundled as an app resource) so the list is
/// identical to Android's. Mirrors `DisclaimerDocument`'s bundle-resource posture;
/// the Android twin is `StockPassages` in `ui/StockPassages.kt`.
struct StockPassages: Decodable, Equatable {
    let passages: [StockPassage]

    enum LoadError: Error { case missingResource(String) }

    /// Load + decode the stock passages bundled with the app. Throws if the resource
    /// is missing or malformed — a build-packaging bug. Unlike the disclaimer gate
    /// (which fails closed), the sight-read source fails **soft**: the picker shows
    /// an "unavailable" state and the custom-passage source stays usable.
    static func loadBundled(bundle: Bundle = .main) throws -> StockPassages {
        guard let url = bundle.url(forResource: "stock_passages", withExtension: "json") else {
            throw LoadError.missingResource("stock_passages.json")
        }
        let decoded = try JSONDecoder().decode(StockPassages.self, from: Data(contentsOf: url))
        // Defence-in-depth: drop any entry whose text isn't a valid drill passage
        // (empty, not already-clean, or over the cap) so a malformed bundled asset
        // fails soft per-entry rather than starting an empty/garbled drill. The
        // checked-in file is asserted well-formed by `SanitizeParityTests`; this
        // guards a future edit that ships without the tests.
        return StockPassages(passages: decoded.passages.filter(isValid))
    }

    /// A stock entry is usable iff its text sanitises to itself (already-clean),
    /// is non-empty, and is within the target cap — the same invariants the
    /// generator + parity tests assert on the checked-in file.
    static func isValid(_ passage: StockPassage) -> Bool {
        let sanitized = PassageSource.sanitize(passage.text)
        return !sanitized.isEmpty && sanitized == passage.text
            && sanitized.count <= PassageSource.maxTargets
    }
}
