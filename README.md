# Distributed Rate Limiter + API Gateway

A rate-limiting API gateway that enforces per-client, per-route limits that stay **correct across multiple gateway instances**. It uses atomic Redis Lua scripts, per-route failure modes for Redis outages, per-backend circuit breakers, and consistent hashing for cache affinity.

> **Status: Phase 2 in progress.** Mock backend and gateway route/API-key config done; next: the filter chain and proxy. The full plan is in [distributed-rate-limiter-gateway.md](distributed-rate-limiter-gateway.md); decisions and bugs hit are logged in [docs/NOTES.md](docs/NOTES.md).

## Built so far

| Phase | What | Proof |
|---|---|---|
| 0 | Multi-module Maven skeleton: `gateway` + `mock-backend` on Spring Boot 3.5, Java 21, virtual threads | Both apps start; `/actuator/health` is `UP` |
| 1 | Thread-safe in-memory token bucket (`InMemoryTokenBucket`): per-key buckets, per-bucket lock, monotonic injectable clock, retry-after | `InMemoryTokenBucketTest`: refill, burst, retry-after, per-key isolation, and 100 threads allowed exactly 100 (repeated 20×) |
| 2 (in progress) | Mock backend: per-client order cache, products, login, and a chaos endpoint for failures and latency | `ApiEndpointsTest`, `ChaosTest` |
| 2 (in progress) | Gateway config: routes, tiers, demo API keys and upstreams from `application.yml`, validated at startup (the gateway refuses to start on bad config) | `GatewayPropertiesBindingTest`, `GatewayConfigValidatorTest`, `GatewayConfigCheckTest`, `RouteConfigTest` |

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
