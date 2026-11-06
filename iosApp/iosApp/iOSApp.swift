import SwiftUI
import UIKit
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                .preferredColorScheme(.dark)
                .statusBarHidden(false)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let vc = MainViewControllerKt.MainViewController()
        vc.view.backgroundColor = UIColor(red: 0.043, green: 0.027, blue: 0.012, alpha: 1)
        return vc
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
