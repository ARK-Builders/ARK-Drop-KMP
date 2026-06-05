import SwiftUI
import Shared
import FirebaseCore

@main
struct iOSApp: App {
    init() {
        // Initialize Firebase, app configuration and DI
        if Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil {
            FirebaseApp.configure()
        }
        AppConfiguration.shared.initialize()
    }
    
    var body: some Scene {
        WindowGroup {
            AppRootView()
        }
    }
}

struct AppRootView: View {
    @StateObject private var coordinator = NavigationCoordinator()
    
    var body: some View {
        HomeView()
            .environmentObject(coordinator)
            .preferredColorScheme(.light) // Force light mode for consistency
    }
}
