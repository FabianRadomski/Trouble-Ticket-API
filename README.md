# Trouble-Ticket-API

Implementation of the minimal TMF621 Trouble Ticket profile described in
[`devtask/trouble-ticket-api.yaml`](devtask/trouble-ticket-api.yaml)
(assignment: [`devtask/assignment.pdf`](devtask/assignment.pdf)).

Stack: Java 21, Spring Boot, PostgreSQL, Docker Compose.

## Running

> TODO: filled in once the implementation lands (docker compose, key generation, minting a dev token, example curl calls).

## Assumptions and technical decisions

The OpenAPI spec is the source of truth for the public contract. Where it is ambiguous or silent,
the assumptions below apply. Each one lists the alternatives considered and the trade-off accepted.

### 1. Authentication and tenant scope

There is no identity provider in scope, so the service acts as an OAuth2 **resource server** that
checks JWTs locally.

- **Algorithm: RS256.** The API holds only the **public key**
  (`spring.security.oauth2.resourceserver.jwt.public-key-location`). Moving to a real IdP
  (Keycloak, Entra ID, …) is a config change to `jwk-set-uri`, with no code change.
- **No private key in the repository.** A dev script generates the keypair locally into a
  gitignored directory and mints tokens for manual testing. Tests generate their own keypair in memory.
- *Rejected:* HS256 shared secret, because the API could then also sign tokens and moving to an IdP would mean changing the verification setup.
  Keycloak in Compose: realistic, but too heavy for the assignment's scope.

**Claims:**

| Claim       | Meaning                                                    |
|-------------|------------------------------------------------------------|
| `sub`       | user / API account id                                      |
| `tenant_id` | tenant scope, the **only** source of tenant identity       |
| `scope`     | space-separated permissions: `tt:read`, `tt:write`         |

**Permission model:**

| Operation                              | Required scope |
|----------------------------------------|----------------|
| `GET /troubleTicket`, `GET /troubleTicket/{id}` | `tt:read`  |
| `POST /troubleTicket`, `PATCH /troubleTicket/{id}`, `POST /troubleTicket/{id}/note` | `tt:write` |

- Missing, invalid or expired token, or a token **without `tenant_id`** → `401 UNAUTHORIZED`.
- Valid token without the required scope → `403 FORBIDDEN`.
- Spring Security does not produce the spec's error body by default, so a custom
  `AuthenticationEntryPoint` and `AccessDeniedHandler` render `{code, message, requestId}` for 401/403.

### 2. Services (`serviceId` check)

The spec has no services endpoint, but create must check that the service exists, is active
and belongs to the caller's tenant. Services are treated as an **external system** (in TMF terms,
a service inventory) behind a port:

- `ServiceCatalog.findActiveForTenant(tenantId, serviceId)` is an application-layer interface.
- v1 ships an **in-memory stub adapter** with fixed seed data (a few services per tenant,
  including an inactive one) for local runs and tests.
- Unknown, inactive **or belonging to another tenant** all return the same
  `404 SERVICE_NOT_FOUND`, so the API does not reveal whether a service exists in another tenant.
- *Rejected:* a local `service` table with seed data. Simpler to query, but it suggests this service owns
  service data, which it does not.

### 3. SOZ integration and the `acknowledged` status

**Context**
- The spec says a newly created ticket "may already" be returned with status `acknowledged`,
  "according to the processing flow on the SOZ side". `new` is therefore also a valid 201 response.
- The spec examples show tickets later moving to `inProgress` / `resolved`, so SOZ drives status over time.
  There is no inbound endpoint for SOZ in v1, so inbound status updates are out of scope.
- Creating a ticket means writing to our DB **and** notifying an external system: a dual-write problem.

**Decision**
1. SOZ is modelled as a **port**: an application-layer interface
   (`SozGateway.register(ticket) -> ACKNOWLEDGED | PENDING`). v1 ships an in-process fake adapter.
   The domain owns the transition rule (`ticket.acknowledge()`, allowed only from `new`).
2. Flow on create:
   1. Save the ticket with status `new` in its own DB transaction and commit.
   2. After the commit, still within the request, call SOZ synchronously with a short timeout.
   3. If SOZ acknowledges: update status to `acknowledged`, return `201` with `acknowledged`.
   4. If SOZ fails or times out: log it, return `201` with `new`. The ticket is saved and the client
      is not blocked by SOZ availability.
