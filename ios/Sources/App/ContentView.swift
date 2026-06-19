import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "flag.2.crossed.fill")
                .font(.largeTitle)
            Text("Semaphore Translator")
                .font(.headline)
            Text("Point the camera at a signer — coming soon.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
