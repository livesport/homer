package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhoamiTest : E2eTestBase() {
    @Test
    fun `a run's callback token identifies the run through either header, a forged one does not`() =
        runBlocking {
            val processId = HomerApi.startRun("whoami-probe")
            assertEquals("FINISHED", Awaits.awaitTerminal(processId).status)

            val out = HomerApi.artifact(processId, "cmd_0.out").bodyAsText()
            val identity = """{"processId":$processId,"agent":"whoami-probe"}"""
            assertTrue(out.contains("dedicated=$identity"), out)
            assertTrue(out.contains("bearer=$identity"), out)
            assertTrue(out.contains("agent-header=whoami-probe"), out)
            assertTrue(out.contains("bogus=401"), out)
        }

    @Test
    fun `an operator credential is not a callback token`() =
        runBlocking {
            assertEquals(HttpStatusCode.Unauthorized, E2e.client.get("/api/v1/callback/whoami").status)
        }
}
