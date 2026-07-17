package maestro.cli.api

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CliVersionTest {

    @Test
    fun `parses a 4-part version`() {
        val version = CliVersion.parse("2.6.1.3")

        assertThat(version).isEqualTo(CliVersion(2, 6, 1, 3))
    }

    @Test
    fun `parses a 3-part version with build defaulting to 0`() {
        // A pristine upstream release tag (no fork build segment).
        val version = CliVersion.parse("2.6.1")

        assertThat(version).isEqualTo(CliVersion(2, 6, 1, 0))
    }

    @Test
    fun `rejects malformed versions`() {
        assertThat(CliVersion.parse("2.6")).isNull()
        assertThat(CliVersion.parse("2.6.1.3.4")).isNull()
        assertThat(CliVersion.parse("2.6.x.3")).isNull()
    }

    @Test
    fun `toString renders all four segments`() {
        assertThat(CliVersion(2, 6, 1, 0).toString()).isEqualTo("2.6.1.0")
        assertThat(CliVersion(2, 6, 1, 3).toString()).isEqualTo("2.6.1.3")
    }

    @Test
    fun `baseVersion drops the build segment`() {
        assertThat(CliVersion(2, 6, 1, 3).baseVersion).isEqualTo("2.6.1")
    }

    @Test
    fun `build segment participates in comparison`() {
        assertThat(CliVersion(2, 6, 1, 1)).isGreaterThan(CliVersion(2, 6, 1, 0))
        assertThat(CliVersion(2, 6, 1, 0)).isEquivalentAccordingToCompareTo(CliVersion(2, 6, 1))
        // A higher base still wins regardless of build.
        assertThat(CliVersion(2, 7, 0, 0)).isGreaterThan(CliVersion(2, 6, 1, 9))
    }
}
