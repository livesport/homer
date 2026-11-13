package homer.app.runners

import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.models.RunnerFilter
import homer.app.models.RunnerFilterView
import homer.app.models.RunnerInstanceStatus
import homer.app.models.RunnerQueueGroup
import homer.app.models.RunnerQueueStatusResponse
import homer.app.models.RunnerStatus
import homer.app.models.RunnerTagValidation
import homer.app.models.RunnersStatusResponse
import homer.app.runners.external.ExternalRunnerDomain
import homer.app.runners.local.LocalRunner
import homer.app.runners.queue.PendingClaim
import homer.app.runners.queue.StaticRunnerConsumer

/**
 * Generic backend kind for [alias] — `local` for the built-in runner, the
 * configured backend type (`ssh` / `kubernetes` / `external`) otherwise, and
 * `unknown` for an alias no longer in config (e.g. a run served by a runner that
 * was later removed). One mapping serves `GET /api/v1/runners`, `/health` and the
 * non-admin status response. See ADR-0077.
 */
fun runnerType(
    config: AppConfig,
    alias: String,
): String =
    when (config.runners[alias]) {
        is RunnerConfig.Ssh -> "ssh"
        is RunnerConfig.Kubernetes -> "kubernetes"
        is RunnerConfig.External -> "external"
        null -> if (alias == LocalRunner.ALIAS) "local" else "unknown"
    }

/**
 * Operator view of every configured runner; never carries a token or env value. [namesVisible]
 * false is the redacted view a `read` grant on `*` gets: operator-chosen aliases and instance
 * names are blanked, capacity and counts stay. See ADR-0066, ADR-0077, ADR-0111.
 */
fun runnersStatus(
    config: AppConfig,
    registry: RunnerRegistry,
    namesVisible: Boolean,
): RunnersStatusResponse {
    val inUse =
        registry.queue
            .consumers()
            .filterIsInstance<StaticRunnerConsumer>()
            .associate { it.alias to it.inUse }
    val domains = registry.externalDomains.associateBy { it.alias }
    val local = config.server.localRunner
    val rows =
        buildList {
            if (local.enabled) {
                add(
                    RunnerStatus(
                        // Homer assigns the built-in alias, so it survives the redacted view (ADR-0111).
                        LocalRunner.ALIAS,
                        runnerType(config, LocalRunner.ALIAS),
                        visibleTags(local.tags, namesVisible),
                        local.concurrency,
                        inUse[LocalRunner.ALIAS] ?: 0,
                    ),
                )
            }
            config.runners.forEach { (alias, cfg) ->
                add(
                    when (cfg) {
                        is RunnerConfig.Ssh -> {
                            RunnerStatus(
                                visibleName(alias, namesVisible),
                                runnerType(config, alias),
                                visibleTags(cfg.tags, namesVisible),
                                cfg.concurrency,
                                inUse[alias] ?: 0,
                            )
                        }

                        is RunnerConfig.Kubernetes -> {
                            RunnerStatus(
                                visibleName(alias, namesVisible),
                                runnerType(config, alias),
                                visibleTags(cfg.tags, namesVisible),
                                cfg.concurrency,
                                inUse[alias] ?: 0,
                            )
                        }

                        is RunnerConfig.External -> {
                            externalRunnerStatus(alias, runnerType(config, alias), domains[alias], namesVisible)
                        }
                    },
                )
            }
        }
    return RunnersStatusResponse(rows)
}

/** The value a redacted view carries in place of an operator-chosen name: blank, never a name. */
private const val WITHHELD_NAME = ""

/**
 * The name a response carries: blank in a redacted view, where an operator-chosen alias, instance
 * id or instance name is withheld. See ADR-0077, ADR-0111.
 */
private fun visibleName(
    name: String,
    namesVisible: Boolean,
): String = if (namesVisible) name else WITHHELD_NAME

/**
 * The tags a response carries. An admin sees the configured ones; a redacted view re-checks
 * every tag at the serializer and drops a value that fails [RunnerTagValidation], rather than
 * echoing agent-written text (ADR-0047 rule 2).
 */
private fun visibleTags(
    tags: Collection<String>,
    namesVisible: Boolean,
): List<String> =
    if (namesVisible) {
        tags.sorted()
    } else {
        tags
            .toSet()
            .filter { RunnerTagValidation.PATTERN.matches(it) }
            .take(RunnerTagValidation.MAX_TAGS)
            .sorted()
    }

/** An `!<external>` domain's row: no tags or concurrency of its own, one entry per registered instance, oldest first. */
private fun externalRunnerStatus(
    alias: String,
    kind: String,
    domain: ExternalRunnerDomain?,
    namesVisible: Boolean,
): RunnerStatus {
    val instances = domain?.instances().orEmpty().sortedBy { it.registeredAt }
    return RunnerStatus(
        alias = visibleName(alias, namesVisible),
        kind = kind,
        tags = emptyList(),
        concurrency = null,
        inUse = instances.sumOf { it.inUse },
        instances =
            instances.map {
                RunnerInstanceStatus(
                    instanceId = visibleName(it.id, namesVisible),
                    name = visibleName(it.name, namesVisible),
                    tags = visibleTags(it.tags, namesVisible),
                    concurrency = it.declaredConcurrency,
                    activeSessions = it.inUse,
                    lastSeenAt = it.lastSeenAt / 1000,
                    registeredAt = it.registeredAt / 1000,
                    draining = it.draining,
                )
            },
    )
}

/**
 * Pending claims grouped by filter, oldest group first; ages clamp at 0. [namesVisible] false
 * empties an alias filter like [runnersStatus] withholds a row's alias — `null` here, because
 * [RunnerFilterView.alias] is nullable in the DTO the console already mirrors.
 */
fun runnerQueueStatus(
    pending: List<PendingClaim>,
    nowMs: Long,
    namesVisible: Boolean,
): RunnerQueueStatusResponse {
    fun ageSec(enqueuedAtMs: Long) = ((nowMs - enqueuedAtMs) / 1000).coerceAtLeast(0)
    val groups =
        pending
            .groupBy { it.filter }
            .map { (filter, claims) ->
                RunnerQueueGroup(
                    filter =
                        when (filter) {
                            is RunnerFilter.Alias -> RunnerFilterView(alias = filter.alias.takeIf { namesVisible })
                            is RunnerFilter.Tags -> RunnerFilterView(tags = visibleTags(filter.tags, namesVisible))
                        },
                    pending = claims.size,
                    oldestAgeSec = ageSec(claims.minOf { it.enqueuedAtMs }),
                )
            }.sortedByDescending { it.oldestAgeSec }
    return RunnerQueueStatusResponse(
        pending = pending.size,
        oldestAgeSec = pending.minOfOrNull { it.enqueuedAtMs }?.let(::ageSec),
        byFilter = groups,
    )
}
