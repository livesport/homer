package homer.app.routes

import homer.app.apiJson
import homer.app.configureStatusPages
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.authorizerFor
import homer.app.fixtures.installGrantsApp
import homer.app.infra.matchesTokenConstantTime
import homer.app.models.Action
import homer.app.models.AgentReloadResult
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Grant
import homer.app.models.MaintenanceResponse
import homer.app.models.PodPhase
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.RunnerConfig
import homer.app.models.RunnerFilter
import homer.app.models.RunnerInstanceStatus
import homer.app.models.RunnerState
import homer.app.models.RunnerStatus
import homer.app.models.RunnersStatusResponse
import homer.app.models.Server
import homer.app.models.SshAuth
import homer.app.models.TokenConfig
import homer.app.runners.RunnerRegistry
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.external.ExternalRunnerInstance
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.kubernetes.SweepStatus
import homer.app.runners.queue.PendingClaim
import homer.app.runners.queue.RunnerClaimQueue
import homer.app.services.AgentFilesService
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for grants enforcement (ADR-0037) at the route layer
 * (Task 6). [installGrantsApp] mounts the production bearer provider, so the
 * same Authentication -> authenticate() -> apiV1Routes pipeline production
 * uses is exercised, with a real [Authorizer] built from the same
 * [TokenConfig] grants production resolves via [Authorizer.from].
 */
class GrantsEnforcementTest {
    private val echoAgent = AgentFixtures.simpleAgent(name = "echo")
    private val otherAgent = AgentFixtures.simpleAgent(name = "other").copy(group = "Other group")

    /** A valid YAML definition for `echo`, used as a PUT body by the route tests. */
    private val echoAgentYaml =
        """
        name: echo
        description: echo agent
        commands:
          - !<cmd>
            cmd: echo hello
            timeout: 60
        """.trimIndent()

    private fun deps(
        authorizer: Authorizer,
        agents: Map<String, homer.app.models.Agent> = mapOf("echo" to echoAgent, "other" to otherAgent),
        agentFilesService: AgentFilesService = mockk(relaxed = true),
    ): ApiDeps {
        val agentsService = mockk<AgentsService>(relaxed = true)
        every { agentsService.agents } returns agents
        // Relaxed AgentReloadResult defaults its List fields to null, not
        // emptyList() -- NPEs during kotlinx.serialization. Stub a real value.
        every { agentsService.reload() } returns
            AgentReloadResult(added = emptyList(), updated = emptyList(), removed = emptyList(), errors = emptyList())
        return mockk<ApiDeps>(relaxed = true) {
            every { this@mockk.authorizer } returns authorizer
            every { this@mockk.agentsService } returns agentsService
            every { this@mockk.agentFilesService } returns agentFilesService
        }
    }

    private val runToken = TokenConfig(token = "run-token", grants = listOf(Grant(listOf("echo"), listOf(Action.RUN))))
    private val readToken = TokenConfig(token = "read-token", grants = listOf(Grant(listOf("echo"), listOf(Action.READ))))
    private val debugToken = TokenConfig(token = "debug-token", grants = listOf(Grant(listOf("echo"), listOf(Action.DEBUG))))
    private val editToken = TokenConfig(token = "edit-token", grants = listOf(Grant(listOf("echo"), listOf(Action.EDIT))))
    private val adminToken = TokenConfig(token = "admin-token", role = "admin")

    /** JSON-encodes a YAML definition as the `content` of an `AgentFileWriteRequest` body. */
    private fun definitionBody(yaml: String): String =
        """{"content": ${homer.app.apiJson.encodeToString(
            kotlinx.serialization.json.JsonPrimitive
                .serializer(),
            kotlinx.serialization.json.JsonPrimitive(yaml),
        )}}"""

