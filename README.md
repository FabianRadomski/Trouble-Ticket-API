# Trouble-Ticket-API

Implementation of the minimal TMF621 Trouble Ticket profile described in
[`devtask/trouble-ticket-api.yaml`](devtask/trouble-ticket-api.yaml)
(assignment: [`devtask/assignment.pdf`](devtask/assignment.pdf)).

Stack: Java 21, Spring Boot, PostgreSQL, Docker Compose.

## Running

> Full instructions (example calls for every endpoint) land with the implementation. So far:

```bash
scripts/dev-keys.sh                     # once: RS256 keypair into .dev/ (gitignored); needs openssl
docker compose up --build               # api :8080, postgres :5432, swagger-ui :8081
TOKEN=$(scripts/dev-token.sh tenant-a "tt:read tt:write")   # 1h dev token
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/troubleTicket
./gradlew build                         # tests need Docker (Testcontainers)
```

`scripts/dev-keys.sh` must run before `docker compose up`: Compose mounts `.dev/jwt-public.pem`
into the `api` container.

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

`HEAD` on ticket paths needs `tt:read` like `GET`, since Spring MVC answers it from the `GET` handler.

**Everything else:**

| Path / request                          | Rule                                                        |
|-----------------------------------------|-------------------------------------------------------------|
| `/actuator/health/**`                   | public (container probes)                                   |
| other `/actuator/**`                    | denied for every token, so an endpoint exposed later is not open by accident |
| `/.well-known/oauth-protected-resource` | public; RFC 9728 metadata that Spring Security 7 serves by default, set to list the two scopes |
| any other path or method                | any valid token, so MVC answers `404` / `405` instead of a misleading `403` |
| internal error dispatches               | not checked again, so errors raised in filters keep their status |

- Missing, invalid or expired token, or a token **without `tenant_id`** (or a blank or non-string one) → `401 UNAUTHORIZED`.
  The `tenant_id` check is a token validator, so such a token never authenticates and application
  code never sees a request without a tenant.
- Valid token without the required scope → `403 FORBIDDEN`.
- Authentication and scope checks run before the request body is read: a missing token with an invalid
  body is `401`, not `400`.
- The tenant reaches application code only through `CurrentTenant` (read from the verified token).
- *Not configured:* CORS. Browser calls from another origin (e.g. Swagger UI "Try it out" on :8081)
  are blocked; curl and server-to-server calls are not affected.
- Spring Security does not produce the spec's error body by default, so a custom
  `AuthenticationEntryPoint` and `AccessDeniedHandler` render `{code, message, requestId}` for 401/403.

### 2. Services (`serviceId` check)

The spec has no services endpoint, but create must check that the service exists, is active
and belongs to the caller's tenant. Services are treated as an **external system** (in TMF terms,
a service inventory) behind a port:

- `ServiceCatalog.findActiveForTenant(tenantId, serviceId)` is an application-layer interface.
- v1 ships an **in-memory stub adapter**. Its seed data is configuration
  (`troubleticket.service-catalog.services` in `application.yml`), so local runs can add services
  without a rebuild. Seeded: `987654321`, `987654322` (active) and `987654329` (inactive) for
  `tenant-a`; `987654331` for `tenant-b`. Service ids are global, as in an inventory.
  *Rejected:* a hard-coded map (a rebuild for every new test service).
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
4. The timeout is `troubleticket.soz.register-timeout` (default `2s`). The call runs on a virtual thread
   so the request stops waiting at the timeout whatever the adapter does. A `PENDING` answer also leaves
   the ticket `new`.
5. The acknowledgement re-reads the ticket and applies `acknowledge()`, which changes nothing if the
   ticket has moved on meanwhile (e.g. the client already closed it). The response then shows that
   status. An optimistic-lock conflict is retried once; if it repeats, the `201` carries the ticket as
   created. Any other failure of this step is a `500`, and the client's retry gets the saved ticket
   through idempotency (§4).

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
- v1 mitigation: an idempotent re-create (`200`, same `tenantId + externalId`) retries the SOZ
  registration if the stored status is still `new` (§4). A client retrying after a lost response
  thereby also retries the registration.
- *Rejected:* calling SOZ inside the DB transaction. Slow DB connections are held during an
  external call, a SOZ outage blocks all creates, and a timeout after SOZ accepted leaves SOZ with
  a ticket we rolled back.
- Closing a ticket should also notify SOZ eventually. In v1 this is a no-op method on the same port
  (`notifyClosed`), called best-effort after the close commits (§5).

### 4. Idempotency of create

- The key is `(tenant_id, external_id)`, enforced by a **unique constraint** in PostgreSQL.
- If a ticket with that key exists, the API returns **`200 OK` with the existing ticket**, as the spec says,
  **even if the new payload differs** (`serviceId`, `description`, `note`). The differing payload
  is ignored and a warning is logged. The warning names the differing fields only, not their values
  (customer data).
  - *Rejected:* `409` on mismatch (not in the contract) and `400` on mismatch (the request is valid, so
    400 is semantically wrong).
  - Risk: a client that reuses an `externalId` by mistake silently gets the old ticket back.
