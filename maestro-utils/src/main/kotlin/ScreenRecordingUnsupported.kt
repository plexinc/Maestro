package maestro.utils

/**
 * The target platform has no way to capture video. Callers decide whether to degrade (skip the
 * recording and carry on) or fail, so this is reported uniformly instead of per-driver ad-hoc
 * `TODO()`s, `error()`s and silent no-ops.
 */
class ScreenRecordingUnsupported(target: String) :
    UnsupportedOperationException("Screen recording is not supported on $target")
