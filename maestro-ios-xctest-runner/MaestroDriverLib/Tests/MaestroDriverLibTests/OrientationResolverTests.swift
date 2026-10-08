import XCTest
@testable import MaestroDriverLib

final class OrientationResolverTests: XCTestCase {

    func testLandscapeAppOnUprightDevice_followsTheApp() {
        // A player locked to landscapeRight while the device stays portrait.
        XCTAssertEqual(
            OrientationResolver.deviceOrientation(appInterfaceOrientation: 4, deviceOrientation: 1),
            3
        )
        XCTAssertEqual(
            OrientationResolver.deviceOrientation(appInterfaceOrientation: 3, deviceOrientation: 1),
            4
        )
    }

    func testPortraitAppOnTurnedDevice_followsTheApp() {
        XCTAssertEqual(
            OrientationResolver.deviceOrientation(appInterfaceOrientation: 1, deviceOrientation: 3),
            1
        )
    }

    func testMatchingOrientations_areUnchanged() {
        XCTAssertEqual(
            OrientationResolver.deviceOrientation(appInterfaceOrientation: 1, deviceOrientation: 1),
            1
        )
        XCTAssertEqual(
            OrientationResolver.deviceOrientation(appInterfaceOrientation: 4, deviceOrientation: 3),
            3
        )
    }

    func testUnreadableAppOrientation_fallsBackToTheDevice() {
        XCTAssertEqual(
            OrientationResolver.deviceOrientation(appInterfaceOrientation: nil, deviceOrientation: 3),
            3
        )
        XCTAssertEqual(
            OrientationResolver.deviceOrientation(appInterfaceOrientation: 0, deviceOrientation: 1),
            1
        )
    }
}