3. v1 implements only this **immediate best-effort attempt**. It does **not** guarantee that SOZ eventually
   receives every ticket.

**Production target (documented, not built): transactional outbox as a safety net**
- Save the ticket **and** an outbox row `REGISTER_TICKET` in the same transaction (atomic, no lost events).
- After the commit, try SOZ immediately (as above). On success, mark the outbox row done → `201 acknowledged`.
- On failure, a background worker polls the outbox and retries with backoff until SOZ acknowledges,
  then updates the status.
- A pure outbox (no immediate attempt) would almost always return `new` in the 201, since the response
  is sent before the worker runs. That is spec-compliant but loses the common-case `acknowledged`.

**Consequences / trade-offs**
- Delivery is at-least-once: a call can succeed on SOZ's side but time out on ours, and the worker
  resends it. SOZ registration must be idempotent, keyed by our ticket `id`.
- **v1 limitation:** if SOZ is down, the ticket stays `new` and nothing retries it. Accepted for the
  assignment's scope and listed as a known gap.
- Optional v1 mitigation: on an idempotent re-create (`200`, same `tenantId + externalId`), retry
  SOZ registration if the stored status is still `new`.
- *Rejected:* calling SOZ inside the DB transaction. Slow DB connections are held during an
  external call, a SOZ outage blocks all creates, and a timeout after SOZ accepted leaves SOZ with
  a ticket we rolled back.
- Closing a ticket should also notify SOZ eventually. In v1 this is a no-op method on the same port
  (`notifyClosed`).

### 4. Idempotency of create

- The key is `(tenant_id, external_id)`, enforced by a **unique constraint** in PostgreSQL.
- If a ticket with that key exists, the API returns **`200 OK` with the existing ticket**, as the spec says,
  **even if the new payload differs** (`serviceId`, `description`, `note`). The differing payload
  is ignored and a warning is logged.
  - *Rejected:* `409` on mismatch (not in the contract) and `400` on mismatch (the request is valid, so
    400 is semantically wrong).
  - Risk: a client that reuses an `externalId` by mistake silently gets the old ticket back.
- **Concurrent duplicates:** two simultaneous creates both pass the "exists?" check, and one insert fails
  on the unique constraint. The violation is caught, the winning row is re-read and returned as `200`.
  No application-level locking is needed.
- The `Location` header is returned on both `201` and `200`, as the spec declares. Its value is the
  **relative** path `/api/v1/troubleTicket/{id}` (the spec types it as `uri-reference`, so a relative
  reference is valid). This avoids rebuilding the external host/scheme behind proxies.
- The idempotency lookup runs **first**. The `serviceId` check applies only when a new ticket is
  created, so a replay of an existing ticket returns `200` even if the service has since been deactivated.

### 5. Status lifecycle and closing

Statuses (from the spec): `new`, `acknowledged`, `inProgress`, `resolved`, `rejected`, `closed`.

- `resolved` and `rejected` are **outcomes** set by the operator (SOZ). `closed` is the single
  **final** state, which can follow either of them. Closing is how the client confirms the outcome.
- The public client may close a ticket from **any** status:
  - from `resolved` / `rejected`: confirms the operator's outcome,
  - from `new` / `acknowledged` / `inProgress`: withdraws a ticket the client no longer needs.
- **Closing an already `closed` ticket is idempotent:** `200` with the unchanged ticket (retry-safe).
- A PATCH body with any status other than `closed` is rejected by schema validation →
  `400 VALIDATION_ERROR`. So no state-based 400 exists on this endpoint.
- *Rejected:* allowing close only from `resolved` / `rejected`, because the client could not withdraw a ticket opened by mistake.

### 6. Notes

- Notes can be added in **any** status, including `closed`. Notes are an append-only comment log,
  and comments after closing are useful (e.g. confirming a fix held).
- The `note` field on create becomes the first element of `notes`.
- Notes are returned in creation order (oldest first).
- Adding a note never changes the ticket status.

### 7. Identifiers

Identifiers follow the format of the spec examples and come from PostgreSQL sequences:

- Ticket: `TT-{year}-{6-digit sequence}`, e.g. `TT-2026-000001`. The year is the creation year.
  The sequence is global and is **not** reset each year, which keeps it simple and unique without per-year counters.
