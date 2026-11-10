# Distributed Rate Limiter + API Gateway

A rate-limiting API gateway that enforces per-client, per-route limits that stay **correct across multiple gateway instances**. It uses atomic Redis Lua scripts, per-route failure modes for Redis outages, per-backend circuit breakers, and consistent hashing for cache affinity.

> **Status: Phase 2 done** (rate-limited proxy, in memory, single instance). Next: Phase 3, Redis + Lua shared across 3 gateway instances in Docker. The full plan is in [distributed-rate-limiter-gateway.md](distributed-rate-limiter-gateway.md); decisions and bugs hit are logged in [docs/NOTES.md](docs/NOTES.md).

## Built so far

| Phase | What | Proof |
|---|---|---|
| 0 | Multi-module Maven skeleton: `gateway` + `mock-backend` on Spring Boot 3.5, Java 21, virtual threads | Both apps start; `/actuator/health` is `UP` |
| 1 | Thread-safe in-memory token bucket (`InMemoryTokenBucket`): per-key buckets, per-bucket lock, monotonic injectable clock, retry-after | `InMemoryTokenBucketTest`: refill, burst, retry-after, per-key isolation, and 100 threads allowed exactly 100 (repeated 20×) |
| 2 | Mock backend: per-client order cache, products, login, and a chaos endpoint for failures and latency | `ApiEndpointsTest`, `ChaosTest` |
| 2 | Gateway config: routes, tiers, demo API keys and upstreams from `application.yml`, validated at startup (the gateway refuses to start on bad config) | `GatewayPropertiesBindingTest`, `GatewayConfigValidatorTest`, `GatewayConfigCheckTest`, `RouteConfigTest` |
| 2 | Correlation ID on every request: reuses a safe `X-Correlation-Id` or generates one, returns it, and tags log lines with it | `CorrelationIdFilterTest`, `CorrelationIdFilterIntegrationTest` |
| 2 | Route resolution: longest path prefix wins, whole segments only; unknown paths get `404 no_route` | `RouteMatcherTest`, `RouteResolutionFilterTest` |
| 2 | Auth: private routes need a valid `X-API-Key` (else `401 invalid_api_key`); public routes identify callers by IP | `AuthFilterTest`, `ApiKeyRegistryTest`, `ClientIpResolverTest` |
| 2 | Rate limiting per route, tier and client with `X-RateLimit-*` headers; `429` with `Retry-After` when the bucket is empty | `RateLimitFilterTest`, `RateLimitServiceTest`, `RateLimitKeysTest` |
| 2 | Proxy to the backend: timeouts, hop-by-hop header stripping, API key never forwarded, client ID always set by the gateway, `X-Served-By`; `503 no_healthy_backend` when the backend is down | `ProxyIntegrationTest` (against a real HTTP stub), `ProxyUpstreamDownTest`, `ProxyServiceTest` |

**Stack:** Java 21, Spring Boot 3.5 (MVC + virtual threads), Redis 7 + Lua, Resilience4j, Micrometer, Prometheus, Grafana, Nginx, Docker Compose, k6, Testcontainers

## Modules

| Module | Port | What it is |
|---|---|---|
| `gateway` | 8080 | The rate-limiting gateway |
| `mock-backend` | 8081 | Mock upstream service (3 replicas in Docker) |

## Build and test

Requires JDK 21. The Maven wrapper downloads Maven itself.

```bash
./mvnw verify                                      # build + test everything
./mvnw -f gateway/pom.xml spring-boot:run          # run the gateway on :8080
./mvnw -f mock-backend/pom.xml spring-boot:run     # run the mock backend on :8081
curl localhost:8080/actuator/health                # {"status":"UP"}
```

On Windows PowerShell, use `.\mvnw.cmd` instead of `./mvnw`.

**Port 8080 already taken (e.g. by Jenkins)?** Create the gitignored `gateway/src/main/resources/application-local.yml` with `server.port: 8090`. The gateway imports it automatically when it exists, so the IDE run button, Maven and `java -jar` all pick it up. For a one-off run you can also pass `--server.port=8090`.

## Try it locally

Start `MockBackendApplication` (:8081) and `GatewayApplication` (:8080, or :8090 with `application-local.yml`), then:

