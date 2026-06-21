import SwiftUI

/// Placeholder for the rear-camera **Interpret** mode ([5c]). The functional
/// decode (rear camera = the opposite mirror geometry, the §3.2 seam the parity
/// harness can't catch) is deferred to Epic 5.2 (#46); for now tapping Interpret
/// lands here. No camera starts. The Android twin is `InterpretStub`.
struct InterpretStubView: View {
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 12) {
                Image(systemName: "binoculars")
                    .font(.largeTitle)
                Text("Coming soon")
                    .font(.title2.bold())
                Text("Point the rear camera at someone signing and read their flags. Not built yet.")
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(.white)
            .padding(32)
        }
        .navigationTitle("Interpret")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack { InterpretStubView() }
}
