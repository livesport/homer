package homer.app.runners.external

import homer.app.exceptions.RunnerRegistrationRejectedException
import homer.app.models.RunnerConfig
import homer.app.models.RunnerTagValidation
import homer.app.runners.queue.RunnerClaimQueue
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The version boundary as a property rather than examples: the value is runner-written text that
 * reaches a log line and an operator view, so whatever the instance sends, the domain either
 * stores it verbatim or refuses it with a message that cannot carry it. Example tests pin the
 * shapes the implementation was written around; this pins the ones it was not. See root §6, §12.
 */
class ExternalRunnerVersionValidationPropertyTest {
    private fun domain() = ExternalRunnerDomain("workers", RunnerConfig.External(token = "t".repeat(32)), RunnerClaimQueue(maxPending = 10))

    @Test
    fun `a registration either stores the reported version verbatim or refuses it without echoing it`() =
        runTest {
            checkAll(Arb.string()) { reported ->
                val d = domain()
                val registered = runCatching { d.register(RegisterInstanceRequest(version = reported)) }
                when {
                    reported.isBlank() -> {
                        assertNull(registered.getOrNull()?.version, "a blank version is stored as absent, not rejected")
                    }

                    RunnerTagValidation.PATTERN.matches(reported) -> {
                        assertEquals(reported, registered.getOrNull()?.version, "an accepted version is stored verbatim")
                    }

                    else -> {
                        val ex = registered.exceptionOrNull()
                        assertIs<RunnerRegistrationRejectedException>(ex, "a version outside the tag shape is refused: $reported")
                        assertEquals(
                            "Runner domain 'workers' rejected the registration: version must match ${RunnerTagValidation.PATTERN}",
                            ex.message,
                            "the refusal is a fixed message, so no part of the raw value can ride it",
                        )
                        assertTrue(d.instances().isEmpty(), "a refused version registers no instance")
                    }
                }
            }
        }
}
