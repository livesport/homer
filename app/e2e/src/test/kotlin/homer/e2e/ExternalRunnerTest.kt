package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.RunnerInstanceView
import homer.e2e.support.RunnerQueueView
import homer.e2e.support.RunnerRegisterResponse
import homer.e2e.support.RunnersView
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * External pull-mode runners (ADR-0066) against real homer-runner containers that share the
 * backend's network namespace (docker-compose.e2e.yml): `e2e-runner` serves `e2e-external`,
 * `e2e-crash-runner` serves `e2e-crash` and is killed by the crash test.
 */
class ExternalRunnerTest : E2eTestBase() {
    /** The registration shape a domain enforces on a `name` or a `version` (SERVER.md, runner protocol). */
    private val tagShape = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")

    private suspend fun externalInstances(): List<RunnerInstanceView> =
        HomerApi
            .decode<RunnersView>(E2e.client.get("/api/v1/runners"))
            .runners
            .filter { it.kind == "external" }
            .flatMap { it.instances.orEmpty() }

    private suspend fun instanceNames(): Set<String> = externalInstances().map { it.name }.toSet()

    private suspend fun awaitInstance(name: String) =
        Awaits.awaitNotNull("instance '$name' registered", timeoutMs = 60_000, pollMs = 500) {
            name.takeIf { it in instanceNames() }
        }

    private suspend fun awaitInstanceView(name: String): RunnerInstanceView =
        Awaits.awaitNotNull("instance '$name' registered", timeoutMs = 60_000, pollMs = 500) {
            externalInstances().firstOrNull { it.name == name }
        }

    @Test
    fun `a tagged agent runs on the external instance with the domain's extraUploads and its output and artifacts come back`() =
        runBlocking {
            awaitInstance("e2e-runner")
            val processId = HomerApi.startRun("external-echo")
            val info = Awaits.awaitTerminal(processId, timeoutMs = 60_000)

            assertEquals("FINISHED", info.status)
            val stdout = HomerApi.artifactText(processId, info.executions[0].stdOut, "cmd_0.out")
            assertTrue(stdout.contains("via homer-runner"), "stdout was: $stdout")
            assertTrue(stdout.contains("extra: e2e extra upload"), "the domain's extraUploads reach the instance: $stdout")
            assertTrue(
                stdout.contains("helper: executable"),
                "the .homer/bin helper must arrive executable so a run can call it by name: $stdout",
            )
            val artifact = HomerApi.artifact(processId, "nested/remote.txt")
            assertEquals(HttpStatusCode.OK, artifact.status)
            assertEquals("remote artifact", artifact.bodyAsText().trim())
        }

    @Test
    fun `a runnerCmd on the external domain gets its extraUploads before the checks run`() =
        runBlocking {
            awaitInstance("e2e-runner")
            val processId = HomerApi.startRun("runnercmd-external")
            val info = Awaits.awaitTerminal(processId, timeoutMs = 60_000)

            assertEquals("FINISHED", info.status, "the check needs the uploaded marker: $info")
            val stdout = HomerApi.artifactText(processId, info.executions[0].stdOut, "cmd_0.out")
            assertTrue(stdout.contains("runnercmd extra: e2e extra upload"), "stdout was: $stdout")
        }

    @Test
    fun `the admin runner endpoints list the instance and refuse scoped and domain tokens`() =
        runBlocking {
            awaitInstance("e2e-runner")
            val queue = HomerApi.decode<RunnerQueueView>(E2e.client.get("/api/v1/runners/queue"))
            assertTrue(queue.pending >= 0)

            for (path in listOf("/api/v1/runners", "/api/v1/runners/queue")) {
                val scoped = E2e.unauthClient.get(path) { header(HttpHeaders.Authorization, "Bearer echo-scoped-token") }
                assertEquals(HttpStatusCode.Forbidden, scoped.status, path)
                val domain =
                    E2e.unauthClient.get(path) {
                        header(HttpHeaders.Authorization, "Bearer e2e-external-domain-token-0123456789")
                    }
                assertEquals(HttpStatusCode.Unauthorized, domain.status, path)
            }
        }

    @Test
    fun `the status API reports the instance's self-declared version`() =
        runBlocking {
            val instance = awaitInstanceView("e2e-runner")

            // The reference runner's hardcoded version passes the same shape the domain
            // enforces at registration — a real runner's version registers, then surfaces here.
            val version = instance.version
            assertTrue(
                !version.isNullOrBlank() && tagShape.matches(version),
                "the registered version reaches GET /api/v1/runners in the tag shape: $instance",
            )
        }

