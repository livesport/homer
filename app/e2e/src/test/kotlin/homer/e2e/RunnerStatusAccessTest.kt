package homer.e2e

import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.MaintenanceState
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

    @Test
    fun `a read grant on star polls the runner state with naming redacted`() =
        runBlocking {
            val response = E2e.unauthClient.get("/api/v1/runners") { header(HttpHeaders.Authorization, starRead) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "polled state must never be cached")
            val view = E2e.json.decodeFromString<RunnersView>(response.bodyAsText())
            // The built-in `local` alias is Homer's own name; every configured alias is withheld.
            assertEquals("local", view.runners.first().alias)
            assertTrue(view.runners.drop(1).all { it.alias == null }, "a configured alias must be withheld: $view")
            assertTrue(view.runners.any { it.kind == "external" }, "the external domain itself stays visible: $view")
            assertTrue(
                view.runners.flatMap { it.instances.orEmpty() }.all { it.name == null && it.instanceId == null },
                "an instance's identity is operator-chosen too: $view",
            )
        }

    @Test
    fun `a read grant on star polls the queue with an alias filter emptied`() =
        runBlocking {
            val response = E2e.unauthClient.get("/api/v1/runners/queue") { header(HttpHeaders.Authorization, starRead) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "polled state must never be cached")
            val view = E2e.json.decodeFromString<RunnerQueueView>(response.bodyAsText())
            assertTrue(view.pending >= 0)
            assertTrue(
                view.byFilter.all { it.filter.alias == null },
                "a redacted queue view must not name the runner an alias filter targets: $view",
            )
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
