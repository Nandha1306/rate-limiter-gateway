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

### Known gaps (Phase 2)
- The mock order cache never evicts entries. That's fine for a mock; it restarts with each container.
- Demo API keys are in plain text in `application.yml`. That's acceptable for a demo, but not for production.