    @Test
    fun `run-granted token executes its agent`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res = client.post("/api/v1/agent/echo") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    // ADR-0047 rule 3: a caller holding no grant on an agent must not learn it exists.
    @Test
    fun `run on an agent the caller holds no grant on answers exactly like an unknown agent`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val hidden = client.post("/api/v1/agent/other") { header(HttpHeaders.Authorization, "Bearer run-token") }
            val unknown = client.post("/api/v1/agent/ghost") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.NotFound, hidden.status)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(unknown.bodyAsText().replace("ghost", "other"), hidden.bodyAsText())
        }

    @Test
    fun `deprecated GET run hides an agent the caller holds no grant on`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res = client.get("/api/v1/agent/other") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `run by a caller who can see the agent but lacks run is forbidden`() =
        testApplication {
            val tokens = mapOf("reader" to readToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res = client.post("/api/v1/agent/echo") { header(HttpHeaders.Authorization, "Bearer read-token") }
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }

    @Test
    fun `debug on an agent the caller holds no grant on answers exactly like an unknown agent`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val hidden = client.post("/api/v1/agents/other/debug") { header(HttpHeaders.Authorization, "Bearer run-token") }
            val unknown = client.post("/api/v1/agents/ghost/debug") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.NotFound, hidden.status)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals(unknown.bodyAsText().replace("ghost", "other"), hidden.bodyAsText())
        }

    @Test
    fun `debug requires debug action`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res =
                client.post("/api/v1/agents/echo/debug") {
                    header(HttpHeaders.Authorization, "Bearer run-token")
                }
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }

    @Test
    fun `debug-granted token passes the authorization check`() =
        testApplication {
            val tokens = mapOf("debugger" to debugToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res =
                client.post("/api/v1/agents/echo/debug") {
                    header(HttpHeaders.Authorization, "Bearer debug-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"commandIndex":0,"env":{},"queryParams":{},"headers":{}}""")
                }
            // Authorization passes; any further failure would be a 400/422/500 from
            // command execution, never 403/404.
            assertFalse(res.status == HttpStatusCode.Forbidden, "expected authorization to pass, got ${res.status}")
        }

    @Test
    fun `reload requires admin`() =
        testApplication {
            val tokens = mapOf("runner" to runToken, "root" to adminToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val scoped = client.post("/api/v1/agents/reload") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.Forbidden, scoped.status)

            val admin = client.post("/api/v1/agents/reload") { header(HttpHeaders.Authorization, "Bearer admin-token") }
            assertEquals(HttpStatusCode.OK, admin.status)
        }

    @Test
    fun `agent list is filtered to granted agents`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res = client.get("/api/v1/agents") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            assertTrue(body.contains("\"echo\""), body)
            assertFalse(body.contains("\"other\""), body)
            // An ungranted agent's group must not leak through the list either.
            assertFalse(body.contains("Other group"), body)
        }

    @Test
    fun `agent list is unfiltered for admin`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res = client.get("/api/v1/agents") { header(HttpHeaders.Authorization, "Bearer admin-token") }
            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            assertTrue(body.contains("\"echo\""), body)
            assertTrue(body.contains("\"other\""), body)
        }

    @Test
    fun `files list requires edit action`() =
        testApplication {
            val tokens = mapOf("runner" to runToken, "editor" to editToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val forbidden = client.get("/api/v1/agents/echo/files") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)

            val allowed = client.get("/api/v1/agents/echo/files") { header(HttpHeaders.Authorization, "Bearer edit-token") }
            assertFalse(allowed.status == HttpStatusCode.Forbidden, "expected authorization to pass, got ${allowed.status}")
        }

    @Test
    fun `file write and delete require edit action`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val putRes =
                client.put("/api/v1/agents/echo/files/notes.md") {
                    header(HttpHeaders.Authorization, "Bearer run-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"hi"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, putRes.status)

            val deleteRes =
                client.delete("/api/v1/agents/echo/files/notes.md") {
                    header(HttpHeaders.Authorization, "Bearer run-token")
                }
            assertEquals(HttpStatusCode.Forbidden, deleteRes.status)
        }

    @Test
    fun `file write succeeds for an edit-granted token`() =
        testApplication {
            val tokens = mapOf("editor" to editToken)
            val agentFilesService = mockk<AgentFilesService>(relaxed = true)
            every { agentFilesService.write(any(), any(), any(), any()) } returns AgentFilesService.FileOpResult.Ok(Unit)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), agentFilesService = agentFilesService)) }

            val res =
                client.put("/api/v1/agents/echo/files/notes.md") {
                    header(HttpHeaders.Authorization, "Bearer edit-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"hi"}""")
                }
            // Deterministic success (the handler's own AgentFileWriteResponse
            // path), not just "not 403" -- proves the EDIT grant actually reaches
            // agentFilesService.write and isn't stopped short by a mismapped action.
            assertEquals(HttpStatusCode.OK, res.status)
        }

    @Test
    fun `file delete succeeds for an edit-granted token`() =
        testApplication {
            val tokens = mapOf("editor" to editToken)
            val agentFilesService = mockk<AgentFilesService>(relaxed = true)
            every { agentFilesService.delete(any(), any()) } returns AgentFilesService.FileOpResult.Ok(Unit)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), agentFilesService = agentFilesService)) }

            val res =
                client.delete("/api/v1/agents/echo/files/notes.md") {
                    header(HttpHeaders.Authorization, "Bearer edit-token")
                }
            assertEquals(HttpStatusCode.NoContent, res.status)
        }

    /**
     * The declared-name create-grant on definition writes (CWE-285, ADR-0068),
     * proven at the route boundary with a REAL temp-dir-backed
     * [AgentFilesService] — everywhere above it is a mock, so the lambda the
     * handler passes into `write` never executed in any test. One denial row
     * (declared name ungranted → `404`, disk untouched) and the two siblings
     * (declared name granted → `200`; non-definition file → `200`).
     */
    @Test
    fun `PUT of a definition declaring an ungranted name is denied at the route with a real service (CWE-285)`() =
        testApplication {
            val root = Files.createTempDirectory("grants-cwe285")
            try {
                val echoDir = root.resolve("echo")
                Files.createDirectories(echoDir)
                Files.write(echoDir.resolve("agent.yaml"), echoAgentYaml.toByteArray())
                val logger = mockk<org.slf4j.Logger>(relaxed = true)
                val agentsService = AgentsService(root.toString(), logger)
                val agentFilesService = AgentFilesService(agentsService, logger)

                // edit on `echo` only: the declared name is NOT covered.
                val tokens =
                    mapOf(
                        "editor" to TokenConfig(token = "edit-token", grants = listOf(Grant(listOf("echo"), listOf(Action.EDIT)))),
                        "creator" to
                            TokenConfig(
                                token = "creator-token",
                                grants = listOf(Grant(listOf("echo", "test_agent_yaml"), listOf(Action.EDIT))),
                            ),
                    )
                val deps =
                    mockk<ApiDeps>(relaxed = true) {
                        every { authorizer } returns authorizerFor(tokens)
                        every { this@mockk.agentsService } returns agentsService
                        every { this@mockk.agentFilesService } returns agentFilesService
                    }
                application { installGrantsApp(tokens, deps) }

                val denied =
                    client.put("/api/v1/agents/echo/files/agent.yaml") {
                        header(HttpHeaders.Authorization, "Bearer edit-token")
                        contentType(ContentType.Application.Json)
                        setBody(definitionBody(AgentFixtures.validAgentYaml()))
                    }
                assertEquals(HttpStatusCode.NotFound, denied.status)
                assertEquals(
                    echoAgentYaml,
                    Files.readString(echoDir.resolve("agent.yaml")),
                    "a denied declared-name write must not touch the definition on disk",
                )

                val granted =
                    client.put("/api/v1/agents/echo/files/agent.yaml") {
                        header(HttpHeaders.Authorization, "Bearer creator-token")
                        contentType(ContentType.Application.Json)
                        setBody(definitionBody(AgentFixtures.validAgentYaml()))
                    }
                assertEquals(HttpStatusCode.OK, granted.status)

                val plainFile =
                    client.put("/api/v1/agents/echo/files/notes.md") {
                        header(HttpHeaders.Authorization, "Bearer edit-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"hi"}""")
                    }
                assertEquals(HttpStatusCode.OK, plainFile.status)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `create agent requires edit action on the requested name`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res =
                client.post("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer run-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"echo"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }

    @Test
    fun `create agent succeeds for an edit-granted token on the target name`() =
        testApplication {
            val tokens = mapOf("editor" to editToken)
            val agentFilesService = mockk<AgentFilesService>(relaxed = true)
            every { agentFilesService.create(any()) } returns AgentFilesService.FileOpResult.Ok("echo")
            application { installGrantsApp(tokens, deps(authorizerFor(tokens), agentFilesService = agentFilesService)) }

            val res =
                client.post("/api/v1/agents") {
                    header(HttpHeaders.Authorization, "Bearer edit-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"echo"}""")
                }
            assertEquals(HttpStatusCode.Created, res.status)
        }

    @Test
    fun `costs and server status require admin`() =
        testApplication {
            val tokens = mapOf("runner" to runToken, "root" to adminToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            for (path in listOf("/api/v1/costs", "/api/v1/costs/monthly", "/api/v1/costs/totals", "/api/v1/status")) {
                val scoped = client.get(path) { header(HttpHeaders.Authorization, "Bearer run-token") }
                assertEquals(HttpStatusCode.Forbidden, scoped.status, "expected 403 for scoped token on $path")

                val admin = client.get(path) { header(HttpHeaders.Authorization, "Bearer admin-token") }
                assertEquals(HttpStatusCode.OK, admin.status, "expected 200 for admin token on $path")
            }
        }

    @Test
    fun `langgraph hides existence of an agent the caller has no grant on`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res = client.get("/api/v1/agents/other/langgraph") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.NotFound, res.status)
        }

    @Test
    fun `langgraph is reachable when the caller has any grant on the agent`() =
        testApplication {
            val tokens = mapOf("runner" to runToken)
            application { installGrantsApp(tokens, deps(authorizerFor(tokens))) }

            val res = client.get("/api/v1/agents/echo/langgraph") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.OK, res.status)
        }

    // --- runner state reads: admin, or a read grant on `*` (ADR-0111) ---

    /** READ on every agent — the grant that unlocks the server-level runner state. */
    private val starReadToken = TokenConfig(token = "star-read-token", grants = listOf(Grant(listOf("*"), listOf(Action.READ))))

    /**
     * The runner-state fixture: a configured `ssh` runner, an `external` domain with one live
     * registered instance, and one pending alias claim. Returns the deps and the instance, so a
     * golden comparison can name the instance the way an admin sees it.
     */
    private fun runnerStateDeps(tokens: Map<String, TokenConfig>): Pair<ApiDeps, ExternalRunnerInstance> {
        val queue = mockk<RunnerClaimQueue>(relaxed = true)
        coEvery { queue.pendingSnapshot() } returns listOf(PendingClaim(RunnerFilter.Alias("gpu-box"), enqueuedAtMs = 1_000))
        every { queue.nowMs() } returns 11_000
        val domain = ExternalRunnerDomain("workers", RunnerConfig.External(token = "t".repeat(32)), queue)
        val instance = domain.register(RegisterInstanceRequest(name = "host-1", tags = listOf("gpu"), concurrency = 2))
        val config =
            AppConfig(
                agentsSearchPath = "unused",
                workspaceRootPath = "unused",
                databasePath = "unused",
                runners =
                    mapOf(
                        "gpu-box" to
                            RunnerConfig.Ssh(
                                host = "h",
                                user = "u",
                                auth = SshAuth.Agent,
                                remoteWorkspaceRoot = "/remote",
                                tags = listOf("gpu"),
                                concurrency = 2,
                            ),
                        "workers" to RunnerConfig.External(token = "t".repeat(32)),
                    ),
            )
        val d =
            deps(authorizerFor(tokens)).also {
                every { it.config } returns config
                every { it.runnerRegistry } returns
                    RunnerRegistry(entries = emptyList(), externalDomains = listOf(domain), queue = queue)
                every { it.maintenanceModeService } returns
                    mockk(relaxed = true) {
                        every { snapshot() } returns MaintenanceResponse(enabled = false, since = null, queued = 0)
                    }
            }
        return d to instance
    }

    // ADR-0047 rule 8: every denial path of the widened gate is asserted.
    @Test
    fun `runner state reads deny a caller without a read grant on star`() =
        testApplication {
            val tokens = mapOf("runner" to runToken, "reader" to readToken, "root" to adminToken)
            application { installGrantsApp(tokens, runnerStateDeps(tokens).first) }

            for (path in listOf("/api/v1/runners", "/api/v1/runners/queue")) {
                val noGrant = client.get(path) { header(HttpHeaders.Authorization, "Bearer run-token") }
                assertEquals(HttpStatusCode.Forbidden, noGrant.status, "expected 403 for a no-grant token on $path")
                assertFalse(noGrant.bodyAsText().contains("gpu-box"), "a denial must not leak the runner list")

                // An agent-level read grant does not unlock server-level state.
                val narrow = client.get(path) { header(HttpHeaders.Authorization, "Bearer read-token") }
                assertEquals(HttpStatusCode.Forbidden, narrow.status, "expected 403 for an agent-scoped read on $path")

                val anonymous = client.get(path)
                assertEquals(HttpStatusCode.Unauthorized, anonymous.status, "expected 401 without a credential on $path")
            }
        }

    @Test
    fun `a read grant on star reads the runner state with operator naming redacted`() =
        testApplication {
            val tokens = mapOf("status" to starReadToken, "root" to adminToken)
            application { installGrantsApp(tokens, runnerStateDeps(tokens).first) }

            val res = client.get("/api/v1/runners") { header(HttpHeaders.Authorization, "Bearer star-read-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals("no-store", res.headers[HttpHeaders.CacheControl], "live state must not be cached")
            val body = res.bodyAsText()
            // Counts, capacity and liveness are what the grant is for; operator naming is not.
            assertTrue(body.contains("\"alias\": \"local\""), "the built-in local alias is Homer's own name: $body")
            assertTrue(body.contains("\"alias\": \"\""), "a configured alias is blanked, keeping the row's shape: $body")
            assertFalse(body.contains("gpu-box"), "a configured alias must be withheld: $body")
            assertFalse(body.contains("host-1"), "an instance name must be withheld: $body")
            assertTrue(body.contains("\"instanceId\": \"\""), "an instance id is blanked: $body")
            assertTrue(body.contains("\"name\": \"\""), "an instance name is blanked: $body")
            assertTrue(body.contains("\"kind\": \"external\""), "the generic backend kind stays: $body")
            assertTrue(body.contains("\"concurrency\": 2"), "instance capacity stays: $body")
            assertTrue(body.contains("\"draining\": false"), "instance liveness stays: $body")
        }

    @Test
    fun `a read grant on star reads the queue with the alias filter emptied`() =
        testApplication {
            val tokens = mapOf("status" to starReadToken)
            application { installGrantsApp(tokens, runnerStateDeps(tokens).first) }

            val res = client.get("/api/v1/runners/queue") { header(HttpHeaders.Authorization, "Bearer star-read-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals("no-store", res.headers[HttpHeaders.CacheControl], "live state must not be cached")
            val body = res.bodyAsText()
            assertTrue(body.contains("\"pending\": 1"), "the queue depth stays: $body")
            assertTrue(body.contains("\"oldestAgeSec\": 10"), "the queue age stays: $body")
            assertTrue(body.contains("\"alias\": null"), "an alias filter carries no value: $body")
            assertFalse(body.contains("gpu-box"), "an alias filter must be emptied, not echoed: $body")
        }

    @Test
    fun `the admin runner state is byte-identical to the operator view`() =
        testApplication {
            val tokens = mapOf("root" to adminToken)
            val (d, instance) = runnerStateDeps(tokens)
            application { installGrantsApp(tokens, d) }

            val res = client.get("/api/v1/runners") { header(HttpHeaders.Authorization, "Bearer admin-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            val expected =
                apiJson.encodeToString(
                    RunnersStatusResponse(
                        listOf(
                            RunnerStatus(alias = "local", kind = "local", tags = emptyList(), concurrency = null, inUse = 0),
                            RunnerStatus(alias = "gpu-box", kind = "ssh", tags = listOf("gpu"), concurrency = 2, inUse = 0),
                            RunnerStatus(
                                alias = "workers",
                                kind = "external",
                                tags = emptyList(),
                                concurrency = null,
                                inUse = 0,
                                instances =
                                    listOf(
                                        RunnerInstanceStatus(
                                            instanceId = instance.id,
                                            name = instance.name,
                                            tags = listOf("gpu"),
                                            concurrency = 2,
                                            activeSessions = 0,
                                            lastSeenAt = instance.lastSeenAt / 1000,
                                            registeredAt = instance.registeredAt / 1000,
                                            draining = false,
                                        ),
                                    ),
                            ),
                        ),
                    ),
                )
            assertEquals(expected, res.bodyAsText(), "the admin view must not change")
        }

    @Test
    fun `a read grant on star reads the maintenance state but cannot toggle it or reload agents`() =
        testApplication {
            val tokens = mapOf("status" to starReadToken)
            application { installGrantsApp(tokens, runnerStateDeps(tokens).first) }

            val state = client.get("/api/v1/maintenance") { header(HttpHeaders.Authorization, "Bearer star-read-token") }
            assertEquals(HttpStatusCode.OK, state.status)
            assertEquals("no-store", state.headers[HttpHeaders.CacheControl])

            val toggle =
                client.post("/api/v1/maintenance") {
                    header(HttpHeaders.Authorization, "Bearer star-read-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":true}""")
                }
            assertEquals(HttpStatusCode.NotFound, toggle.status, "the toggle stays admin-only")

            val reload = client.post("/api/v1/agents/reload") { header(HttpHeaders.Authorization, "Bearer star-read-token") }
            assertEquals(HttpStatusCode.Forbidden, reload.status, "reload stays admin-only")

            val kill = client.delete("/api/v1/kill/42") { header(HttpHeaders.Authorization, "Bearer star-read-token") }
            assertEquals(HttpStatusCode.NotFound, kill.status, "a read grant must not reach a control route")
        }

    @Test
    fun `a caller without the grant gets the bare 404 from the maintenance read`() =
        testApplication {
            val tokens = mapOf("runner" to runToken, "reader" to readToken)
            application { installGrantsApp(tokens, runnerStateDeps(tokens).first) }

            val noGrant = client.get("/api/v1/maintenance") { header(HttpHeaders.Authorization, "Bearer run-token") }
            val narrowRead = client.get("/api/v1/maintenance") { header(HttpHeaders.Authorization, "Bearer read-token") }

            assertEquals(HttpStatusCode.NotFound, noGrant.status)
            assertEquals(HttpStatusCode.NotFound, narrowRead.status)
            assertEquals("", noGrant.bodyAsText(), "the denial is the bare 404 an unmounted path answers")
        }

    @Test
    fun `status-all keeps the runner alias hidden from a read-star caller`() =
        testApplication {
            // ADR-0077 is not widened: the new grant reads runner state, never the
            // operator naming on a run's status row.
            val tokens = mapOf("status" to starReadToken)
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery {
                pm.getAllProcesses(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns
                ProcessManager.ProcessListResult(
                    processes =
                        listOf(
                            ProcessInfo(id = 42, status = ProcessStatus.FINISHED, agentName = "echo", owner = "alice")
                                .copy(runner = RunnerState(alias = "gpu-box", podPhase = PodPhase.READY)),
                        ),
                    total = 1,
                )
            val questions = mockk<QuestionsService>(relaxed = true)
            coEvery { questions.openCountByProcess() } returns emptyMap()
            val d =
                runnerStateDeps(tokens).first.also {
                    every { it.processManager } returns pm
                    every { it.questionsService } returns questions
                }
            application { installGrantsApp(tokens, d) }

            val res = client.get("/api/v1/status/all") { header(HttpHeaders.Authorization, "Bearer star-read-token") }

            assertEquals(HttpStatusCode.OK, res.status)
            val body = res.bodyAsText()
            assertFalse(body.contains("gpu-box"), "the run-status alias stays admin-only: $body")
            assertTrue(body.contains("\"type\": \"ssh\""), "the generic backend type stays: $body")
        }

    @Test
    fun `health withholds runner aliases from a non-admin caller`() =
        testApplication {
            // CWE-200: /health is open to any authenticated caller,
            // so the configured runner aliases and the sweep snapshot's runnerAlias
            // — operator-chosen backend metadata — must not reach a non-admin. The
            // endpoint answers 200 to everyone; only the alias fields are withheld.
            val tokens = mapOf("runner" to runToken, "root" to adminToken)
            val sweepStatus = SweepStatus()
            sweepStatus.set(
                SweepStatus.Snapshot(
                    ranAt = 1_700_000_000L,
                    scanned = 1,
                    deleted =
                        listOf(
                            SweepStatus.DeletedPod(
                                namespace = "homer",
                                name = "homer-99-xyz",
                                processId = 99,
                                runnerAlias = "sweep-k8s",
                            ),
                        ),
                    errors = emptyList(),
                ),
            )
            val d =
                deps(authorizerFor(tokens)).also {
                    every { it.sweepStatus } returns sweepStatus
                    every { it.config } returns
                        AppConfig(
                            agentsSearchPath = "unused",
                            workspaceRootPath = "unused",
                            databasePath = "unused",
                            runners =
                                mapOf(
                                    "k8s-prod-eu" to
                                        homer.app.models.RunnerConfig
                                            .Kubernetes(image = "homer:latest"),
                                ),
                        )
                }
            application { installGrantsApp(tokens, d) }

            val scoped = client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer run-token") }
            assertEquals(HttpStatusCode.OK, scoped.status)
            val scopedBody = scoped.bodyAsText()
            // `encodeDefaults = true` keeps the JSON keys; the assertion is on the
            // values — a non-admin gets null, an admin gets the operator-chosen string.
            assertFalse(scopedBody.contains("k8s-prod-eu"), "a non-admin must not see the configured alias")
            assertFalse(scopedBody.contains("sweep-k8s"), "a non-admin must not see the sweep alias")
            assertTrue(scopedBody.contains("\"type\": \"kubernetes\""), "the generic backend type stays")

            val admin = client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer admin-token") }
            assertEquals(HttpStatusCode.OK, admin.status)
            val adminBody = admin.bodyAsText()
            assertTrue(adminBody.contains("k8s-prod-eu"), "an admin still sees the configured alias")
            assertTrue(adminBody.contains("\"runnerAlias\": \"sweep-k8s\""), "an admin still sees the sweep alias")
        }

    @Test
    fun `the health response carries deletedJobs with the alias hidden from non-admins`() =
        testApplication {
            val tokens = mapOf("runner" to runToken, "root" to adminToken)
            val sweepStatus = SweepStatus()
            sweepStatus.set(
                SweepStatus.Snapshot(
                    ranAt = 1_700_000_000L,
                    scanned = 0,
                    deleted = emptyList(),
                    errors = emptyList(),
                    deletedJobs = listOf(SweepStatus.DeletedJob("homer", "homer-99-job", 99, "job-k8s")),
                ),
            )
            val d =
                deps(authorizerFor(tokens)).also {
                    every { it.sweepStatus } returns sweepStatus
                    every { it.config } returns
                        AppConfig(agentsSearchPath = "unused", workspaceRootPath = "unused", databasePath = "unused")
                }
            application { installGrantsApp(tokens, d) }

            val scopedBody = client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer run-token") }.bodyAsText()
            assertTrue(scopedBody.contains("homer-99-job"), "a non-admin still sees the deleted Job")
            assertFalse(scopedBody.contains("job-k8s"), "a non-admin must not see the Job's runner alias")

            val adminBody = client.get("/api/v1/health") { header(HttpHeaders.Authorization, "Bearer admin-token") }.bodyAsText()
            assertTrue(adminBody.contains("\"runnerAlias\": \"job-k8s\""), "an admin still sees the Job's runner alias")
        }
}
