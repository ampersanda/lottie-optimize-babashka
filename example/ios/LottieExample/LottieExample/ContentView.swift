import SwiftUI
import Lottie

struct ContentView: View {
    var body: some View {
        LottieView(animation: .named("confetti"))
            .playbackMode(.playing(.toProgress(1, loopMode: .loop)))
            .ignoresSafeArea()
    }
}

#Preview {
    ContentView()
}
