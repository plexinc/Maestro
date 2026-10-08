import Foundation

/// Picks the orientation the driver works in. The raw values are
/// UIDeviceOrientation and UIInterfaceOrientation, which tvOS does not have.
public enum OrientationResolver {

    /// The device orientation that matches what the app shows.
    ///
    /// An app can lock itself to landscape while the device stays upright, such
    /// as a video player. Its frames are then in landscape, so the screen size,
    /// the bounds filter and the tap transform must follow the app, not the
    /// device. Interface and device landscape values are swapped (Apple's
    /// convention): an interface in landscapeRight is a device in landscapeLeft.
    ///
    /// - Parameters:
    ///   - appInterfaceOrientation: the foreground app's interface orientation,
    ///     or nil when it can't be read.
    ///   - deviceOrientation: the device's own orientation.
    /// - Returns: the device orientation to use.
    public static func deviceOrientation(
        appInterfaceOrientation: Int?,
        deviceOrientation: Int
    ) -> Int {
        switch appInterfaceOrientation {
        case 1: return 1 // portrait
        case 2: return 2 // portraitUpsideDown
        case 3: return 4 // landscapeLeft interface: device landscapeRight
        case 4: return 3 // landscapeRight interface: device landscapeLeft
        default: return deviceOrientation
        }
    }
}
