import CryptoKit
import Foundation

/// Persisted first-launch-consent keys ([6b-9], #87). Centralized like
/// `AppSettingsKeys` so the writer (`RootView` on accept) and any future reader
/// (e.g. the 6b-4 About surface) bind to the *same* `UserDefaults` keys. The
/// Android twin is the `disclaimer_*` keys in `AppSettings`.
enum DisclaimerKeys {
    /// SHA-256 hex of the disclaimer doc the user last accepted. Empty until the
    /// first acceptance. Compared against the bundled doc's hash to decide whether
    /// to re-show the gate (a doc edit changes the hash → re-prompt).
    static let acceptedHash = "disclaimerAcceptedHash"
    /// Wall-clock epoch seconds when consent was recorded. Local-only, informational.
    static let acceptedAt = "disclaimerAcceptedAt"
}

/// The first-launch disclaimer, loaded from the bundled `shared/disclaimer.json`
/// (#87). The displayed fields are decoded from JSON; `contentHash` is computed
/// from the file's **raw bytes**, so it is identical to Android's hash over the
/// same `shared/` file and changes whenever the doc is edited — that hash is the
/// consent key (`RootView` re-shows the gate when it stops matching the stored
/// one). Mirrors `ContractLoader`'s bundle-resource posture; the Android twin is
/// `DisclaimerDocument` in `ui/DisclaimerGate.kt`.
struct DisclaimerDocument: Equatable {
    let version: String
    let title: String
    let body: [String]
    let agreement: String
    let acceptLabel: String
    let eula: URL
    let privacy: URL
    /// SHA-256 hex over the raw doc bytes — the consent key.
    let contentHash: String

    enum LoadError: Error { case missingResource(String) }

    /// Load + decode the disclaimer bundled with the app. Throws if the resource
    /// is missing or malformed — a build-packaging bug, which `RootView` surfaces
    /// as a fail-closed blocking screen rather than skipping the gate.
    static func loadBundled(bundle: Bundle = .main) throws -> DisclaimerDocument {
        guard let url = bundle.url(forResource: "disclaimer", withExtension: "json") else {
            throw LoadError.missingResource("disclaimer.json")
        }
        return try decode(Data(contentsOf: url))
    }

    /// Decode a disclaimer doc from its raw JSON bytes and stamp it with the
    /// SHA-256 of those exact bytes. Factored out from `loadBundled` so tests can
    /// drive it from the `shared/` source and assert the hash is deterministic
    /// and matches what the app computes on-device.
    static func decode(_ data: Data) throws -> DisclaimerDocument {
        let payload = try JSONDecoder().decode(Payload.self, from: data)
        return DisclaimerDocument(
            version: payload.version,
            title: payload.title,
            body: payload.body,
            agreement: payload.agreement,
            acceptLabel: payload.acceptLabel,
            eula: payload.links.eula,
            privacy: payload.links.privacy,
            contentHash: sha256Hex(data))
    }

    /// The gate decision: re-show whenever the stored accepted hash doesn't match
    /// the bundled doc's hash. Empty stored hash (never accepted) → show; a doc
    /// edit (new hash) → show; otherwise → skip. Pure, so it's unit-tested without
    /// a device. The Android twin is `DisclaimerDocument.needsConsent`.
    static func needsConsent(acceptedHash: String, documentHash: String) -> Bool {
        acceptedHash != documentHash
    }

    /// Lowercase hex SHA-256 of `data`. Matches Android's `MessageDigest("SHA-256")`
    /// hex over the same bytes, so both platforms derive the same consent key.
    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// Decoded JSON payload (the displayed fields only). `contentHash` is not a
    /// JSON field — it's computed from the raw bytes in `decode`.
    private struct Payload: Decodable {
        let version: String
        let title: String
        let body: [String]
        let agreement: String
        let acceptLabel: String
        let links: Links

        struct Links: Decodable {
            let eula: URL
            let privacy: URL
        }

        enum CodingKeys: String, CodingKey {
            case version, title, body, agreement, links
            case acceptLabel = "accept_label"
        }
    }
}
