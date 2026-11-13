package homer.runner

import homer.app.models.RunnerTagValidation
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Property-based companion to [RunnerVersionTest] (root §6): the token shape is swept, not sampled. */
class RunnerVersionPropertyTest {
    @Test
    fun `parse reports a value exactly when its trim is a safe release token`() =
        runTest {
            checkAll(Arb.string(0..200)) { raw ->
                val trimmed = raw.trim()
                val parsed = RunnerVersion.parse(raw)
                if (RunnerTagValidation.PATTERN.matches(trimmed)) {
                    // "dev" is itself a valid token, so a release literally named dev is
                    // reported as itself — indistinguishable from the fallback, by design.
                    assertEquals(trimmed, parsed, "raw: '$raw'")
                } else {
                    assertEquals(RunnerVersion.FALLBACK, parsed, "raw: '$raw'")
                }
            }
        }

    @Test
    fun `a 64-character token is reported as it is and one character more falls back`() {
        assertEquals("1" + "0".repeat(63), RunnerVersion.parse("1" + "0".repeat(63)))
        assertEquals(RunnerVersion.FALLBACK, RunnerVersion.parse("1" + "0".repeat(64)))
    }
}
