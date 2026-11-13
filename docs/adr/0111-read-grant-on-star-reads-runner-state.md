---
status: proposed
date: 2026-11-13
deciders: Lukáš Vlk
consulted: ADR-0037, ADR-0047, ADR-0077, ADR-0092
---

# ADR-0111: A `read` grant on `*` reads the server-wide runner state, with operator naming withheld

## Context and Problem Statement

Runner state — which backends are configured, how many permits each holds, and
what is waiting in the claim queue — is server-wide, not agent-scoped, so the
grants model (ADR-0037) had nowhere to put it: `GET /api/v1/runners`,
`/api/v1/runners/queue` and `GET /api/v1/maintenance` were admin-only. An
operator who should be able to watch capacity and queue depth without holding
`role: admin` — the console's runner view, a monitoring poller, a read-only
service account — therefore had to be made an admin, which grants reload, kill,
retry, agent edits and the maintenance toggle on top. The alternative, leaving
the endpoints closed, forces the same over-grant. The alias and instance names
these endpoints carry are the operator-chosen naming ADR-0077 already treats as
disclosing infrastructure topology (CWE-200), so a widening cannot simply ship
the admin view.

## Decision Drivers

- §12: principals are a trust boundary, and availability is in scope — a polled
  read that only admins can make pushes deployments into granting admin.
- ADR-0077: operator-chosen naming (alias, instance name, sweep `runnerAlias`)
  is admin-only; the generic `kind` is not.
- ADR-0037: a grant is per-agent; `*` is the existing, explicit "every agent"
  pattern, and a grant that is not `*` says nothing about server-wide state.
- ADR-0047 rule 3 (handler-level gate, fail closed) and rule 8 (prove each
  denial path with a test).
- No new config surface if an existing grant can carry the meaning.

## Considered Options

- **Keep the endpoints admin-only** — every read-only watcher becomes an admin.
- **Admit any `read` grant** — a grant on one agent unlocks server-wide state.
- **A new grant action (`readServer`, `server:read`)** — a dedicated action for
  server-level reads.
- **Admit a `read` grant on the literal `*` pattern, with naming withheld** —
  the caller who may read *every* agent may read the state of the runners that
  serve them, minus the operator-chosen names.

## Decision Outcome

Chosen option: **"admit a `read` grant on the literal `*` pattern, with operator
naming withheld"**, because the operator already has a way to say "this
principal may read everything" — `grants: [{agents: ["*"], actions: ["read"]}]` —
and reading which runners exist, how loaded they are and what is queued is
strictly less than reading every run. Nothing new to configure, and no
deployment that keeps its tokens scoped sees a change.

- `Authorizer.canReadServerState(principal)` is the single gate: an admin,
  unsecured mode, or `readableAgents(principal)?.contains("*") == true`. It is
  read-only — it grants no reload, kill, retry or maintenance toggle — and an
  unknown or absent principal fails closed.
- `GET /api/v1/runners` and `/runners/queue` call it at the top of the handler
  through `deniedUnlessServerReader`, which answers `403`. These are documented
  endpoints that report no per-resource existence, so there is nothing to hide
  behind a `404`; the denial names no alias and no instance.
- `GET /api/v1/maintenance` admits the same grant; `POST` stays admin-only. Its
  denial keeps the bare `404` ADR-0092 established, unchanged, and the body is
  still parsed only after the gate.
- `namesVisible` is resolved once per request in the handler
  (`authorizer.isAdmin(principalName())`), the `/health` pattern of ADR-0077, and
  passed into `RunnerStatusReport` as a **required** parameter: a new call site
  cannot inherit visibility by omission. With `namesVisible = false` the view
  nulls `alias` on every configured row, `instanceId` and `name` on every
  instance, and the alias of a queue filter group. The built-in `local` row
  keeps its alias, which Homer assigns rather than the operator (ADR-0077).
- Redaction keeps what a watcher is there for: `kind`, `tags`, `concurrency`,
  `inUse`, `activeSessions`, `registeredAt`, `lastSeenAt` and `draining`. A tag
  is re-checked against `RunnerTagValidation` at the serializer on the redacted
  path only, so a value that reached an instance by another route cannot ride
  out through this view (ADR-0047 rule 2). The admin path is byte-identical to
  before.
- Both responses carry `Cache-Control: no-store`: they are polled live state,
  and a cached copy would let a proxy or browser serve a stale answer after a
  runner was added, drained or removed.
- `/status`, `/status/all` and `/costs*` stay admin-only, and per-run
  `runner.alias` stays admin-only (ADR-0077): this ADR widens the server-wide
  runner read, not the run read.

### Consequences

- Good: a monitoring or read-only principal can watch runner capacity and queue
  depth without holding `role: admin`.
- Good: the disclosure ADR-0077 closed per run stays closed here — the widened
  view carries no operator-chosen name, and an alias filter is emptied rather
  than echoed.
- Good: no new config key, schema field or console change; a deployment with
  scoped tokens sees no behavioral change at all.
- Bad: a `*`-read caller learns the number, kind and load of configured runners,
  and the tags on them — accepted, since the same caller may already read every
  agent and every run on them.
- Bad: `*` is a prefix-exact pattern, so a deployment that grants
  `agents: ["prod-*"]` must add an explicit `*` grant to enable this; the
  narrowness is deliberate.
- Bad: the redacted view is a second shape of the same DTO, so a future field
  must be classified as operator-named or generic when it is added.

## Pros and Cons of the Options

### Keep the endpoints admin-only

- Good: no new disclosure at all.
- Bad: every read-only watcher needs `role: admin`, which also carries kill,
  reload, agent edit and the maintenance toggle — a far larger grant than the
  read it wanted. §12 treats that as the worse trade.

### Admit any `read` grant

- Good: simplest rule, no pattern matching.
- Bad: a grant on one agent would unlock server-wide state, including the tags
  and load of runners that agent can never use. A grant says which agents a
  principal may read; it says nothing about the fleet.

### A new grant action (`readServer`, `server:read`)

- Good: expresses the intent most precisely and is orthogonal to agent scoping.
- Bad: a new action is a config schema change, a console change and a migration
  note for every deployment that wants it, for a read the literal `*` grant
  already expresses. It can be added later without breaking this decision; the
  gate is one method, so the swap is local.

### Admit a `read` grant on `*`, naming withheld

- Good: reuses the grant operators already write for "everything", and keeps
  ADR-0077's classification: generic `kind` out, operator naming withheld.
- Bad: `*` is coarse — it cannot express "runner state but not every run" —
  accepted because reading every run is strictly more than reading the runners.

## More Information

Amends [ADR-0077](0077-runner-alias-is-admin-only-on-status.md), which stated
that `GET /api/v1/runners` stays admin-only; its classification (operator-named
alias vs. generic `kind`) is what this decision applies. The maintenance read
extends [ADR-0092](0092-maintenance-mode-queues-starts-and-skips-cron.md) without
changing its denial. Implemented in `Authorizer.canReadServerState`,
`SecuredRoutes.deniedUnlessServerReader`, `ServerRoutes`, `MaintenanceRoutes` and
`RunnerStatusReport`; documented in [`app/README.md`](../../app/README.md).