- Note: `NOTE-{6-digit sequence}`, e.g. `NOTE-000001`.
- The padding is a minimum width. Past 999999 the number just grows.
- Trade-off: sequential IDs can be enumerated. That is harmless here because every lookup is tenant-scoped
  and returns `404` for tickets of other tenants. *Rejected:* UUIDs (opaque, but inconsistent with the
  contract's examples).

### 8. Request validation and errors

- All error responses use the spec's `Error` shape: `{code, message, requestId}`.
- `additionalProperties: false` is enforced: unknown JSON fields → `400 VALIDATION_ERROR`
  (Jackson `FAIL_ON_UNKNOWN_PROPERTIES`). Bean Validation enforces required fields, `minLength`
  and `minimum`.
- **Whitespace-only strings are rejected** (`"   "` → `400 VALIDATION_ERROR`) for `externalId`,
  `description`, `note` and note `text`. This is **stricter than the contract**: `minLength: 1` alone
  accepts them, but a blank description, note or external key carries no meaning and would only
  pollute data. Values are otherwise stored as sent (not trimmed).
- Error codes:

| HTTP | `code`                     | When                                                        |
|------|----------------------------|-------------------------------------------------------------|
| 400  | `VALIDATION_ERROR`         | malformed JSON, unknown/missing fields, disallowed status   |
| 401  | `UNAUTHORIZED`             | missing/invalid token, missing `tenant_id` claim            |
| 403  | `FORBIDDEN`                | token lacks the required scope                              |
| 404  | `SERVICE_NOT_FOUND`        | create: service unknown, inactive or in another tenant      |
| 404  | `TROUBLE_TICKET_NOT_FOUND` | ticket unknown or in another tenant                         |
| 500  | `INTERNAL_ERROR`           | unexpected error (not in the spec; no internals leaked)     |

Generic HTTP errors the spec does not list still use the same `Error` body, so clients only ever
have to parse one error shape:

| HTTP | `code`                     | When                                                        |
|------|----------------------------|-------------------------------------------------------------|
| 404  | `NOT_FOUND`                | unknown path                                                |
| 405  | `METHOD_NOT_ALLOWED`       | HTTP method not supported on the path                       |
| 406  | `NOT_ACCEPTABLE`           | `Accept` header excludes `application/json`                 |
| 415  | `UNSUPPORTED_MEDIA_TYPE`   | request body is not `application/json`                      |

### 9. Request ID

- A servlet filter **always generates** a new UUID `requestId` per request. Incoming headers are not
  trusted or reused.
- It is put in the logging MDC, included in every error body and returned in an `X-Request-Id`
  response header.
- Trade-off: no end-to-end correlation with upstream callers. Honoring an incoming `X-Request-Id`
  would be a small, non-breaking extension.

### 10. Listing tickets

- Returns all tickets in the caller's tenant, ordered **oldest first**
  (`ORDER BY created_at ASC, id ASC`). This order is deterministic and stays stable if paging is added later.
- **Risk: no paging or filtering** (v1 contract). The response grows without limit as a tenant's ticket count
  grows. Proposed for v2: TMF-style `offset` / `limit` with `X-Total-Count`, plus filtering by
  `status`. A server-side cap was rejected because it would silently truncate results
  without any signal in the contract.

### 11. Contract-first code generation

- The Spring controller interfaces (`TroubleTicketApi`, `TroubleTicketNoteApi`) and the request/response
  DTOs are **generated from `devtask/trouble-ticket-api.yaml`** by `openapi-generator` on every build
  (into `build/`, not committed). Controllers implement the generated interfaces, so if the code
  drifts from the spec (path, method, parameter, field), **the build fails**.
- The generator does not apply the spec's `/api/v{version}` server URL. The `/api/v1` prefix is added
  in the web configuration for the generated controllers only (actuator stays at `/actuator`).
- Generated DTOs are a **web-layer concern only**. They are mapped to and from domain objects in the
  web layer and never reach the application or domain code.
- The generator cannot check everything (e.g. that a response actually fills every required field),
  so integration tests additionally validate real responses against the spec where feasible.
- *Rejected:* hand-written DTOs plus a contract test. That means more code to keep in sync by hand, and drift
  is detected only by tests rather than by the compiler.

### 12. Other notes

- The assignment refers to `devtask/trouble-ticket-api.yaml`. The specification lives in `devtask/`
  accordingly.
- Out of scope (v1): inbound status updates from SOZ, ticket deletion, note editing, paging,
  and an outbox worker (see §3).
