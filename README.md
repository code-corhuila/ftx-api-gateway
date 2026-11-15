# ftx-api-gateway

> Single public entry point of Futbolix: routing, correlation id, header hygiene and rate limiting

Part of **Futbolix**, the distributed system for reserving the four football courts of a single
sports facility. Governance and documentation live in
[`ftx-docs`](https://github.com/code-corhuila/ftx-docs). Decision: **ADR-004** (Spring Cloud
Gateway, port 8080). Contract: `ftx-docs/07-api/contracts/openapi/api-gateway.yaml`.

## Routes

| Path | Service |
|---|---|
| `/api/v1/auth/**` | `ftx-customers-api` :8081 |
| `/api/v1/courts/*/schedules/**`, `/api/v1/courts/*/bookable-slots`, `/api/v1/admin/courts/*/schedules/**` | `ftx-schedules-api` :8085 |
| `/api/v1/courts/**`, `/api/v1/admin/courts/**` | `ftx-courts-api` :8082 |
| `/api/v1/availability`, `/api/v1/reservations/**`, `/api/v1/admin/reservations/**` | `ftx-reservations-api` :8083 |
| `GET /health` | The gateway: `ok` or `degraded` with the state of each service |
| anything else | 404 `NOT_FOUND` |

The schedule routes are evaluated before the general courts route. The `ftx-front` app only needs
`VITE_API_BASE_URL=http://localhost:8080`.

## What the gateway does (and what it does not)

| Does | Does not |
|---|---|
| Route by path, with 2 s connect and 5 s response timeouts | Hold business rules (ADR-004) |
| **Remove `X-User-Id` / `X-User-Role` sent by clients** (07-api/authentication.md; STRIDE row 7 of TB1) | Decide who is an administrator: each service checks the RS256 token against the JWKS of `ftx-customers-api` |
| Keep a well-formed `X-Correlation-Id` or create one, send it to the service and return it | Log bodies, tokens or reservation codes |
| **Rate limit** per client address and minute: create reservation (10), look up / cancel by code (30), administrator login (10). Over the limit: 429 `TOO_MANY_REQUESTS` + `Retry-After` | Keep state: the counters are in memory (one instance in the MVP; Redis when there are several) |
| Answer its own errors with `ErrorResponse` (404, 503 service down, 504 timeout) | Hide errors of a service: they pass through untouched |
| CORS for the web app origin (`GATEWAY_CORS_ORIGINS`) | Accept other origins |

A service that is down returns 503 for its routes only; the others keep working.

## Configuration

| Variable | Default |
|---|---|
| `CUSTOMERS_API_URL` | `http://ftx-customers-api:8081` |
| `COURTS_API_URL` | `http://ftx-courts-api:8082` |
| `RESERVATIONS_API_URL` | `http://ftx-reservations-api:8083` |
| `SCHEDULES_API_URL` | `http://ftx-schedules-api:8085` |
| `GATEWAY_CORS_ORIGINS` | `http://localhost:5173` |
| `GATEWAY_RATE_CREATE` / `GATEWAY_RATE_LOOKUP` / `GATEWAY_RATE_LOGIN` | `10` / `30` / `10` per minute |
| `PORT` | `8080` |

## Architecture

```
ApiGatewayApplication
RoutesConfiguration      routes (Java DSL) and timeouts
CorsFilter               CORS for the web app origin, answered before routing
EdgeHeadersFilter        strips X-User-*, sets X-Correlation-Id      (first global filter)
RateLimitFilter          429 on create / look-up / login floods       (second global filter)
GatewayErrorHandler      ErrorResponse for 404 / 503 / 504
GatewayHealthController  GET /health with the state of each service
```

## Run it

From [`ftx-workflow`](https://github.com/code-corhuila/ftx-workflow), with the whole platform. Alone:

```bash
docker build -t ftx-api-gateway .
docker run --rm --network platform -p 8080:8080 ftx-api-gateway
```

## Tests

```bash
mvn verify                       # or, on Windows without Java: run-tests.bat (Docker only)
```

Fake services (JDK `HttpServer`) stand behind the gateway: every path reaches its service; spoofed
identity headers never arrive, the token and a correlation id do; a malicious correlation id is
replaced; unknown path → JSON 404; a service down → 503 while the others answer; `/health`
reports `degraded`; CORS accepts the web app and refuses another origin. The rate limiter is
tested with a fixed clock: the 4th creation in a minute → 429 with `Retry-After`, each client has
its own counter, code guessing is limited and other paths are not.

## Branching

`develop`, `qa` and `main` accept no direct commits. `hu-<id>-dev → develop`, then
`git cherry-pick -x` into `qa`, then into a release branch `→ main` with the instructor's approval
and the tag `v3.0.0`.
