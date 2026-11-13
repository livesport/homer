package homer.runner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunnerVersionTest {
    @Test
    fun `a release tag is reported as it is, trimmed`() {
        assertEquals("1.42.0", RunnerVersion.parse("1.42.0"))
        assertEquals("1.42.0", RunnerVersion.parse("  1.42.0\n"))
        assertEquals("v1.42.0-rc.1", RunnerVersion.parse("v1.42.0-rc.1"))
        assertEquals("dev", RunnerVersion.parse("dev"))
    }

    @Test
    fun `anything that is not a safe release token falls back to dev`() {
        for (raw in listOf(null, "", "   ", "1.42 .0", "1.42.0\nboom", "1.4\u00002.0", "-1.42.0", "1" + "0".repeat(64))) {
            assertEquals("dev", RunnerVersion.parse(raw), "'$raw' must not be reported as a version")
        }
    }

    @Test
    fun `current is the version this build stamped into the jar`() {
        // A test build passes no -PhomerVersion, so the build's fallback is what it reports;
        // the point is that it is a real build value, never the old hardcoded constant.
        assertEquals("dev", RunnerVersion.current)
        assertTrue(RunnerVersion.PATTERN.matches(RunnerVersion.current))
    }
}
