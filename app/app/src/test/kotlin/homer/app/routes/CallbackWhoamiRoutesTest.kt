package homer.app.routes

import homer.app.EnvConstants
import homer.app.apiJson
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.mountCallbackRoutes
import homer.app.models.WhoamiResponse
import homer.app.services.QuestionsService
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallbackWhoamiRoutesTest {
    private val path = "/api/v1/callback/whoami"

    private fun registered(
        processId: Int = 7,
        agent: String = "agent-a",
    ): Pair<QuestionsService, String> {
        val service = PersistenceTestSupport.questionsService()
        return service to runBlocking { service.registerProcess(processId, agent) }
    }

    private suspend fun HttpClient.whoami(
        dedicated: String? = null,
        authorization: String? = null,
    ): HttpResponse =
        get(path) {
            dedicated?.let { header(EnvConstants.CALLBACK_TOKEN_HEADER, it) }
            authorization?.let { header(HttpHeaders.Authorization, it) }
        }

    private suspend fun assertIdentified(
        response: HttpResponse,
        processId: Int,
        agent: String,
    ) {
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(WhoamiResponse(processId = processId, agent = agent), apiJson.decodeFromString(response.bodyAsText()))
        assertEquals(agent, response.headers[EnvConstants.AGENT_HEADER])
        assertEquals(processId.toString(), response.headers[EnvConstants.PROCESS_ID_HEADER])
    }

    private suspend fun assertRefused(
        response: HttpResponse,
        reference: String,
    ) {
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(reference, response.bodyAsText(), "every refusal must look the same")
        assertNull(response.headers[EnvConstants.AGENT_HEADER])
        assertNull(response.headers[EnvConstants.PROCESS_ID_HEADER])
    }

    @Test
    fun `the dedicated header identifies the run holding the token`() =
        testApplication {
            val (service, token) = registered(processId = 7, agent = "agent-a")
            mountCallbackRoutes { every { questionsService } returns service }

            assertIdentified(client.whoami(dedicated = token), processId = 7, agent = "agent-a")
        }

    @Test
    fun `a bearer authorization identifies the run too`() =
        testApplication {
            val (service, token) = registered(processId = 3, agent = "reviewer")
            mountCallbackRoutes { every { questionsService } returns service }

            assertIdentified(client.whoami(authorization = "Bearer $token"), processId = 3, agent = "reviewer")
        }

    @Test
    fun `the dedicated header wins over an authorization that belongs to the upstream`() =
        testApplication {
            val (service, token) = registered()
            mountCallbackRoutes { every { questionsService } returns service }
            val reference = client.whoami().bodyAsText()

            assertIdentified(client.whoami(dedicated = token, authorization = "Bearer upstream-credential"), 7, "agent-a")
            // A non-blank dedicated header is the only token read: no fallback to a valid bearer.
            assertRefused(client.whoami(dedicated = "not-a-token", authorization = "Bearer $token"), reference)
        }

    @Test
    fun `a blank dedicated header falls back to the bearer`() =
        testApplication {
            val (service, token) = registered()
            mountCallbackRoutes { every { questionsService } returns service }

            assertIdentified(client.whoami(dedicated = "", authorization = "Bearer $token"), 7, "agent-a")
        }

    @Test
    fun `every method answers alike, since an ext_authz check reuses the proxied request's method`() =
        testApplication {
            val (service, token) = registered()
            mountCallbackRoutes { every { questionsService } returns service }
            val reference = client.whoami().bodyAsText()

            for (verb in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)) {
                val identified =
                    client.request(path) {
                        method = verb
                        header(EnvConstants.CALLBACK_TOKEN_HEADER, token)
                    }
                assertIdentified(identified, 7, "agent-a")
                assertRefused(client.request(path) { method = verb }, reference)
            }
        }

    @Test
    fun `a missing, unknown, finished or evicted token gets one identical 401`() =
        testApplication {
            val service = PersistenceTestSupport.questionsService()
            val finished = runBlocking { service.registerProcess(1, "finished") }
            val evicted = runBlocking { service.registerProcess(2, "evicted") }
            runBlocking {
                service.expireForProcess(1)
                service.removeForProcess(2)
            }
            mountCallbackRoutes { every { questionsService } returns service }
            val reference = client.whoami().bodyAsText()

            assertRefused(client.whoami(), reference)
            assertRefused(client.whoami(authorization = "Basic dXNlcjpwYXNz"), reference)
            assertRefused(client.whoami(dedicated = "not-a-token"), reference)
            assertRefused(client.whoami(dedicated = finished), reference)
            assertRefused(client.whoami(authorization = "Bearer $finished"), reference)
            assertRefused(client.whoami(dedicated = evicted), reference)
        }

    @Test
    fun `no string but the exact token identifies the run`() =
        testApplication {
            val (service, token) = registered()
            mountCallbackRoutes { every { questionsService } returns service }
            val reference = client.whoami().bodyAsText()

            checkAll(Arb.string(1..80, Codepoint.alphanumeric()), Arb.int(1..63)) { noise, cut ->
                if (noise != token) assertRefused(client.whoami(dedicated = noise), reference)
                assertRefused(client.whoami(dedicated = token + noise), reference)
                assertRefused(client.whoami(dedicated = noise + token), reference)
                assertRefused(client.whoami(dedicated = token.dropLast(cut)), reference)
            }
        }
}