- **Concurrent duplicates:** two simultaneous creates both pass the "exists?" check, and one insert fails
  on the unique constraint. The violation is caught, the winning row is re-read and returned as `200`.
  No application-level locking is needed.
  - The insert runs in its own transaction, so the violation is caught after that transaction has rolled
    back, and the re-read uses a fresh one. Under PostgreSQL's read-committed isolation the losing insert
    waits on the unique index until the winner commits, so the re-read always finds the winning row.
  - Any integrity violation triggers the re-read; if no ticket with the key exists, the original error is
    rethrown (`500`). This avoids parsing driver-specific constraint names.
  - The loser does **not** call SOZ: the winning request is registering the ticket at that moment.
  - Hibernate logs each such violation at `WARN`. It is expected only under concurrent duplicates.
- A replay of a ticket still `new` retries its SOZ registration (§3); the response is `200` with the
  resulting status.
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
- The close commits in its own transaction. A concurrent status change (e.g. the SOZ acknowledgement
  of §3) fails it on the ticket's version. It is re-read and retried once, which succeeds because
  closing is allowed from every status. If it conflicts again, the error propagates (`500`), and the
  client's retry is safe.
- After the commit, and only if this request actually closed the ticket, SOZ is told
  (`notifyClosed`, a no-op in v1). A repeated close does not notify SOZ again. A failed notification is
  logged and the response is still `200 closed`. Guaranteed delivery would need the outbox of §3.
- A ticket of another tenant gives the same `404 TROUBLE_TICKET_NOT_FOUND` as a missing one (§1).

### 6. Notes

- Notes can be added in **any** status, including `closed`. Notes are an append-only comment log,
  and comments after closing are useful (e.g. confirming a fix held).
- The `note` field on create becomes the first element of `notes`.
- Notes are returned in creation order (oldest first).
- Adding a note never changes the ticket status. It also leaves the ticket's `updatedAt` and version
  untouched, so a note never causes an optimistic-lock conflict with a concurrent status change (or
  another note).
- `POST /troubleTicket/{id}/note` returns `201` with the note and **no `Location` header**: the spec
  declares none and notes have no endpoint of their own.
- A ticket of another tenant gives the same `404 TROUBLE_TICKET_NOT_FOUND` as a missing one (§1).

### 7. Identifiers

Identifiers follow the format of the spec examples and come from PostgreSQL sequences:

- Ticket: `TT-{year}-{6-digit sequence}`, e.g. `TT-2026-000001`. The year is the creation year.
  The sequence is global and is **not** reset each year, which keeps it simple and unique without per-year counters.
- Note: `NOTE-{6-digit sequence}`, e.g. `NOTE-000001`.
- The padding is a minimum width. Past 999999 the number just grows.
- Numbers can have gaps: a create that loses an idempotency race (§4) or fails has already taken its
  sequence values. IDs are unique and increasing, not contiguous.
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
- **`externalId` is limited to 255 characters** (longer → `400 VALIDATION_ERROR`). The spec sets no
  `maxLength`, so this is also **stricter than the contract**. The value is part of the unique
  idempotency index, and an unbounded key would fail inside PostgreSQL's index (a `500`) instead of
  as a clear client error. 255 leaves ample room for UUIDs and typical ticket numbers of client systems.
  *Rejected:* an unbounded `text` column (matches the contract literally, but very long keys break the index).
- Whitespace and length rules live in the domain (`TroubleTicket`), which throws a validation exception
  rendered as the same `400 VALIDATION_ERROR` body as schema violations.
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
- One query that reads only the four summary columns (a DTO projection, backed by the
  `(tenant_id, created_at, id)` index). Notes are never loaded for the list, so there are no N+1 queries.
- The spec's `TroubleTicketSummary` has **no `id`**, so a client cannot go from a list entry to
  `GET /troubleTicket/{id}` directly. It can only match entries by its own `externalId`. The contract
  is followed as written. Adding `id` to the summary would be a backward-compatible change for v2.
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

### 12. Persistence

- **Schema: Flyway** migrations (`src/main/resources/db/migration`). Hibernate only validates the
  entities against the schema at startup (`ddl-auto: validate`) and never changes it.
  Versioned SQL is reviewable and gives full control over constraints, indexes and sequences.
  *Rejected:* Hibernate `ddl-auto: update` (no history, unsafe in production), `schema.sql` at startup
  (no versioning), Liquibase (equivalent, but more ceremony than plain SQL needs here).
- **JPA entities with behaviour.** `TroubleTicket` and `Note` are JPA entities that also own the status
  rules (`acknowledge()`, `close()`, `addNote()`), unit-tested without Spring. Generated DTOs stay in
  the web layer. *Rejected:* separate domain objects plus persistence entities and a mapper: cleaner
  separation, but twice the classes and mapping code for a model this small.
- **Constraints in the database:** `unique (tenant_id, external_id)` backs idempotency (§4), a check
  constraint limits `status` to known values, and notes reference their ticket by foreign key.
- **Optimistic locking** (`version` column) on tickets, so concurrent status changes (e.g. a SOZ
  acknowledge racing a client close) cannot silently overwrite each other.
- **Tenant-scoped repository.** The ticket repository extends Spring Data's bare `Repository`, not
  `JpaRepository`, and every lookup takes the tenant. There is no unscoped `findById` that could load
  another tenant's ticket by mistake.
- Times are stored as `timestamptz` (UTC `Instant` in code), truncated to microseconds, PostgreSQL's
  precision. A single `Clock` bean supplies "now", so tests control time.
- A ticket's `updatedAt` tracks the ticket's own fields (status); notes carry their own time.

### 13. Other notes

- The assignment refers to `devtask/trouble-ticket-api.yaml`. The specification lives in `devtask/`
  accordingly.
- Out of scope (v1): inbound status updates from SOZ, ticket deletion, note editing, paging,
  and an outbox worker (see §3).
