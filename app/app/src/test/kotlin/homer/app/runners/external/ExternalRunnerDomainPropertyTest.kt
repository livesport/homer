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
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Property-based companion to [ExternalRunnerDomainTest] (root §6): the version shape is swept, not sampled. */
class ExternalRunnerDomainPropertyTest {
    @Test
    fun `a version outside the tag shape is refused and stores nothing, any shape-conforming one is stored`() =
        runTest {
            checkAll(Arb.string(0..200)) { raw ->
                val queue = RunnerClaimQueue(maxPending = 10)
                val d = ExternalRunnerDomain("workers", RunnerConfig.External(token = "t"), queue)
                if (raw.isNotBlank() && !RunnerTagValidation.PATTERN.matches(raw)) {
                    assertThrows<RunnerRegistrationRejectedException> { d.register(RegisterInstanceRequest(version = raw)) }
                    assertTrue(d.instances().isEmpty(), "a refused version stores no instance: '$raw'")
                    assertTrue(queue.consumers().isEmpty(), "a refused version leaves no queue consumer: '$raw'")
                } else {
                    val inst = d.register(RegisterInstanceRequest(version = raw))
                    assertEquals(raw.takeIf { it.isNotBlank() }, inst.version, "raw: '$raw'")
                }
            }
        }
}
