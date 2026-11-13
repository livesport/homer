package homer.app.models

import kotlinx.serialization.Serializable

/** GET /api/v1/runners — see app/README.md. */
@Serializable
data class RunnersStatusResponse(
    val runners: List<RunnerStatus>,
)

/**
 * One configured runner (`local`, `ssh`, `kubernetes` or `external`) in the operator view.
 * Operator-chosen naming is admin-only: a non-admin reader gets `alias: null`, except on the
 * built-in `local` row, whose fixed alias Homer assigns. See ADR-0077, ADR-0111.
 */
@Serializable
data class RunnerStatus(
    val alias: String? = null,
    val kind: String, // "local" | "ssh" | "kubernetes" | "external"
    val tags: List<String>, // sorted; empty for external (instances carry tags)
    val concurrency: Int? = null, // null = unbounded; always null for external
    val inUse: Int, // static: permits held; external: sum of instance activeSessions
    val instances: List<RunnerInstanceStatus>? = null, // external only
)

/**
 * One live instance of an `external` domain, as registered via the runner protocol. The
 * instance's identity is operator-visible metadata: `instanceId` and `name` are admin-only and
 * null for a non-admin reader, while its capacity and liveness stay. See ADR-0077, ADR-0111.
 */
@Serializable
data class RunnerInstanceStatus(
    val instanceId: String? = null,
    val name: String? = null,
    val tags: List<String>, // sorted
    val concurrency: Int,
    val activeSessions: Int,
    val lastSeenAt: Long, // epoch seconds
    val registeredAt: Long, // epoch seconds
    val draining: Boolean,
)

/** GET /api/v1/runners/queue — see app/README.md. */
@Serializable
data class RunnerQueueStatusResponse(
    val pending: Int,
    val oldestAgeSec: Long? = null,
    val byFilter: List<RunnerQueueGroup>,
)

/** Pending claims sharing one [RunnerFilterView], oldest-first at the response level. */
@Serializable
data class RunnerQueueGroup(
    val filter: RunnerFilterView,
    val pending: Int,
    val oldestAgeSec: Long,
)

/** Exactly one of the two is set — except in a redacted view, where an alias filter carries neither. See ADR-0111. */
@Serializable
data class RunnerFilterView(
    val alias: String? = null,
    val tags: List<String>? = null,
)
