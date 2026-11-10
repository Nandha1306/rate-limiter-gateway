# Notes: bugs hit and decisions made

Interview prep log. Add an entry whenever something breaks or a choice is made.

## Phase 0: setup

- **One Maven build, standalone modules.** The root `pom.xml` only aggregates `gateway` and `mock-backend`. Each module inherits from `spring-boot-starter-parent` directly, so it can also be built alone, which is what the Phase 3 Dockerfiles need.
- **`application.yml` is committed.** It contains no secrets. Secrets come from environment variables; personal overrides go in `application-local.yml`, which is gitignored.
- **Spring Boot 3.5.x, not 4.x.** The plan targets the Resilience4j Spring Boot 3 starter, so the Initializr default of 4.1.2 was changed to 3.5.16.
- **Bug hit: the gateway died on startup.** Jenkins was already listening on 8080 on this machine. `Get-NetTCPConnection -LocalPort 8080` showed a java process whose parent was `jenkins`.
  - **Fix:** a local-only port override (8090) in the gitignored `application-local.yml`. `application.yml` imports it with `optional:classpath:`, so it applies without a profile flag; the IDE run button doesn't pass one. The committed default stays 8080 for Docker.
  - **Lesson:** check what owns a port before assuming the app is broken.
  - **Phase 3 follow-ups:**
    - Nginx publishes host port 8080 too, so make it configurable in Compose: `"${NGINX_PORT:-8080}:80"`.
    - `.dockerignore` must exclude `**/application-local.yml`.
- **Bug hit: IntelliJ showed everything red and Run did nothing.**
  - **Cause:** the `.idea` folder described a plain Java module whose only source folder was `mock-backend/src`. With no Maven import, there were no Spring jars and the package paths were wrong.
  - **Fix:** registered the root `pom.xml` with IntelliJ's Maven support, removed the hand-made module, and added shared run configurations in `.run/`.
- **Bug hit: still red after the Maven import.** IntelliJ's `idea.log` showed a Guice `CreationException` in `Maven3XServerEmbedder.customizeComponents` (`ArtifactHandlerManager`, `DefaultArtifactFactory`).
  - **Cause:** Initializr pinned the Maven wrapper to **3.10.0**. IntelliJ 2025.1 is set to "use Maven wrapper" and can't embed a Maven that new, so dependency resolution aborted. The command line worked because it runs Maven directly.
  - **Fix:** pinned `.mvn/wrapper/maven-wrapper.properties` to **3.9.9**, the version IntelliJ 2025.1 bundles.
  - **Lesson:** when the IDE and the CLI disagree, read the IDE log; don't keep re-importing.

## Phase 1: in-memory token bucket

### Design decisions (`InMemoryTokenBucket`)
- **One bucket per key** in a `ConcurrentHashMap`, created with `computeIfAbsent`. Creation is atomic, so two threads can never make two buckets for the same key.
- **Lock per bucket, not per limiter.** `synchronized (bucket)` means user_1 and user_2 never wait on each other. `synchronized` is fine here because the critical section is pure arithmetic with no I/O, so it can't pin a virtual thread on blocking calls (plan section 3).
- **`double` tokens.** Refill is continuous: 300 ms at 1 token/s is 0.3 tokens. A `long` would drop the fraction and refill slowly.
- **Monotonic, injectable clock.**
  - Production uses `System.nanoTime()`, which never jumps backwards when the wall clock changes.
  - Tests pass a `LongSupplier` backed by an `AtomicLong`, so they advance time exactly with no `Thread.sleep`, and are never flaky.
- **Retry-after** is `ceil((1 - tokens) / refillPerSecond × 1000)` ms, minimum 1 ms. With a near-zero refill rate it exceeds `Long.MAX_VALUE` and is clamped. The concurrency test hits this branch because it uses `windowSeconds = Long.MAX_VALUE`.

### Tests (`InMemoryTokenBucketTest`)
- **Six single-threaded tests:** first request allowed, empty bucket denied, refill over time, separate buckets per key, retry-after value, burst up to capacity.
- **Concurrency test:**
  - Setup: 100 threads × 10 attempts on one key, bucket of 100, clock frozen.
  - Result: exactly 100 allowed, repeated 20 times with `@RepeatedTest(20)`.
  - This meets the plan's Phase 1 "Done when".

### Race condition: what happens without `synchronized`
- **Experiment, still to run:** remove `synchronized (bucket)` in `tryAcquire`, run `InMemoryTokenBucketTest`, record the allowed counts, then restore it.
- **Observed:** _fill in the allowed counts from your runs (expected: more than 100, and different on each run)._
- **Why it over-admits:**
  1. **Check-then-act.** Reading `tokens`, checking `tokens >= 1.0` and subtracting are three separate steps. Two threads can both read `1.0`, both pass the check, and both subtract. One token admits two requests.
  2. **Lost updates.** `tokens -= 1.0` is itself read-modify-write. Two threads can read the same value and both write `value - 1`, so one subtraction vanishes and the bucket "refunds" a token.
  3. **Visibility.** Without a lock there is no happens-before edge, so a thread may keep using a stale `tokens` value cached by another core.
