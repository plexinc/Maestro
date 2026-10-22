import FlyingFox
import Foundation
import XCTest

struct OpenUrlRequest: Codable {
    let url: String
}

/// Opens a URL as the system does for a tapped link: the app that handles it
/// opens, already running or not.
@MainActor
struct OpenUrlHandler: HTTPHandler {
    func handleRequest(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let body = try? await JSONDecoder().decode(OpenUrlRequest.self, from: request.bodyData),
              let url = URL(string: body.url) else {
            return AppError(type: .precondition, message: "Incorrect request body for OpenUrl Handler").httpResponse
        }

        #if os(iOS)
        if #available(iOS 16.4, *) {
            XCUIDevice.shared.system.open(url)
            return HTTPResponse(statusCode: .ok)
        }
        #endif

        return AppError(type: .precondition, message: "Opening a URL needs iOS 16.4 or newer").httpResponse
    }
}
