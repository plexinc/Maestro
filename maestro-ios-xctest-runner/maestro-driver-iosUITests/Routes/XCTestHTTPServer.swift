import FlyingFox
import Foundation

enum Route: String, CaseIterable {
    case runningApp
    case swipe
    case swipeV2
    case inputText
    case touch
    case screenshot
    case isScreenStatic
    case pressKey
    case pressButton
    case eraseText
    case deviceInfo
    case setOrientation
    case setAppearance
    case appearance
    case setPermissions
    case viewHierarchy
    case status
    case keyboard
    case launchApp
    case terminateApp
    case openUrl

    func toHTTPRoute() -> HTTPRoute {
        return HTTPRoute(rawValue)
    }
}

struct XCTestHTTPServer {
    func start() async throws {
        let port = ProcessInfo.processInfo.environment["PORT"]?.toUInt16()
            ?? ProcessInfo.processInfo.environment["TEST_RUNNER_PORT"]?.toUInt16()
        let server = try makeServer(port: port ?? 22087)
        
        for route in Route.allCases {
            let handler = await RouteHandlerFactory.createRouteHandler(route: route)
            await server.appendRoute(route.toHTTPRoute(), to: handler)
        }
        
        try await server.run()
    }

    private func makeServer(port: UInt16) throws -> HTTPServer {
        #if os(tvOS) && !targetEnvironment(simulator)
        if let host = ProcessInfo.processInfo.environment["HOST"] {
            return HTTPServer(address: try .inet6(ip6: host, port: port), timeout: 100)
        }
        #endif
        return HTTPServer(address: try .inet(ip4: "127.0.0.1", port: port), timeout: 100)
    }
}