- **Why `synchronized` fixes it:**
  - **Mutual exclusion:** only one thread at a time runs the whole read, check and update for a bucket.
  - **Visibility:** releasing the lock publishes the writes to the next thread that acquires it.
- **Why `ConcurrentHashMap` alone is not enough:** it makes the *map* thread-safe (lookups and inserts), not the mutable state *inside* each `Bucket`.
- **Lock-free alternative (CAS):**
  - **How:** keep an immutable `State(tokens, lastRefillNanos)` in an `AtomicReference`. Loop: read state, compute the new state, `compareAndSet`, and retry if another thread won.
  - **Trade-off:** no blocking, but under heavy contention on one key threads spin and retry. A per-key lock is simpler and fast enough here.
- **Phase 3 link:** Redis Lua scripts solve the same check-then-act race across *processes*, where a JVM lock can't help.

### Known gaps
- **The bucket map never shrinks.** Every new key stays in memory forever. Phase 5 moves the buckets into a bounded, auto-expiring Caffeine cache when this class becomes the Redis fallback.
- **The capacity cap only runs when time has passed.** If a route's capacity is lowered at runtime (Phase 7) and two calls land in the same nanosecond, `tokens` can briefly sit above the new capacity. Moving `Math.min(capacity, …)` outside the `elapsedNanos > 0` check would close it.

### Bug hit: the Phase 1 branch had no project structure
- **Cause:**
  - `phase-1/thread-safe-token-bucket` was created from a stale local commit (`392050e`), before the skeleton was merged into `main` via PR #1.
  - The token bucket commit went on top of that, so the branch held only the Initializr app plus two files.
  - IntelliJ also moved `InMemoryTokenBucket.java` into the old root `src/` by accident.
- **Fix:**
  - Undid the stray move; the file content was identical, so nothing was lost.
  - Merged `origin/main` into the branch.
  - Used a merge, not a rebase, because the branch was already pushed; a rebase would need a force push.
- **Lesson:** `git switch main && git pull` before cutting a new branch.

## Phase 2: mock backend, filters and proxy

