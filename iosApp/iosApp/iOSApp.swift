import SwiftUI
import UIKit
import ComposeApp

/// Follows the app theme: light palettes get dark status-bar text and a light keyboard.
final class ThemeState: ObservableObject {
    @Published var dark = true
}

@main
struct iOSApp: App {
    @StateObject private var theme = ThemeState()

    var body: some Scene {
        WindowGroup {
            ComposeView(theme: theme)
                .ignoresSafeArea(.all)
                .preferredColorScheme(theme.dark ? .dark : .light)
                .statusBarHidden(false)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    let theme: ThemeState

    func makeUIViewController(context: Context) -> UIViewController {
        let vc = MainViewControllerKt.MainViewController(onDark: { dark in
            let isDark = dark.boolValue
            DispatchQueue.main.async { theme.dark = isDark }
        })
        vc.view.backgroundColor = UIColor(red: 0.043, green: 0.027, blue: 0.012, alpha: 1)
        return vc
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