    @Test
    fun `a registration with a version outside the tag shape is refused with 422`() =
        runBlocking {
            val response =
                E2e.unauthClient.post("/api/v1/runner/register") {
                    header(HttpHeaders.Authorization, "Bearer e2e-external-domain-token-0123456789")
                    // Full semver with build metadata — `+` is outside the accepted shape.
                    setBody(
                        TextContent(
                            """{"name":"e2e-bad-version","tags":["e2e-external"],"concurrency":1,"version":"1.42.0+build.5"}""",
                            ContentType.Application.Json,
                        ),
                    )
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue("1.42.0+build.5" !in body, "the refusal must not echo the raw version: $body")
            assertTrue("e2e-bad-version" !in instanceNames(), "a refused registration registers no instance")
        }

    @Test
    fun `killing a run cancels the remote process`() =
        // <Unit>: the block ends in awaitNotNull's String, and JUnit skips non-void @Test methods.
        runBlocking<Unit> {
            awaitInstance("e2e-runner")
            val processId = HomerApi.startRun("external-sleeper")
            Awaits.awaitStatus(processId, setOf("WORKING"), timeoutMs = 60_000)
            // The claim is WORKING before the op reaches the instance; wait until the sleep runs there.
            Awaits.awaitNotNull("sleeper running on the runner", timeoutMs = 30_000, pollMs = 1_000) {
                probe().takeIf { it == "sleeper=alive" }
            }

            assertEquals(HttpStatusCode.OK, HomerApi.kill(processId).status)
            assertEquals("KILLED", Awaits.awaitStatus(processId, setOf("KILLED"), timeoutMs = 15_000).status)
            Awaits.awaitNotNull<String>("remote sleeper gone after kill", timeoutMs = 30_000, pollMs = 1_000) {
                probe().takeIf { it == "sleeper=gone" }
            }
        }

    @Test
    fun `a runner that dies mid-run fails the run within the heartbeat TTL and drops the instance`() =
        runBlocking {
            awaitInstance("e2e-crash-runner")
            val processId = HomerApi.startRun("external-crash")
            // heartbeatTimeoutSec 35 + 5 s sweep + slack.
            val info = Awaits.awaitTerminal(processId, timeoutMs = 75_000)

            assertEquals("FAILED", info.status)
            assertTrue("e2e-crash-runner" !in instanceNames(), "the dead instance must be dropped")
        }

    @Test
    fun `a register request with a malformed version is refused with 422 and a well-formed one registers`() =
        // <Unit>: the block ends in awaitNotNull's String, and JUnit skips non-void @Test methods.
        runBlocking<Unit> {
            val domain = "Bearer e2e-external-domain-token-0123456789"

            suspend fun register(body: String) =
                E2e.client.post("/api/v1/runner/register") {
                    header(HttpHeaders.Authorization, domain)
                    setBody(TextContent(body, ContentType.Application.Json))
                }

            // A version a space or a 65th character disfigures must never reach storage.
            for (version in listOf("1.42 .0", "1" + "0".repeat(64))) {
                val refused = register("""{"name":"e2e-version-probe","version":"$version"}""")
                assertEquals(HttpStatusCode.UnprocessableEntity, refused.status, "version '$version'")
            }
            assertTrue("e2e-version-probe" !in instanceNames(), "a refused registration stores no instance")

            val ok = register("""{"name":"e2e-version-probe","version":"1.42.0"}""")
            assertEquals(HttpStatusCode.Created, ok.status)
            awaitInstance("e2e-version-probe")

            val id = E2e.json.decodeFromString<RunnerRegisterResponse>(ok.bodyAsText()).instanceId
            assertEquals(
                HttpStatusCode.NoContent,
                E2e.client.delete("/api/v1/runner/instances/$id") { header(HttpHeaders.Authorization, domain) }.status,
            )
            Awaits.awaitNotNull<String>("deregistered instance gone", timeoutMs = 15_000, pollMs = 500) {
                "e2e-version-probe".takeIf { it !in instanceNames() }
            }
        }

    private suspend fun probe(): String {
        val id = HomerApi.startRun("external-probe")
        val info = Awaits.awaitTerminal(id, timeoutMs = 60_000)
        return HomerApi.artifactText(id, info.executions[0].stdOut, "cmd_0.out").trim()
    }
}