Branch: `phase-2/rate-limited-proxy`, cut from `main` after Phase 1 was merged (PR #3).

### Decisions (agreed before coding)

| # | Decision | Why |
|---|---|---|
| 1 | **Header names:** `X-API-Key`, `X-Correlation-Id`, `X-Client-Id`, `X-Served-By`, `X-Real-IP`, `X-Instance-Id`, `X-Cache` | The plan describes these headers but doesn't name them; fixing the names once keeps the gateway, the mock backend and the tests consistent |
| 2 | **Every route uses the in-memory token bucket** until Phase 4; `NONE` always allows | Only the token bucket exists so far. The route's `algorithm` field is parsed now so config doesn't change later |
| 3 | **`X-RateLimit-Limit` reports the bucket capacity** | `X-RateLimit-Remaining` counts tokens, so reporting capacity keeps `Remaining ≤ Limit`. Reporting the per-window limit would give odd pairs like "Limit 60, Remaining 9" |
| 4 | **`RestClient` on the JDK `HttpClient`:** HTTP/1.1, connect 500 ms, read 2 s | Blocking client that suits virtual threads; forcing HTTP/1.1 avoids upgrade attempts (plan section 6) |
| 5 | **Route config is validated at startup** (plan section 8 rules) | Fail fast on a typo instead of at the first request. Phase 7's admin API reuses the same validator |
| 6 | **Bodies are buffered, not streamed** | Simple and enough for small JSON payloads. Logged as a known gap |
| 7 | **Proxy tests use the JDK `HttpServer` as a fake backend** | Needs a real socket to test timeouts and refused connections; avoids adding WireMock |

### Step 1: mock backend
- **Chaos is a `HandlerInterceptor` on `/api/**` and `/auth/**`,** not code in each controller. Every mock endpoint gets the same latency and failure behavior, and `/chaos` itself can never be broken by chaos, so you can always turn it off.
- **`ChaosSettings` is an immutable record in an `AtomicReference`.** A request reads one snapshot, so latency and failure decisions come from the same settings even if someone changes them mid-request. The record validates itself (`failureRate` 0–1, `latencyMs` 0–60000); bad input gets a 400 and leaves the current settings unchanged.
- **The order cache key is a `(clientId, orderId)` record,** not the string `clientId + ":" + orderId`. With strings, client `a:b` + order `c` would collide with client `a` + order `b:c`.
- **The cache miss sleeps outside the map,** then uses `putIfAbsent`. Sleeping inside `computeIfAbsent` would hold the map's internal lock for 20 ms and block other keys. Two concurrent misses on the same key can both report MISS, which is acceptable for a mock.
- **Every API response carries `X-Instance-Id`.** It comes from `INSTANCE_ID`, so in Phase 6 you can see which replica served a request and check sticky routing.
- **Tests:** 7 endpoint tests (cache MISS/HIT, per-client cache, anonymous client, create → 201 and cached, default item, products, login) and 4 chaos tests (rate 1 → 503 while `/chaos` still works, reset, latency, invalid input). Each test uses unique order IDs, and chaos is reset around every test, because the Spring context is shared.

### Step 2: route, API-key and upstream config
- **`GatewayProperties` is an immutable record tree** bound from `gateway.*`: `RouteConfig`, `ApiKeyConfig`, `UpstreamConfig`, `UpstreamTimeouts`. Collections are copied into unmodifiable ones, so nothing can change the config after startup. Phase 7 replaces whole routes rather than mutating them.
- **Defaults live on the records:**
  - `algorithm` defaults to `TOKEN_BUCKET`, `failureMode` to `LOCAL_FALLBACK` (plan section 5.1), and `timeouts` to 500 ms / 2 s.
  - An omitted `burst` binds as `0` (capacity = limit); a test proves it.
- **Tier fallback is the route's job:**
  - `RouteConfig.effectiveTier(clientTier)` returns the client's tier if the route lists it, otherwise `defaultTier`.
  - `limitFor(tier)` uses that tier. Step 6 also puts the *effective* tier in the rate-limit key, so a premium client on a route without a premium tier shares the free bucket instead of getting a phantom one.
- **Validation runs at startup and reports every error at once:**
  - `GatewayConfigValidator` holds the plan section 8 rules as pure static methods, so the Phase 7 admin API can reuse `validateRoute(...)`.
  - `GatewayConfigCheck` runs them during startup and throws `InvalidGatewayConfigException`, listing each error with its route label.
  - Rules added beyond plan section 8, each of which would make matching or proxying ambiguous: unique route ids, unique path prefixes, no trailing `/` on a prefix (except `/` itself), upstream URLs must be `http(s)` with a host, and timeouts > 0.
  - `LimitSpec` already rejects a bad limit while binding, so a `limit: 0` fails startup even before the validator runs.
- **API keys are never printed in full.** Validation errors mask them (`secr****`).
- **Demo API keys live in `application.yml`:** `demo-free-key-1` → `user_1`, `demo-free-key-2` → `user_2`, `demo-premium-key-3` → `user_3`. They're fine for a portfolio demo; production keys would come from a secret store or environment variables.
- **Upstream URL from the environment:** `${MOCK_BACKEND_URL:http://localhost:8081}`. Phase 3 points it at the Docker service names.
- **Gotcha found while testing: overriding one list element replaces the whole list.**
  - Running with `--gateway.routes[0].path-prefix=…` did **not** patch route 0. Spring Boot replaced the entire `routes` list from `application.yml` with a new list built only from the overriding source, so routes came out half-empty.
  - The validator listed every missing field and startup stopped, which is the fail-fast behavior we wanted.
  - **Lesson for Phase 3:** never override routes with indexed environment variables like `GATEWAY_ROUTES_0_...`. Use single-value placeholders such as `MOCK_BACKEND_URL`, or a whole profile file.
- **Tests:**
  - `GatewayPropertiesBindingTest` (4): the real `application.yml` binds as plan section 8 says.
  - `RouteConfigTest` (3): tier fallback.
  - `GatewayConfigValidatorTest` (12): one test per rule, plus "all errors reported with labels" and "keys masked".
  - `GatewayConfigCheckTest` (3): defaults applied; bad config and bad limit stop startup.

### Step 3: correlation ID filter
- `CorrelationIdFilter` runs first on every request (actuator included). It reuses an incoming `X-Correlation-Id` only if it is 1–64 characters of `[A-Za-z0-9_-]`; otherwise it generates a UUID. **Validating blocks log injection** (e.g. a `\n` that forges a log line).
- The ID goes into the MDC (shown in log lines via `logging.pattern.level`), a request attribute (for the proxy) and the response header. The MDC is cleared in `finally` because threads are reused.

### Step 4: route resolution
- **`RouteMatcher`** sorts routes by prefix length, longest first, so the most specific route wins. A prefix matches only whole path segments: `/api/orders` matches `/api/orders` and `/api/orders/1`, never `/api/ordersXYZ`. A `/` route would act as a catch-all.
- **`RouteResolutionFilter`:**
  - **No match:** answers `404 {"error":"no_route"}`.
  - **Match:** stores the route in a request attribute, which the auth and rate-limit filters use to decide whether to run.
  - **`/actuator/**`:** skipped entirely.
- **Matching uses the decoded path** (`UrlPathHelper`), without the query string or `;` parameters. Otherwise `/api/%6Frders/1` would miss the `orders` route, so its rate limit wouldn't apply, while the backend still decodes it as `/api/orders/1`. A test covers this bypass.
- **`ErrorResponses`** writes every gateway JSON error body; steps 5–7 reuse it.
- **Bug hit:** adding a second, package-private constructor to `RouteMatcher` for tests made Spring fail with "No default constructor found". With two constructors, Spring won't guess which to use. The fix was to mark the public one with `@Autowired`.
- **Tests:** `RouteMatcherTest` (5) and `RouteResolutionFilterTest` (5: no_route body, `/api/ordersXYZ`, query string ignored, encoded path decoded, actuator bypass).

### Step 5: auth
- **`AuthFilter`** runs only on routed requests (those with a route attribute), so unknown paths still get `404 no_route` and actuator stays open.
  - **Private route:** `X-API-Key` → `ClientIdentity(clientId, tier)`. A missing, blank or unknown key gets `401 {"error":"invalid_api_key"}`.
  - **Public route:** the identity is `ip:<address>` with the route's default tier. Any API key sent is ignored, so a premium key can't lift the per-IP login limit.
  - The identity goes into a request attribute (for the rate limiter and the proxy) and into the MDC as `clientId`. Log lines now show `[correlationId] [clientId]`; the MDC is cleared in `finally`.
- **`ApiKeyRegistry` stores only SHA-256 hashes of the keys** and looks up by the hash of the incoming key.
  - The plain-text keys aren't kept in memory after startup, so a heap dump doesn't leak them.
  - The string compare runs on digests, so its timing reveals nothing about the real key.
  - The keys still sit in plain text in `application.yml` (known gap).
- **`ClientIpResolver`:** uses `X-Real-IP` if it looks like an IP literal, otherwise the socket address. Anything else (hostnames, comma lists, empty) is ignored, so arbitrary strings can't become rate-limit keys.
- **The client's tier is stored as given.** The rate limiter maps it to the route's effective tier (step 6), because one key can hit several routes with different tier lists.
- **Tests:** `ApiKeyRegistryTest` (2), `ClientIpResolverTest` (3) and `AuthFilterTest` (7: no key, unknown or blank key, free and premium keys, public route by socket IP and by `X-Real-IP`, key ignored on a public route, unrouted requests skip auth, MDC cleared).

### Step 6: rate limiting and the 429 contract
- **Split of responsibilities:**
  - **`RateLimitFilter`** decides *what* to limit: route, effective tier (`route.effectiveTier`), client and `LimitSpec`.
  - **`RateLimitService`** decides *how*: which limiter and which key. The `limiter` package knows nothing about routes or HTTP, so Phase 3/4 can swap in Redis limiters without touching the filter.
- **Keys follow plan section 4:** `rl:{routeId:tier:clientId}:tb`. Each route, tier and client combination gets its own bucket. The *effective* tier is used, so a premium client on a route without a premium tier shares the free-tier bucket instead of getting a separate one.
- **Every algorithm except `NONE` is enforced with the token bucket for now** (decision 2). Unbuilt algorithms are still limited, never left open. `NONE` skips limiting and sends no rate-limit headers.
- **Response contract (plan section 9):**
  - **Allowed:** `X-RateLimit-Limit` = bucket capacity (decision 3) and `X-RateLimit-Remaining`. Both are set *before* the chain continues; the proxy in step 7 must keep them when copying backend headers.
  - **Blocked:** `429`, `Retry-After` in whole seconds (rounded up, minimum 1, overflow-safe), `X-RateLimit-Remaining: 0` and `{"error":"rate_limited","retryAfterMs":n}`.
- **The filter only runs when auth identified a client,** so 401s, 404s and actuator calls never use up tokens.
- **Test isolation:**
  - Buckets live as long as the Spring context, which tests share. So `RateLimitFilterTest` uses its own profile (`application-ratelimit-test.yml`) with routes that refill 1 token per hour, so nothing refills mid-test, and gives each test its own API key or IP.
  - **Bug hit:** JsonPath reads `retryAfterMs` (~3,599,999) as an `Integer`, so a `Long` Hamcrest matcher threw `ClassCastException`. Fixed by using int literals.
- **Live check** (`demo-free-key-1` on `/api/orders/1`, free tier: burst 10, 1 token/s): requests 1–10 showed `Remaining` 9→0, request 11 got `429` with `Retry-After: 1`.
- **Tests:**
  - `RateLimitKeysTest` (1).
  - `RateLimitServiceTest` (5): NONE allows, capacity enforced, unbuilt algorithms still limited, buckets separated, key format.
  - `RateLimitFilterTest` (9): headers on allowed requests, full 429 contract, premium burst, unlisted tier → default, separate clients, per-IP on public routes, NONE never limited, 401 not rate-limited, `Retry-After` rounding.

### Step 7: proxy
- **`ProxyController`** is a catch-all `@RequestMapping("/**")` that forwards every request the filters let through. Actuator keeps working because its handler mapping has higher priority than `/**`.
- **`ProxyClientConfig`:** a `RestClient` on the JDK `HttpClient` that speaks HTTP/1.1, never follows redirects (the client should see them), and uses connect 500 ms / read 2 s from `gateway.timeouts`.
- **`ProxyService`** forwards the method, the **raw** path and query (still percent-encoded, exactly as the client sent them) and the body. It uses `exchange()` so 4xx/5xx responses come back as data instead of exceptions.
  - A backend **5xx is passed through** as-is.
  - **Connection failure or read timeout** → `503 {"error":"no_healthy_backend"}` with `Retry-After: 5`, plus a WARN log with the most specific cause.
- **`ProxyHeaders`**, following plan section 6:
  - **Both directions:** drops hop-by-hop headers (`Connection`, `Keep-Alive`, `Transfer-Encoding`, `Upgrade`, `Host`, `Content-Length`, `TE`, `Trailer`, `Proxy-*`, `Expect`) **and any header named in `Connection`** (RFC 7230).
  - **To the backend:** never sends `X-API-Key`; always *overwrites* `X-Client-Id` (a spoofed one is replaced) and `X-Correlation-Id`.
  - **To the client:** never lets the backend overwrite the gateway's own `X-RateLimit-*`, `X-Correlation-Id` or `X-Served-By`. Multi-valued headers such as two `Set-Cookie`s survive.
  - **Adds** `X-Served-By: host:port` of the backend that answered.
- **Phase 2 uses the first URL of the upstream pool.** Phase 6 replaces this with the consistent hash ring and failover.
- **Tests run against a real HTTP server.** `StubBackend` (the JDK `HttpServer` on a random port) echoes back what it received, so the tests check what really went over the wire.
  - `ProxyIntegrationTest` (7): method, path, query and body forwarded; API key stripped and client ID overwritten; correlation ID forwarded; hop-by-hop and `Connection`-named headers stripped; response headers copied except gateway-owned ones; 5xx passed through; slow backend → 503.
  - `ProxyUpstreamDownTest` (1): closed port → 503.
  - `ProxyServiceTest` (3): URL joining.
- **Bug hit during the live check:** the first end-to-end run looked wrong (instance `mock-local` instead of `mock-1`, first request already a cache HIT).
  - **Cause:** IntelliJ was already running `MockBackendApplication` on 8081, so the copy started from the terminal failed with "Port 8081 was already in use", and the gateway talked to the IntelliJ one.
  - **Lesson:** check port owners (`Get-NetTCPConnection -LocalPort 8081`) before trusting an end-to-end result. The same lesson as the Jenkins issue in Phase 0.

- **Bug hit: CI failed but local runs passed.** `RateLimitFilterTest.allowedRequestCarriesRateLimitHeaders` failed on GitHub Actions with `expected: null but was: "5"` for `Retry-After`.
  - **Cause:** since step 7, an *allowed* request goes on to the proxy, which called the real upstream from `application.yml` (`localhost:8081`). Locally the IntelliJ mock backend was running there, so the test got a `200`. On CI nothing listens there, so the proxy answered `503 no_healthy_backend` with `Retry-After: 5`, which the test mistook for rate-limiter output. The local green runs were green for the wrong reason.
  - **Reproduced locally** by pointing the upstream at a dead port: `-Dgateway.upstreams.mock.urls[0]=http://127.0.0.1:1`.
  - **Fix:** moved `StubBackend` to a shared `support` test package. `RateLimitFilterTest` now points its upstream at the stub via `@DynamicPropertySource`, and allowed requests assert exactly `200` instead of "not 429". The other filter tests only check request attributes, so they pass with or without a backend. Verified by running the whole suite with the dead-port override.
  - **Lesson:** a test must not depend on what happens to be running on the developer's machine. Run the suite once with dependencies unreachable (as on CI) before pushing.

### Phase 2 "Done when": verified
Mock backend on :8081, gateway on :8090:
- `demo-free-key-1` on `/api/orders/1`: requests 1–10 → `200` with the order from the backend, `X-RateLimit-Remaining` 9→0, `X-Served-By: localhost:8081`, `X-Cache`, `X-Instance-Id`. Request 11 → **`429`, `Retry-After: 1`**.
- `demo-premium-key-3`: still `200` (`Limit 100`, `Remaining 99`), so tiers have separate buckets.
- `POST /auth/login` without a key: 5 × `200`, then `429` (5/min per IP).
- No key → `401 invalid_api_key`. Unknown path → `404 no_route`. Backend chaos 100% → backend's `503` passed through.
- Backend down → `503`, `Retry-After: 5`, `{"error":"no_healthy_backend"}`, and a WARN log line tagged `[correlationId] [user_2]`.

### Known gaps (Phase 2)
- The mock order cache never evicts entries. That's fine for a mock; it restarts with each container.
- Demo API keys are in plain text in `application.yml`. That's acceptable for a demo, but not for production.
- **Request and response bodies are buffered in memory** (decision 6). Large uploads or downloads would need streaming and a body size limit.
- **Only the first backend URL is used.** Load balancing, consistent hashing and failover arrive in Phase 6.
- **`X-Real-IP` can be spoofed until Nginx is in front (Phase 3).** Run locally, the gateway port is reachable directly, so a caller can send a fake `X-Real-IP` and get a fresh per-IP login budget each time. In Docker the gateways publish no ports and Nginx overwrites the header (plan section 5.7). A stricter option would accept the header only from a trusted proxy address.

### Bug hit: Phase 2 merged into `main` by mistake
- **What happened:**
  1. PR #4 merged the Phase 2 branch straight into `main` instead of `release`.
  2. It was reverted with `git revert -m 1` (#5).
  3. Phase 2 then reached `release` with a regular merge (#6).
- **Trap:** a reverted merge's commits stay in `main`'s history. `release` → `main` (#7) therefore changed **0 files**, because Git saw the same commits as already merged. A simulated merge (`git merge-tree --write-tree`) confirmed it before anything was claimed.
- **Fix:** revert the revert on a branch from `main` (#8). Verified that the result is identical to `release` (`git diff release <branch>` empty).
- **Lessons:**
  - check the PR's base branch before merging;
  - squash-merge feature branches into `release`, so `main` never sees the same commit IDs twice;
  - after reverting a merge, re-merging needs a "revert of the revert".

## Phase 3: Redis + 3 gateway instances (MVP)

Branch: `phase-3/redis-global-rate-limit`, cut from `main` after Phase 2 (`8d9c7f5`).

### Decisions (agreed before coding)

| # | Decision | Why |
|---|---|---|
| 1 | `gateway.limiter.store`: `REDIS` (default) or `MEMORY` | The gateway and most tests can run without Redis; the in-memory bucket becomes the Redis fallback in Phase 5 |
| 2 | Tests default to `MEMORY` (test-only `application.properties`); Redis tests use Testcontainers | The existing suite stays fast and needs no Docker |
| 3 | Redis tests are `@Testcontainers(disabledWithoutDocker = true)` | Skipped locally when Docker is off, always run in CI |
| 4 | Accuracy demo routes `/api/demo/naive` and `/api/demo/lua`, same limit (100/hour), different algorithm | A clean side-by-side; the slow refill keeps the expected count exactly 100 |
| 5 | Backend URLs as one comma-separated `MOCK_BACKEND_URLS`, a different first backend per gateway | Indexed overrides replace whole lists (Phase 2 lesson); spreads load until Phase 6 |
| 6 | `X-Gateway-Id` response header | Proves Nginx spreads requests across all 3 gateways |
| 7 | k6 run as a Docker container | No local install; plan section 15's tool |
| 8 | Nginx on `${NGINX_PORT:-8080}` (8088 on this machine because of Jenkins); backends on 8081–8083 | Plan section 13 plus the Phase 0 Jenkins lesson |

**Race-demo care:**
- No test asserts that the naive limiter *over*-admits, because a race isn't guaranteed on every run and that would make a flaky test. Tests assert it never *under*-admits, and log how much it over-admits.
- The demo reports only counts actually measured.

### Step 1: Redis client and store switch
- **Added `spring-boot-starter-data-redis`.** Lettuce multiplexes commands over one connection, so a Redis round trip per request stays cheap even with thousands of virtual threads (plan section 5.2).
- **`spring.data.redis.timeout: 100ms` and `connect-timeout: 200ms`.** Lettuce's default command timeout is 60 s, so a Redis outage would make every request hang before any fallback could run (plan section 5.1). Host and port come from `REDIS_HOST` / `REDIS_PORT`.
- **`LimiterProperties` (`gateway.limiter.store`, from `LIMITER_STORE`):**
  - It's a separate record from `GatewayProperties`, so existing constructors and tests stay untouched.
  - Invalid values stop startup.
  - The switch takes effect when the Redis limiter is wired in (steps 2–3).
- **Bug avoided: Redis would have made the gateway "DOWN".**
  - Adding Spring Data Redis auto-registers a Redis health check, so `/actuator/health` returned **503** with no Redis running. Verified by re-running the health test with `management.health.redis.enabled=true`.
  - That would have broken two existing tests. Worse, Docker and Nginx would treat a gateway as dead whenever Redis is down, which is the opposite of plan section 5.1, where the gateway keeps serving on a fallback.
  - **Fix:** `management.health.redis.enabled: false`. Redis status becomes a metric in Phase 8 rather than a liveness signal.
- **Tests:**
  - `RedisClientSettingsTest` (3): timeouts, tests use `MEMORY`, health `UP` without Redis.
  - `LimiterPropertiesTest` (3): default `REDIS`, switch to `MEMORY`, unknown value fails startup.
  - The full suite (112) passes with no Redis running.

### Step 2: token bucket in Lua
- **`resources/lua/token_bucket.lua`** does the whole read → refill → take → save in **one atomic Redis call**, so no other gateway can interleave between the check and the update (plan section 4):
  - **Time comes from `redis.call('TIME')`, never from the gateway,** so three gateways with drifting clocks still agree on how much has refilled.
  - **State is a hash `{tokens, ts}`.** A missing bucket starts full. Refill happens only when time has moved forward, and `tokens` is capped at capacity on *every* call. This closes the Phase 1 gap where the cap was skipped when no time had passed.
  - **Expiry is 2 × the full-refill time,** so idle buckets clean themselves up.
  - **Overflow guards:** with a near-zero refill rate (`windowSeconds = Long.MAX_VALUE`), `capacity / rate` is astronomically large. The expiry is capped at 30 days and retry-after at 2⁵³−1 ms, the largest integer a double holds exactly.
  - **Returns `{allowed, floor(tokens), retryAfterMs}`,** rounded in the script, because Redis truncates Lua numbers to integers on return.
- **`RedisTokenBucketLimiter`** implements the same `RateLimiter` interface as the Phase 1 bucket (Strategy pattern). It loads the script once; Spring runs it with `EVALSHA` and falls back to `EVAL` if Redis doesn't have it cached. It's a bean, but `RateLimitService` only starts using it in step 3.
- **Tests:** `RedisTokenBucketLimiterTest`, against a real `redis:7-alpine` in Testcontainers.
  - Allow/deny/remaining/retry-after; refill over real time; stored as a hash with the right expiry; separate keys; the near-zero-rate caps.
  - **Three separate Redis clients (standing in for 3 gateways), 60 threads, 1020 attempts on one key, capacity 100 → exactly 100 allowed**, 5 runs out of 5. The refill is 1 token per hour, so the plan section 15 bound (burst + rate × elapsed) is exactly 100 for a test lasting seconds.
  - `@Testcontainers(disabledWithoutDocker = true)`: skipped when Docker is off, and confirmed **not** skipped here (10 run, 0 skipped).
- **Bug hit: script file named `token_bucker.lua` and empty.** The Java side loads `lua/token_bucket.lua` by name, so it would have failed at the first request with a missing-resource error. Renamed and filled in before any test ran.

### Step 3: naive limiter, limiter selection and the race measured
- **`NaiveFixedWindowLimiter`** does `GET` → compare → `INCR` (+ `EXPIRE` on the first hit) as **separate Redis calls**, on purpose. Between one gateway's `GET` and its `INCR`, the other gateways can read the same count and also pass the check. It also has a second, quieter flaw: a crash between `INCR` and `EXPIRE` leaves a counter that never expires. The Lua script has neither problem because it's one atomic call.
- **`RateLimitService` now chooses the limiter:**

  | Store | Algorithm | Limiter | Key code |
  |---|---|---|---|
  | `MEMORY` | any limited one | in-memory bucket | `tb` |
  | `REDIS` | `NAIVE_FIXED_WINDOW` | naive (race demo) | `nfw` |
  | `REDIS` | every other limited one | Lua token bucket | `tb` |

  `NONE` always allows. **The key's algorithm code is the code of the limiter that actually runs, not the configured one.** `products` is configured as `SLIDING_WINDOW_COUNTER` but runs the token bucket, so its key ends in `:tb`. When Phase 4's real sliding-window script uses `:swc`, it can never read a leftover token-bucket hash (the WRONGTYPE problem, plan section 4).
- **Race demo, measured, not assumed:** 3 Redis clients, 60 threads, 1020 attempts, limit 100, real Redis (Testcontainers):

  | Run | Naive allowed | Lua token bucket allowed |
  |---|---|---|
  | 1 | 136 (+36) | 100 |
  | 2 | 118 (+18) | 100 |
  | 3 | 112 (+12) | 100 |
  | 4 | 119 (+19) | 100 |
  | 5 | 124 (+24) | 100 |

  These counts are from one local run; the naive numbers vary by timing. The tests deliberately **don't** assert over-admission, which would be flaky. They assert two guarantees instead:
  - **The naive limiter never allows fewer than the limit:** a deny means a read of ≥ 100, and the count only grows.
  - **The Redis counter equals the number allowed exactly:** one `INCR` per allowed request, none per deny.

  Over-admission is logged per run. The end-to-end demo through Nginx is step 8.
- **Bug hit: requests went to an old gateway.** The first live check showed no Redis keys, and a `200` even with Redis stopped. The new jar had failed with "Port 8090 was already in use": the Phase 2 gateway started earlier for manual testing was still running. Stopped it and re-ran. **Third time this lesson came up** (Jenkins, IntelliJ mock, old gateway): check the port owner before trusting a live result.
- **Bug hit: the first request after startup got `500` with Redis up.**
  - **Cause:** Lettuce opens its connection lazily, on the first command. Its connection setup ("Connection initialization timed out after 100 millisecond(s)") runs under the 100 ms command timeout. The first connect through Docker Desktop's port forwarding took longer, so the first user request failed; later requests reused the connection.
  - **Fix:** `RedisWarmup`, an `ApplicationRunner` that pings Redis at startup (up to 3 tries) when the store is `REDIS`. The connection cost moves off the request path, and the 100 ms request timeout stays. It never fails startup, because the gateway must start even with Redis down (Phase 5).
  - **Verified:** the same live check now gives request #1 → `200`, 0 errors in the log, and the warm-up logs "Redis connection ready (attempt 1 of 3)".
- **Behavior with Redis down, for now:** `500` in ~315 ms. It fails fast thanks to the 100 ms timeout, instead of hanging for 60 s. The per-route failure modes and local fallback are Phase 5.
- **Tests:**
  - `RateLimitServiceTest` (6, rewritten): which limiter and key code per store and algorithm; NONE; memory store never calls Redis.
  - `NaiveFixedWindowLimiterTest` (7): sequential limit and retry-after, string counter with window expiry, concurrency guarantees ×5.
  - `RedisWarmupTest` (3): reachable, unreachable (no failure), memory store skips it.
  - Full suite: 133 gateway + 12 mock-backend.

### Step 4: demo routes and multi-instance settings
- **Accuracy demo routes** `demo-naive` (`/api/demo/naive`, `NAIVE_FIXED_WINDOW`) and `demo-lua` (`/api/demo/lua`, `TOKEN_BUCKET`):
  - **Identical limit:** free tier 100 per **hour**, burst 0, so capacity = 100.
  - **Exactly 100 is the right answer for the Lua route:** the refill is 100/3600 ≈ 0.028 tokens/s, so a test lasting seconds gains well under one token, and the bound "burst + rate × elapsed" is 100.
  - **Re-runs:** the buckets last an hour, so a re-run within the hour must clear `rl:{demo-*` keys first (automated in step 8).
- **Mock backend `GET /api/demo/**`** returns `{"ok":true,"instanceId":...}`. It's cheap and always 200, so the accuracy test measures only the gateway's limiting, never backend latency or errors.
- **`MOCK_BACKEND_URLS` replaces `MOCK_BACKEND_URL`.** It's one comma-separated value (`gateway.upstreams.mock.urls: ${MOCK_BACKEND_URLS:...}`) that Spring binds to a `List<URI>`, trimming spaces around commas.
  - This avoids indexed environment variables, which would replace whole lists (the Phase 2 lesson).
  - Each gateway in Compose will list a different backend first, because the proxy still uses only the first URL until Phase 6.
  - Tests that set `urls[0]` through `@DynamicPropertySource` still work, because an indexed property in a higher-priority source wins.
- **`X-Gateway-Id`** comes from `INSTANCE_ID` (`gateway.instance.id`, default `gateway-local`):
  - Set by `GatewayIdFilter`, which runs first, so even 401/404/429 responses carry it.
  - It's on the proxy's "set by gateway" list, so a backend can't overwrite it. The stub backend now sends a fake `X-Gateway-Id`, and the test checks the client still sees the gateway's value.
  - It's what will prove Nginx spreads requests across all three gateways.
- **Live check** (real `INSTANCE_ID`, `MOCK_BACKEND_URLS` with two URLs and a space, Redis in a container):
  - The response carried `X-Gateway-Id: gw-1`, `X-RateLimit-Limit: 100` and `X-Served-By: localhost:8081` (the first URL).
  - 104 sequential calls → **100 × 200 then 4 × 429 on both demo routes**; the Lua key is a hash and the naive counter read exactly 100.
  - **This is expected:** the naive race needs requests running concurrently through several gateways, which a sequential single-gateway run can't produce. Step 8 measures that through Nginx.
- **Tests:**
  - `GatewayPropertiesBindingTest` (+1): the demo routes bind with the same limit.
  - `UpstreamUrlsBindingTest` (2): a comma list, with spaces, binds in order; a single URL still works.
  - `GatewayIdFilterTest` (1): header present on 200, 404 and 401.
  - `ProxyIntegrationTest`: a backend can't overwrite `X-Gateway-Id`.
  - `ApiEndpointsTest` (+1): `/api/demo/**`.
  - Full suite: 137 gateway + 13 mock-backend.

### Step 5: Dockerfiles
- **One multi-stage `Dockerfile` per module, built from the module folder alone.** This is why the modules don't inherit from the root POM (Phase 0 decision).
  - **Build stage:** `maven:3.9-eclipse-temurin-21`. `pom.xml` is copied and `mvn dependency:go-offline` runs **before** `src/` is copied, so dependencies sit in their own cached layer and only change when the POM does.
  - **Run stage:** `eclipse-temurin:21-jre`, which has no Maven and no JDK, just the jar.
  - **`-DskipTests` in the image build:** tests already run in CI, and Testcontainers can't start Docker from inside a Docker build.
- **Non-root:** a system user `app` (uid 999) runs the jar, so a compromise of the app isn't root in the container.
- **Memory:** `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`. The heap is sized from the *container's* limit, not the host's. On OOM the JVM exits so Docker can restart it, rather than running on in a broken state.
- **`.dockerignore` excludes `target/`, IDE files and `**/application-local.*`.** This closes the Phase 0 follow-up: without it, the local port-8090 override would be baked into the image.
- **Verified by running the images** (Redis, backend and gateway containers on one Docker network):
  1. `Tomcat started on port 8080` even though `application-local.yml` exists on disk, so the override was **not** baked in.
  2. Both images run as `uid=999(app)`.
  3. With `-m 512m`, the JVM's max heap is ~371 MB, i.e. 75% of what the JVM sees as available.
  4. The request through the gateway container → `200`, served by `rlg5-backend:8081` (`x-instance-id: backend-1`), `X-Gateway-Id: gw-1`, bucket key in the Redis container, Redis warm-up ready on attempt 1, 0 errors.
- **First build** took ~6 min (gateway) and ~2 min (mock-backend), mostly downloading base images and dependencies. Images are ~520 MB / ~500 MB.
- **CI:** a new `docker-images` job builds both images and fails if either runs as root, so a broken Dockerfile shows up on the next push, not at `docker compose up`.
- **Possible later improvements (not needed for the MVP):**
  - **Smaller images:** a distroless or Alpine JRE, or a `jlink`-trimmed runtime.
  - **Faster rebuilds:** Spring Boot's layered jar (`-Djarmode=tools extract --layers`), so a code change only rebuilds the small application layer.
