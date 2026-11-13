package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.MaintenanceState
import homer.e2e.support.RunnerInstanceView
import homer.e2e.support.RunnerQueueView
import homer.e2e.support.RunnersView
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The runner-state reads against the production server (ADR-0111): a `read` grant on `*` may poll
 * `/api/v1/runners` and `/api/v1/runners/queue`, with the operator-chosen names redacted; a read
 * grant on one agent stays denied; and an admin still sees every alias.
 */
class RunnerStatusAccessTest : E2eTestBase() {
    private val starRead = "Bearer status-reader-token"
    private val agentScoped = "Bearer echo-scoped-token"

    /** The instances the admin view lists, so a test can wait for one to be registered. */
    private suspend fun adminInstances(): List<RunnerInstanceView> =
        HomerApi.decode<RunnersView>(E2e.client.get("/api/v1/runners")).runners.flatMap { it.instances.orEmpty() }

    @Test
    fun `a read grant on star polls the runner state with naming redacted`() =
        runBlocking {
            // The instance assertions below only bite once a live instance exists to redact.
            Awaits.awaitNotNull("an external instance registered", timeoutMs = 60_000, pollMs = 500) {
                adminInstances().takeIf { it.isNotEmpty() }
            }

            val response = E2e.unauthClient.get("/api/v1/runners") { header(HttpHeaders.Authorization, starRead) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "polled state must never be cached")
            val view = E2e.json.decodeFromString<RunnersView>(response.bodyAsText())
            // The built-in `local` alias is Homer's own name; every configured alias is blanked.
            assertEquals("local", view.runners.first().alias)
            assertTrue(view.runners.drop(1).all { it.alias.isEmpty() }, "a configured alias must be withheld: $view")
            assertTrue(view.runners.any { it.kind == "external" }, "the external domain itself stays visible: $view")
            val instances = view.runners.flatMap { it.instances.orEmpty() }
            assertTrue(instances.isNotEmpty(), "a live instance must be listed, or nothing below is proven: $view")
            assertTrue(
                instances.all { it.name.isEmpty() && it.instanceId.isEmpty() },
                "an instance's identity is operator-chosen too: $view",
            )
        }

    @Test
    fun `a read grant on star polls the queue with an alias filter emptied`() =
        runBlocking {
            // Seed a pending alias-filtered claim: no instance registers for the idle domain, so
            // the claim waits there for its short TTL (and the run then fails — pinned below).
            val seeded = HomerApi.startRun("runner-alias-unserved")
            try {
                val view =
                    Awaits.awaitNotNull("the seeded alias claim to turn up pending", timeoutMs = 30_000, pollMs = 250) {
                        val response =
                            E2e.unauthClient.get("/api/v1/runners/queue") { header(HttpHeaders.Authorization, starRead) }
                        assertEquals(HttpStatusCode.OK, response.status)
                        assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "polled state must never be cached")
                        val decoded = E2e.json.decodeFromString<RunnerQueueView>(response.bodyAsText())
                        decoded.takeIf { queued ->
                            queued.pending > 0 &&
                                queued.byFilter.any { group ->
                                    group.pending > 0 && group.filter.alias == null && group.filter.tags == null
                                }
                        }
                    }

                assertEquals(1, view.pending, "only the seeded claim may be pending: $view")
                // An alias filter is the one group shape whose redaction loses information: an
                // admin sees the alias here, so a pending group carrying neither field is what a
                // redaction regression fails.
                assertTrue(
                    view.byFilter.any { it.pending > 0 && it.filter.alias == null && it.filter.tags == null },
                    "a pending alias filter must arrive with both filter fields withheld: $view",
                )
            } finally {
                // The seeded claim waits out its TTL and fails: pin that, so the suite leaves no
                // run behind mid-claim.
                assertEquals("FAILED", Awaits.awaitTerminal(seeded, timeoutMs = 30_000).status)
            }
        }

    @Test
    fun `an agent-scoped read is denied on both runner-state reads`() =
        runBlocking {
            for (path in listOf("/api/v1/runners", "/api/v1/runners/queue")) {
                val denied = E2e.unauthClient.get(path) { header(HttpHeaders.Authorization, agentScoped) }
                assertEquals(HttpStatusCode.Forbidden, denied.status, path)
            }
        }

    @Test
    fun `an admin still sees every configured alias`() =
        runBlocking {
            val view = HomerApi.decode<RunnersView>(E2e.client.get("/api/v1/runners"))

            assertTrue(
                view.runners.map { it.alias }.containsAll(listOf("local", "e2e-ext", "e2e-affinity")),
                "the admin view is unchanged: $view",
            )
        }

    @Test
    fun `a read grant on star reads the maintenance state but cannot toggle it`() =
        runBlocking {
            val read = E2e.unauthClient.get("/api/v1/maintenance") { header(HttpHeaders.Authorization, starRead) }
            assertEquals(HttpStatusCode.OK, read.status)

            val toggle =
                E2e.unauthClient.post("/api/v1/maintenance") {
                    header(HttpHeaders.Authorization, starRead)
                    setBody(TextContent("""{"enabled":true}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.NotFound, toggle.status, "the grant is read-only: POST stays admin-only")
            // The refused toggle changed nothing.
            assertFalse(HomerApi.decode<MaintenanceState>(E2e.client.get("/api/v1/maintenance")).enabled)
        }
}
