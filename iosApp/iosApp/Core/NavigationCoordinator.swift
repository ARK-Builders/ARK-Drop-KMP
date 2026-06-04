import SwiftUI
import Shared

enum Route: Hashable {
    case home
    case send
    case receive
    case history
    case editProfile
    case about
}

@MainActor
class NavigationCoordinator: ObservableObject {
    @Published var path = NavigationPath()
    private var routeStack: [Route] = []
    private var lastLoggedRoute: Route?

    init() {
        logScreenView(.home)
    }

    func navigate(to route: Route) {
        routeStack.append(route)
        path.append(route)
        logScreenView(route)
    }

    func navigateBack() {
        if !path.isEmpty {
            path.removeLast()
        }
        if !routeStack.isEmpty {
            routeStack.removeLast()
        }
        logScreenView(routeStack.last ?? .home)
    }
    
    func navigateToRoot() {
        path.removeLast(path.count)
        routeStack.removeAll()
        logScreenView(.home)
    }
    
    func replace(with route: Route) {
        if !routeStack.isEmpty {
            routeStack.removeLast()
        }
        routeStack.append(route)
        path = navigationPath(from: routeStack)
        logScreenView(route)
    }

    func reconcilePath(count: Int) {
        if count < routeStack.count {
            routeStack = Array(routeStack.prefix(count))
        }
        logScreenView(routeStack.last ?? .home)
    }

    private func navigationPath(from routes: [Route]) -> NavigationPath {
        var newPath = NavigationPath()
        routes.forEach { newPath.append($0) }
        return newPath
    }

    private func logScreenView(_ route: Route) {
        guard lastLoggedRoute != route else {
            return
        }
        lastLoggedRoute = route
        KoinHelper.shared.logScreenView(screenName: route.analyticsName)
    }
}

private extension Route {
    var analyticsName: String {
        switch self {
        case .home:
            return "home"
        case .send:
            return "send"
        case .receive:
            return "receive"
        case .history:
            return "history"
        case .editProfile:
            return "edit_profile"
        case .about:
            return "about"
        }
    }
}
