import SwiftUI
import UIKit
import LuminaUI

// The whole UI is Compose Multiplatform code from the :sharedUi Gradle module,
// linked as the static LuminaUI framework. Keep this host minimal: iOS logic
// belongs in Kotlin (sharedUi/src/iosMain).
@main
struct LuminaReaderApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(arguments: ProcessInfo.processInfo.arguments)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
