---
status: proposed
date: 2026-11-13
deciders: Lukáš Vlk
consulted: ADR-0019, ADR-0039, ADR-0045
---
# ADR-0110: Homer tells an egress proxy which run holds a callback token; the egress policy stays with the proxy

## Context and Problem Statement

Deployments that keep upstream credentials out of the runs put a credential-injecting
proxy between the runs and the upstream APIs (ADR-0045 keeps Homer out of secret
delivery; a sidecar proxy is the usual way to do that). The proxy can restrict *what*
is reachable, but not *who* reaches it: every run on a runner shares the network, so a
read-only reviewer and the agent that publishes look the same, and a header the run
sets itself proves nothing. The proxy needs an identity the run cannot forge. Homer
already mints one per run — the callback token of ADR-0019, registered with the agent
name when the run starts — but nothing outside Homer can ask whose token it is.

## Decision Drivers

- The identity must be one the run cannot forge or borrow from a run that has ended.
- Homer must not become a policy engine for systems it does not own, and agent
  definitions carry no authority (ADR-0039): what an agent may reach upstream cannot be
  declared in a file `edit` can rewrite.
- No new secret or key material for Homer to hold or rotate (ADR-0045).
- Additive: a deployment that does not use it sees no change.

## Considered Options

- **Resolve the callback token on request** — a callback route answers which run holds
  a token; the proxy keeps the policy.
- **Homer evaluates the egress policy** — the proxy asks "may this run call X?".
- **Signed run tokens (JWT) the proxy validates locally.**
- **Identity headers set by the runner.**

## Decision Outcome

Chosen option: **"Resolve the callback token on request"**, because the token already
exists, is unguessable, is screened from the run's output, and stops identifying the run
when the run ends — the proxy gets a revocable identity with no new key material, and
Homer answers only *who*, never *what*.

- `/api/v1/callback/whoami` sits with the other callback routes, outside operator
  authentication: operator credentials never satisfy it, and the token satisfies nothing
  but callback routes.
- The token is read from `X-Homer-Callback-Token`, else from `Authorization: Bearer`. At
  a proxy `Authorization` belongs to the upstream, so a present dedicated header is the
  only one read.
- `200 {processId, agent}`, the same values in `X-Homer-Process-Id` / `X-Homer-Agent`
  (so envoy's `ext_authz` forwards them without parsing JSON). One identical `401` for a
  missing, unknown or ended run's token — a caller learns nothing from which it was.
- Any method answers the same: envoy's `ext_authz` HTTP check reuses the proxied
  request's method.

### Consequences

- Good: per-agent egress policy becomes possible for any proxy that can make an HTTP
  call, with the policy in the proxy's own configuration.
- Good: a run's identity dies with the run; there is no expiry to tune.
- Bad: the proxy depends on Homer being up. A proxy that fails closed refuses egress
  while the server restarts, and the in-memory registry means a restart invalidates
  every working run's token.
- Bad: runs sharing one OS user can read each other's environment, so a run can present
  another working run's token. Accepted at this boundary (root `CLAUDE.md` §12: same-UID
  access is not a principal boundary); separating runs by user, pod or runner pool is the
  deployment's job, and `app/SERVER.md` says so.
- Neutral: the route is on the proxy's hot path. Token resolution is a linear scan with
  a constant-time comparison per registration, fine for tens of working runs; indexing by
  a hash of the token is the fix if it shows in profiles.

## Pros and Cons of the Options

### Homer evaluates the egress policy

- Good: one place to look.
- Bad: Homer learns about upstream APIs, routes and methods it does not own.
- Bad: the natural place to declare the policy — next to the agent definition — is one
  ADR-0039 forbids from carrying authority; a server-config map instead ties every policy
  change to a server deploy.

### Signed run tokens (JWT)

- Good: the proxy validates locally; Homer is off the request path.
- Bad: signing keys to generate, share across instances, publish and rotate.
- Bad: a token stays valid until it expires — a finished run cannot be revoked.

### Identity headers set by the runner

- Bad: not an identity — the run's own shell can send any header.

## More Information

- Issue: livesport/homer#207.
- Proxy setup, the envoy example and the shared-user limit: `app/SERVER.md` § Run
  identity for egress proxies.