```bash
curl -i -H "X-API-Key: demo-free-key-1" localhost:8090/api/orders/1   # 200, X-RateLimit-Remaining: 9 ... 0
# 11th request within a few seconds:
# HTTP/1.1 429   Retry-After: 1   {"error":"rate_limited","retryAfterMs":...}
curl -i -H "X-API-Key: demo-premium-key-3" localhost:8090/api/orders/1  # premium tier: separate, larger bucket
curl -i -X POST localhost:8090/auth/login                              # public route: 5/min per IP
curl -i localhost:8090/api/products                                    # 401 invalid_api_key
curl -i -X POST localhost:8081/chaos -H "Content-Type: application/json" -d '{"failureRate":1.0}'  # backend 503s pass through
```

If the backend is unreachable, the gateway answers `503 {"error":"no_healthy_backend"}` with `Retry-After: 5`.

## Gateway configuration

Routes, tiers, API keys and upstreams live under `gateway.*` in `gateway/src/main/resources/application.yml`. Invalid config stops startup with every error listed.

| Route | Path prefix | Auth | Algorithm (configured) | Failure mode | Limits |
|---|---|---|---|---|---|
| `orders` | `/api/orders` | API key | Token bucket | Local fallback | free: 60/min, burst 10 · premium: 600/min, burst 100 |
| `products` | `/api/products` | API key | Sliding window counter | Fail open | free: 100/min · premium: 1000/min |
| `login` | `/auth/login` | Public (per IP) | Sliding window log | Local fallback | anonymous: 5/min |

Until Phase 4, every route is enforced with the in-memory token bucket; the configured algorithm takes effect when the Redis algorithms land.

**Demo API keys** (send as `X-API-Key`):

| Key | Client | Tier |
|---|---|---|
| `demo-free-key-1` | `user_1` | free |
| `demo-free-key-2` | `user_2` | free |
| `demo-premium-key-3` | `user_3` | premium |

**Backend URL:** set `MOCK_BACKEND_URL` (default `http://localhost:8081`).

**Routing:** the route with the longest matching path prefix wins, and a prefix matches whole path segments only (`/api/orders` matches `/api/orders/1`, not `/api/ordersXYZ`). A path no route matches gets `404 {"error":"no_route"}`. `/actuator/**` is served by the gateway itself.

**Auth:** private routes (`/api/orders`, `/api/products`) need one of the demo keys in `X-API-Key`; a missing or unknown key gets `401 {"error":"invalid_api_key"}`. The public `/auth/login` route needs no key and limits callers per IP, taken from `X-Real-IP` (set by Nginx in Docker) or the connection address.

**Rate limiting:** each route, tier and client gets its own bucket. Allowed responses carry `X-RateLimit-Limit` (bucket capacity) and `X-RateLimit-Remaining`. An empty bucket answers:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 1
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0

{"error":"rate_limited","retryAfterMs":834}
```

**Correlation ID:** every response carries `X-Correlation-Id`. Send your own (1–64 characters of letters, digits, `-`, `_`) to trace a request across logs; anything else is replaced with a generated UUID.

Don't override routes with indexed environment variables or flags such as `--gateway.routes[0]...`; that replaces the whole route list.

## Mock backend API

Every response from `/api/**` and `/auth/**` carries `X-Instance-Id` (the replica name, from the `INSTANCE_ID` environment variable, default `mock-local`).

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/orders/{id}` | Order for the client in `X-Client-Id` (default `anonymous`). The first read is `X-Cache: MISS` (20 ms simulated DB time), and later reads are `HIT` |
| POST | `/api/orders` | Creates an order (`{"item":"book"}`, body optional) and returns `201` with `Location` |
| GET | `/api/products` | Fixed product catalogue |
| POST | `/auth/login` | Returns a random token |
| GET / POST | `/chaos` | Reads or sets `{"failureRate": 0..1, "latencyMs": 0..60000}`. Omitted fields default to 0. Applies to `/api/**` and `/auth/**`, never to `/chaos` itself |

```bash
curl -H "X-Client-Id: user_1" localhost:8081/api/orders/7         # X-Cache: MISS, then HIT
curl -X POST localhost:8081/chaos -H "Content-Type: application/json" -d '{"failureRate":1.0}'   # every API call → 503
curl -X POST localhost:8081/chaos -H "Content-Type: application/json" -d '{}'                    # chaos off
```

**IntelliJ:** open the root folder; it imports as a Maven project. The shared run configurations in `.run/` (`GatewayApplication`, `MockBackendApplication`) appear in the Run dropdown.

## Coming later

Architecture diagram, quick start with Docker Compose, algorithm comparison, race-condition demo, benchmarks, chaos results, and the Grafana dashboard. See plan section 17.
