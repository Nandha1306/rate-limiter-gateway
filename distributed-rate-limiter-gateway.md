# Distributed Rate Limiter + API Gateway (v2, build-ready)

**Stack:** Java 21 (25 optional), Spring Boot 3.5.x (Spring MVC + virtual threads), Redis 7 (Lua), Resilience4j, Micrometer, Prometheus, Grafana, Nginx, Docker Compose, k6, Testcontainers
**Goal:** A system-design-focused portfolio project for SDE / Backend Engineer roles (fresher)
**Estimated time:** ~7 weeks at ~2 hrs/day alongside DSA prep. MVP (resume-ready) by end of week 3.

---

## 0. What Changed from v1

| Area | v1 problem | v2 fix |
|---|---|---|
| Lua clock | Script read time from the gateway while recommending Redis time | Every script reads time from Redis itself |
| Lua commands | Deprecated HMSET, no retry-after value | HSET, and every script returns allowed / remaining / retry-after |
| Filter order | Resilience ran before routing, but breakers are per backend | Correlation → Route → Auth → RateLimit → Proxy (router + per-backend breaker + retry on next backend) |
| Per-IP limits | Auth rejected unauthenticated requests before the limiter ran | Public routes skip auth and are limited by IP |
| Redis Cluster | Sliding window counter used two keys in different slots | Single-key design plus hash tags in every key |
| Sliding window log | Same-millisecond requests collapsed into one entry | Each entry gets a unique member (timestamp + UUID) |
| Hot keys | "Shard by key hash" (can't split one key) | Local pre-check, local token leasing, or N sub-keys |
| Redis down | Lettuce's 60 s default timeout made fail-open hang | 100 ms timeout, reject commands when disconnected, circuit breaker around Redis |
| Fail-open | Applied everywhere, including login | Per-route failure mode: FAIL_OPEN, FAIL_CLOSED, LOCAL_FALLBACK |
| Config pub/sub | Missed messages were lost forever | Versioned config plus a 30 s polling backup |
| Backends | Unclear whether A/B/C were different services | 3 replicas of one service with a per-client cache (justifies consistent hashing) |
| Tiers | Ambiguous next to capacity | Every tier has its own full limit spec; tier is part of the key |
| Algorithm switch | Same key reused across algorithms causes WRONGTYPE errors | Algorithm code is part of the key |
| Tests | "allowed ≤ limit" is wrong for a token bucket | Bound is burst + rate × elapsed; tolerance for approximate algorithms |
| Metrics | Planned to rebuild HTTP and breaker metrics | Reuse built-in metrics; only 3 custom ones |
| Docker Compose | No healthchecks, no Grafana provisioning | Redis healthcheck, Prometheus scrapes all gateways, Grafana auto-provisioned |

---

## 1. Problem Statement

Backend services can be overloaded or abused by:
- Buggy clients or bots sending thousands of requests per second
- One heavy user starving everyone else
- Brute-force attacks on login or payment APIs
- Free-tier users consuming paid-tier capacity

A **rate-limiting API gateway** sits in front of services and enforces fair usage.

**The hard part:** with multiple gateway instances, in-memory counters don't work. A user limited to 100 req/min could send 300 (100 per instance). Counters must be **shared and atomic** across instances. That is the core system design challenge.

---

## 2. Goals and Non-Goals

### Goals
- Per-client, per-route, per-tier rate limiting (API key for private routes, IP for public routes)
- Correct limits across N gateway instances, **proven by a test**
- Multiple algorithms, switchable per route at runtime
- Runtime config changes without restart, robust to missed messages
- Fault tolerance: per-backend circuit breakers, retries on idempotent requests, timeouts, configurable behavior when Redis is down
- Observability: metrics, dashboards, structured logs with correlation IDs
- Measured performance: benchmarks published in the README

### Non-Goals
- Full auth system (static API keys in config are enough)
- Service discovery (static backend list)
- Multi-region replication
- Production-hardened Redis (documented, not built)

---

## 3. Architecture

### Components

| Component | Count | Responsibility |
|---|---|---|
| Nginx | 1 | Entry point on port 8080; spreads traffic across gateways; sets the client IP header; blocks public access to actuator endpoints |
| Gateway (Spring Boot MVC) | 3 | Auth, rate limiting, routing, resilience, admin API |
| Redis | 1 | Shared rate-limit state, route config, config-change pub/sub |
| Mock backend | 3 replicas | Same service ×3, with a per-client cache and a chaos endpoint |
| Prometheus | 1 | Scrapes all three gateways directly on the Docker network |
| Grafana | 1 | Auto-provisioned datasource and dashboard |

Draw this as `docs/architecture.png`: Clients → Nginx → 3 Gateways → (Redis, 3 Backend replicas), with Prometheus scraping the gateways and Grafana reading Prometheus.

### What the backends are
The three backends are **replicas of the same mock service**. Each one keeps a **per-client in-memory cache**, and a cache miss costs ~20 ms of simulated database work. That is why consistent hashing is used: the same client keeps landing on the same replica, so its cache stays warm. With stateless replicas, round-robin would be the right choice. Say this explicitly in interviews.

### Request flow inside one gateway
1. **Correlation ID filter:** reads the incoming correlation ID header or creates one; attaches it to logs and the response.
2. **Route resolution filter:** finds the matching route by longest path prefix. No match → 404.
3. **Auth filter:**
    - Private route: looks up the API key and gets the client ID and tier. Unknown key → 401.
    - Public route: the identity is the client IP, with the route's default tier.
4. **Rate limit filter:** runs the route's algorithm against Redis. Blocked → 429.
5. **Proxy:**
    - The router asks the consistent hash ring for an ordered list of backends for this client.
    - Backends whose circuit breaker is open are skipped.
    - The call uses connect and read timeouts.
    - On failure, GET/HEAD/OPTIONS requests retry on the **next** backend in the list, with exponential backoff and jitter.

This is **Chain of Responsibility**: each filter either passes the request on or short-circuits with 401, 404, 429 or 503.

Admin and actuator paths bypass the route, auth and rate limit filters. Admin paths have their own token check.

### Why Spring MVC + virtual threads (not Spring Cloud Gateway)
- Spring Cloud Gateway is reactive, so everything (Redis client, filters, proxy) must be reactive too. That is harder to debug while learning.
- Spring MVC with virtual threads enabled gives simple blocking code that still handles thousands of concurrent requests.
- **Interview answer for "why not Spring Cloud Gateway?"** It already ships a Redis token-bucket limiter. I built my own to understand and measure the internals, and chose MVC + virtual threads for simpler code.
- **Java 21 caveat:** a `synchronized` block that does blocking I/O pins a virtual thread to its carrier thread.
    - Use `synchronized` only for CPU-only code, like the in-memory bucket.
    - Use `ReentrantLock` around anything that talks to Redis.
    - Java 24+ removes this pinning.

---

## 4. Rate Limiting Algorithms

All algorithms implement one interface (**Strategy pattern**). A factory picks the implementation from the route config (**Factory pattern**). Every algorithm returns three things: allowed or not, remaining quota, and retry-after in milliseconds.

| Algorithm | How it works | Pros | Cons | Redis structure |
|---|---|---|---|---|
| **Token Bucket** | Bucket refills at a fixed rate; each request takes a token | Controlled bursts, O(1) memory | Bursts can surprise strict limits | Hash: tokens, last timestamp |
| **Sliding Window Log** | Stores the timestamp of every accepted request | Exact | Memory grows with the limit | Sorted set |
| **Sliding Window Counter** | Weighted blend of current and previous fixed windows | Good accuracy, O(1) memory | Approximate | Hash: window index, current count, previous count |
| Fixed Window | Counter that expires after the window | Simplest | Up to 2× limit at window boundaries | String counter |
| Naive Fixed Window (demo only) | Read, check and increment as three separate calls | Demonstrates the race condition | **Over-admits** across instances | String counter |
| None | No limiting | Baseline for benchmarks | None | None |

### Why Lua scripts?
A naive flow (read the count, check it, increment it) has a **race condition**: two instances both read 99, both allow, and the count becomes 101. Redis runs a Lua script **atomically**, so read-check-update is one operation. The naive algorithm exists so you can **demonstrate** this race in the README.

### Why Redis server time?
Each gateway's clock drifts. If one gateway is 2 seconds ahead, its refill math differs from the others. Reading time from Redis inside the script gives every instance one shared clock. This is safe on Redis 7.

### Token bucket logic
1. Read the current time from Redis.
2. Load the stored token count and last-update timestamp. If missing, start with a full bucket.
3. Add tokens for the elapsed time (elapsed × rate), capped at capacity.
4. If at least one token is available, take it and allow the request. Otherwise deny, and set retry-after = time needed to refill the missing fraction of a token.
5. Save the token count and timestamp.
6. Set the key to expire after twice the full-refill time, so idle clients clean themselves up.
7. Return allowed, remaining tokens (rounded down) and retry-after.

Gotcha: Redis truncates Lua numbers to integers when returning them, so fractional tokens are stored but only whole numbers are returned.

### Sliding window log logic
1. Read the current time from Redis.
2. Remove all entries older than one window.
3. Count the remaining entries.
4. Under the limit: add an entry with score = now and a **unique member** (timestamp plus a UUID passed in by the gateway), refresh the expiry, allow.
5. Over the limit: deny. Retry-after = when the oldest entry leaves the window.
6. Only accepted requests are logged, so blocked requests don't extend the client's penalty. Write this choice down in the design doc.

### Sliding window counter logic (single key, Cluster-safe)
1. Read the current time from Redis and compute the current window index.
2. Load the stored window index, current count and previous count from one hash.
3. Rotate the windows:
    - Same window as stored: keep both counts.
    - Exactly one window later: previous = current, current = 0.
    - More than one window later, or nothing stored: both = 0.
4. Estimate the count as previous × (fraction of the previous window still overlapping) + current.
5. Estimate below the limit: increment current and allow.
6. Otherwise deny. Retry-after = time until the weighted estimate drops below the limit, capped at the end of the current window.
7. Save, and expire after two windows.

v1 used two separate keys for the current and previous windows. Rotating windows inside one hash avoids the Redis Cluster multi-slot problem entirely.

### Fixed window logic
1. Increment the counter.
2. If this was the first increment, set the expiry to one window.
3. Allow while the count is within the limit. Otherwise deny, with retry-after = the key's remaining time-to-live.

### Key design
Format: `rl:{routeId:tier:clientId}:algorithmCode`

Examples: `rl:{orders:premium:user_42}:tb` and `rl:{login:anonymous:ip:172.18.0.1}:swl`

- **Braces (hash tag):** Redis Cluster hashes only the part inside the braces, so all keys for one client and route land in the same slot.
- **Tier in key:** an upgraded client gets a fresh bucket instead of inheriting state sized for the old tier.
- **Algorithm code in key:** switching a route from token bucket (a hash) to sliding log (a sorted set) would otherwise hit the old key and fail with a WRONGTYPE error.

---

## 5. Key Design Decisions (copy into docs/design-doc.md)

### 5.1 What if Redis goes down?
Each route sets its own failure mode:

| Mode | Behavior | Use for |
|---|---|---|
| FAIL_OPEN | Allow everything | Cheap read routes where availability matters most |
| FAIL_CLOSED | Block everything | Rarely right; it is a self-inflicted outage |
| LOCAL_FALLBACK (default) | In-memory token bucket per instance, with limit ÷ expected instance count | Login, payments, most routes |

**Never use pure fail-open on login.** It removes brute-force protection exactly when Redis is down.

Making the fallback fast:
1. **Redis command timeout of 100 ms.** Lettuce's default is 60 seconds, which would make every request hang before falling back.
2. **Reject commands immediately while disconnected,** instead of queuing them.
3. **A circuit breaker named "redis" around every limiter call.** After a few failures it opens, and requests go straight to the fallback with zero Redis wait. It probes again in the half-open state.
4. **A metric that counts every fallback,** plus a Grafana panel, so the outage is visible.

The local fallback reuses the in-memory token bucket from Phase 1. Its buckets live in a bounded, auto-expiring cache (Caffeine) so memory can't grow forever.

### 5.2 Why Redis?
- Sub-millisecond latency
- Atomic Lua scripting
- Native key expiry for automatic cleanup
- Pub/sub for config propagation

Lettuce, the Redis client, multiplexes commands over one connection, so a round trip per request is cheap even with thousands of virtual threads.

Alternatives considered:
- Hazelcast: an embedded data grid; heavier to run.
- A database: too slow for a per-request check.
- Local-only counters: inconsistent across instances.

### 5.3 Redis as a single point of failure
- Production answer: Redis Sentinel for automatic failover, or Redis Cluster for sharding plus replicas.
- In this project: document it, rely on the failure modes, and optionally add a replica as a stretch goal.

### 5.4 Hot keys
One very heavy client hammers a single key. **Sharding does not help**, because one key always lives on one shard. What actually helps:
- **Local pre-check:** if this instance blocked the client in the last few milliseconds, reject locally without calling Redis.
- **Local token leasing:** each instance takes a batch of tokens (e.g. 10) from Redis in one call and serves them locally. Far fewer Redis calls, but limits loosen by up to batch × instances.
- **N sub-keys:** split one logical bucket into N keys with limit ÷ N each, and pick one at random per request. Spreads load across shards; approximate.

### 5.5 Consistency vs performance
- Exact global limits cost one Redis round trip per request.
- Leasing and pre-checks trade accuracy for latency.
- Benchmark both and report the trade-off.

### 5.6 Config propagation
Pub/sub is fire-and-forget: an instance that is disconnected during a publish misses the message forever. The fix:
- A config version number in Redis, incremented on every change.
- Instances reload when they receive a pub/sub message **or** when a 30-second poll sees a different version.
- On startup, routes from the application config are used if Redis is unreachable, so the gateway still routes.
- Seeding uses "set only if absent", so three instances starting together don't overwrite admin changes or each other.

Known gap: the config write and the version increment are two separate commands. A crash between them leaves the change undetected until the next update. Production fix: do both atomically with a Lua script or a MULTI/EXEC transaction.

### 5.7 Client identity and IP trust
- Public routes are limited by the client IP from a header that Nginx sets.
- This is only safe because gateways are unreachable except through Nginx (no published ports).
- Trusting forwarded-IP headers from the open internet lets anyone spoof their IP. Say this in the design doc.

---

## 6. Resilience

| Mechanism | Setting | Notes |
|---|---|---|
| Per-backend circuit breaker | Opens at 50% failures over the last 20 calls (minimum 10), stays open 10 s, then 3 probe calls in half-open | Created dynamically per backend from one shared config |
| What counts as failure | Connection error, timeout, or status 500 or above | |
| Timeouts | Connect 500 ms, read 2 s | Force HTTP/1.1 on the JDK HTTP client to avoid upgrade attempts |
| Retries | Max 2 attempts, GET/HEAD/OPTIONS only, on the next backend in ring order, backoff 50 ms doubling with full jitter | POST is never retried (not idempotent) |
| Backends with an open breaker | Skipped without counting as an attempt | |
| No backend available | 503 with Retry-After: 5 | If every attempt returned 5xx, pass the last upstream response through |
| Redis breaker | Opens at 50% failures (minimum 5 calls), stays open 5 s | See 5.1 |
| Bulkhead (stretch) | Cap concurrent calls per backend | Matters more once virtual threads make unlimited concurrency easy |

### Proxy rules
- Strip hop-by-hop headers in both directions: Connection, Keep-Alive, Transfer-Encoding, Upgrade, Host, Content-Length, and similar.
- Never forward the client's API key to backends.
- Always overwrite the client ID header the gateway sends to backends, so clients can't spoof it.
- Forward the correlation ID to backends.
- Add a header to the response saying which backend served it.

---

## 7. Load Balancing: Consistent Hashing

- **Virtual nodes:** each backend is placed on a ring 150 times, by hashing its ID plus an index.
- **Lookup:**
    1. Hash the client ID.
    2. Walk the ring clockwise and collect **distinct** backends in order.
    3. The first is the primary; the rest are ordered failover candidates.
- **Why it beats hash % N:** adding or removing a backend remaps only ~1/N of clients instead of nearly all of them.
- **Natural failover:** the next backend on the ring is exactly where that client's keys would move if the primary were removed.
- **Implementation:** a thread-safe sorted map (`ConcurrentSkipListMap`), with the hash taken from the first 8 bytes of MD5.
- **Unit tests to write:**
    - Each of 3 backends gets roughly a third of 100k keys (within a few percent).
    - Adding a 4th backend moves roughly 25% of keys, and every moved key goes to the new backend.

---

## 8. Dynamic Configuration

### Route config fields

| Field | Meaning | Example |
|---|---|---|
| id | Unique route name | orders |
| pathPrefix | Matches the exact path or the path followed by "/" (so /api/orders doesn't match /api/ordersXYZ) | /api/orders |
| upstream | Backend pool name | mock |
| publicRoute | If true: no API key needed, limited per IP | false |
| algorithm | TOKEN_BUCKET, SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER, FIXED_WINDOW, NAIVE_FIXED_WINDOW, NONE | TOKEN_BUCKET |
| failureMode | FAIL_OPEN, FAIL_CLOSED, LOCAL_FALLBACK | LOCAL_FALLBACK |
| defaultTier | Tier used when the client's tier isn't listed, and always on public routes | free |
| tiers | Map of tier name to limit spec | free, premium |

### Limit spec (per tier)

| Field | Meaning |
|---|---|
| limit | Requests allowed per window (sustained rate) |
| windowSeconds | Window length |
| burst | Token bucket only: bucket capacity. Refill rate = limit ÷ windowSeconds per second. If 0, capacity = limit |

### Starting routes

| Route | Prefix | Public | Algorithm | Failure mode | Tiers |
|---|---|---|---|---|---|
| orders | /api/orders | No | Token bucket | Local fallback | free: 60/min, burst 10; premium: 600/min, burst 100 |
| products | /api/products | No | Sliding window counter | Fail open | free: 100/min; premium: 1000/min |
| login | /auth/login | Yes | Sliding window log | Local fallback | anonymous: 5/min per IP |

### Starting API keys (static config)
- Two free-tier keys, mapped to user_1 and user_2.
- One premium key, mapped to user_3.

### Validation rules (admin API returns 400 if broken)
- id, pathPrefix and upstream are required; pathPrefix starts with "/".
- upstream must exist.
- tiers is non-empty and contains defaultTier.
- Every tier: limit > 0, windowSeconds > 0, burst ≥ 0.
- On update, the ID in the path must equal the ID in the body.

### Storage in Redis

| Key | Type | Content |
|---|---|---|
| gw:routes | Hash | Field = route ID, value = route as JSON |
| gw:config:version | Integer | Incremented on every change |
| gw:config:updates | Pub/sub channel | Message = the new version number |

### Admin API (requires an admin token header, compared in constant time)

| Method | Endpoint | Purpose |
|---|---|---|
| GET | /admin/routes | List all routes |
| GET | /admin/routes/{id} | Get one route |
| POST | /admin/routes | Add a route (409 if it exists) |
| PUT | /admin/routes/{id} | Replace a route: limits, algorithm, failure mode |
| DELETE | /admin/routes/{id} | Remove a route |

---

## 9. Response Contract

| Situation | Status | Headers | Body |
|---|---|---|---|
| Allowed | Backend's status | X-RateLimit-Limit, X-RateLimit-Remaining, correlation ID, served-by backend | Backend's body |
| Rate limited | 429 | Retry-After (seconds, rounded up, minimum 1), X-RateLimit-Limit, X-RateLimit-Remaining: 0 | JSON with error "rate_limited" and retry-after in ms |
| Bad or missing API key | 401 | | JSON with error "invalid_api_key" |
| No matching route | 404 | | JSON with error "no_route" |
| No healthy backend | 503 | Retry-After: 5 | JSON with error "no_healthy_backend" |
| Fail-open mode | Backend's status | X-RateLimit-Remaining omitted (unknown) | Backend's body |

Mention in the design doc: the IETF HTTPAPI working group has a draft that standardizes RateLimit and RateLimit-Policy headers. The X-RateLimit headers used here follow the widespread convention.

---

## 10. Observability

### Reuse what already exists
| Metric | Comes from |
|---|---|
| HTTP request count and latency histogram, by status | Spring Boot Actuator |
| Circuit breaker state, including dynamically created backend breakers | Resilience4j Spring Boot integration |
| JVM, threads, GC | Actuator |

Enable percentile histograms for HTTP requests so p95/p99 can be computed in Prometheus.

### Custom metrics (only these three)
| Metric | Tags |
|---|---|
| Rate limit decisions (counter) | route, algorithm, tier, result (allowed / blocked) |
| Redis fallbacks (counter) | route, failure mode |
| Redis latency (histogram) | none |

### Grafana dashboard panels
1. Requests per second by status code
2. Requests per second per gateway instance (shows Nginx balancing)
3. Blocked percentage overall
4. Blocked per second by route
5. p95 and p99 request latency
6. Redis p99 latency
7. Redis fallbacks per second, by route and mode
8. Circuit breakers currently open

Build the dashboard in the Grafana UI, export it as JSON (**not** with the "export for sharing externally" option), and save it in the repo. Grafana provisioning then loads the Prometheus datasource and this dashboard automatically on every startup. Give the datasource a fixed UID so the dashboard always finds it.

### Logs
- Use Spring Boot's built-in structured JSON logging (ECS format).
- Put the correlation ID and client ID in the logging context (MDC) on every line.
- Clear the context at the end of each request.
- The correlation ID is forwarded to backends and returned to clients.

---

## 11. Project Structure

| Folder | Contents |
|---|---|
| gateway/ | Spring Boot gateway, Dockerfile, Lua scripts in resources |
| gateway: filter package | Correlation ID, route resolution, auth, rate limit and admin-auth filters |
| gateway: limiter package | Interface, result, limit spec, algorithm and failure-mode enums, key builder, factory, rate limit service |
| gateway: limiter/redis package | Lua-backed limiters and the naive demo limiter |
| gateway: limiter/local package | In-memory token bucket and the local fallback limiter |
| gateway: routing package | Backend model, consistent hash ring, backend router |
| gateway: proxy package | Proxy controller, proxy service, HTTP client setup, upstream exceptions |
| gateway: config package | Route config model, validator, dynamic config service, Redis setup |
| gateway: admin package | Admin controller |
| gateway: metrics package | Custom metrics |
| gateway tests | In-memory bucket unit tests, Lua scripts integration tests, hash ring unit tests |
| mock-backend/ | Tiny Spring Boot app with Dockerfile |
| nginx/ | Nginx config |
| monitoring/ | Prometheus config; Grafana provisioning (datasource + dashboard provider) and dashboard JSON |
| loadtest/ | k6 script |
| scripts/ | Chaos helper script |
| docs/ | architecture.png, design-doc.md, NOTES.md (bugs hit and decisions made: interview prep) |
| root | pom.xml (aggregator only: lists both modules, which inherit from spring-boot-starter-parent so each still builds alone for Docker), Maven wrapper, docker-compose.yml, README.md |

---

## 12. Setup

### Generate the projects
At start.spring.io: Maven, Java 21, latest Spring Boot 3.5.x, Jar packaging.

| Project | Dependencies from start.spring.io | Add manually |
|---|---|---|
| gateway | Spring Web, Spring Data Redis, Actuator, Prometheus, Validation, Testcontainers | Resilience4j Spring Boot 3 starter (latest 2.x from Maven Central), Caffeine (version managed by Spring Boot) |
| mock-backend | Spring Web, Actuator | Nothing |

### Gateway settings to configure

| Setting | Value | Why |
|---|---|---|
| Server port | 8080 | |
| Virtual threads | Enabled | Cheap blocking I/O |
| Redis host | From environment, default localhost | |
| Redis command timeout | 100 ms | Lettuce's 60 s default breaks fail-open |
| Redis connect timeout | 200 ms | |
| Actuator endpoints exposed | health, info, prometheus | |
| Logging | Structured JSON (ECS) | |
| Circuit breaker configs | "backend" and "redis", values from section 6 | |
| Expected instances | 3 | For the local fallback limit split |
| Admin token | From environment | |
| Upstream timeouts | Connect 500 ms, read 2 s | |
| Max attempts | 2 | |
| Virtual nodes | 150 | |
| Config poll interval | 30 s | |
| Upstream "mock" | 3 backend URLs, from environment | |
| API keys and routes | As in section 8 | Seeded into Redis at startup |

### Mock backend behavior

| Endpoint | Behavior |
|---|---|
| GET /api/orders/{id} | Per-client cache keyed by client header + ID. Miss = 20 ms sleep, then cache. Returns instance ID and cache HIT/MISS headers |
| GET /api/products | Static product list |
| POST /api/orders | Returns 201 created |
| POST /auth/login | Returns a random token |
| POST /chaos | Sets a failure rate (0 to 1) and added latency. Called directly on the backend's port, never through the gateway |

All endpoints except /chaos apply the configured latency and random failures (503). The mock backend runs on port 8081 with virtual threads enabled.

---

## 13. Infrastructure

### Dockerfiles (gateway and mock-backend)
- Multi-stage build: compile with a Maven + Java 21 image, run on a slim Java 21 runtime image.
- Download dependencies in a separate layer before copying the source, so rebuilds are fast.
- Cap JVM memory as a percentage of the container's memory.

### Nginx
- One upstream group with all three gateways, plus keepalive connections.
- Set the client real-IP and forwarded-for headers.
- Return 404 for actuator paths (Prometheus scrapes gateways directly).
- Use HTTP/1.1 to the gateways.

### Docker Compose services

| Service | Image / build | Host port | Notes |
|---|---|---|---|
| redis | Redis 7 Alpine | None | Persistence off. Healthcheck with redis-cli ping |
| backend-1/2/3 | Build mock-backend | 8081, 8082, 8083 | Exposed only so you can call the chaos endpoint |
| gateway1/2/3 | Build gateway | None | Env: Redis host, instance ID, admin token, backend URLs. Start only after Redis is **healthy**, not just started |
| nginx | Nginx Alpine | 8080 (configurable: `${NGINX_PORT:-8080}`) | Starts after the gateways. Make the host port configurable: on the dev machine Jenkins already owns 8080 |
| prometheus | Prometheus | 9090 | Scrapes all 3 gateways every 5 s at the actuator Prometheus path |
| grafana | Grafana | 3000 | Provisioning folders and dashboards mounted |

Plain "depends on" doesn't wait for Redis to be ready, which is why the healthcheck condition matters.

---

## 14. Implementation Roadmap

Each phase has a **Done when** line. Don't move on until it's true.

### Phase 0: Setup (Day 1)
- [x] Public GitHub repo, README stub, NOTES.md
- [x] Generate gateway and mock-backend projects
- **Done when:** both apps start locally. ✅

### Phase 1: In-memory token bucket (Days 1-4)
- [x] Limiter interface, result, limit spec
- [x] Thread-safe in-memory token bucket (monotonic clock, synchronized)
- [x] Unit tests: refill over time, burst up to capacity, retry-after value
- [x] Concurrency test: 100 threads, 1000 attempts, bucket of 100 with near-zero refill. Expect exactly 100 allowed
- [ ] Remove the synchronization, watch the test fail, write down why in NOTES.md
- **Learn:** race conditions, atomicity, ConcurrentHashMap, CAS vs locks
- **Done when:** the concurrency test passes reliably 20 runs in a row. ✅ (`@RepeatedTest(20)`, all green)

### Phase 2: Mock backend, filters and proxy (Days 5-9)
- [x] Mock backend with all endpoints and the chaos endpoint
- [ ] Routes and API keys from static config
- [ ] Filters in order: correlation ID, route resolution, auth, rate limit (in-memory for now)
- [ ] Proxy to a single backend with timeouts and header stripping
- [ ] 429 response with all headers from section 9
- **Done when:** curl shows 200s, then 429s with a correct Retry-After.

### Phase 3: Redis + multiple instances (Days 10-15), MVP
- [ ] Redis client with the 100 ms timeout
- [ ] Token bucket Lua script using Redis time; key format with hash tags
- [ ] Naive limiter for the race demo
- [ ] Minimal Docker Compose: Redis, 3 gateways, Nginx, 3 backends
- [ ] Accuracy test through Nginx: naive over-admits, Lua doesn't. Screenshot both
- [ ] Integration test with Testcontainers: three separate Redis clients in one JVM against one Redis. Total allowed equals capacity
- **Done when:** `docker compose up` works and the accuracy test proves global limits. **Put the project on your resume now.**

### Phase 4: More algorithms (Days 16-19)
- [ ] Sliding window log (unique members)
- [ ] Sliding window counter (single-key rotation)
- [ ] Fixed window
- [ ] Factory, plus the algorithm code in the key
- [ ] Integration tests per script: limits, expiry, retry-after, same-millisecond requests (log), window rotation (counter)
- **Done when:** each route can use any algorithm and the tests pass.

### Phase 5: Redis failure handling (Days 20-23)
- [ ] Reject commands while disconnected
- [ ] Circuit breaker around Redis calls
- [ ] Three failure modes; local fallback with limit ÷ instances
- [ ] Fallback metric
- [ ] Chaos test: stop Redis during load. Latency stays low, fallback counter rises, limits still roughly enforced on login
- **Done when:** stopping Redis causes no request hangs.

### Phase 6: Routing and resilience (Days 24-29)
- [ ] Consistent hash ring with virtual nodes, plus distribution and remap tests
- [ ] Per-backend circuit breakers
- [ ] Retry on the next candidate for idempotent methods, with backoff and jitter
- [ ] 503 handling and last-response pass-through
- [ ] Chaos tests: set backend-2 failure rate to 100%. Its breaker opens and traffic fails over. Then stop backend-2 entirely
- [ ] Verify the cache HIT ratio stays high because of sticky routing
- **Done when:** a backend failure produces brief errors, then clean failover.

### Phase 7: Dynamic config (Days 30-34)
- [ ] Route config stored in Redis; seed with "set if absent"
- [ ] Versioning, pub/sub refresh, 30-second polling backup
- [ ] Admin API with validation and token check
- [ ] Tier semantics from section 8
- [ ] Test: change a limit through one gateway; all three apply it within a second
- [ ] Test: disconnect one gateway's pub/sub. It still catches up within 30 seconds
- **Done when:** limits and algorithms change at runtime with no restart.

### Phase 8: Observability (Days 35-38)
- [ ] Three custom metrics; enable HTTP percentile histograms
- [ ] Prometheus scraping all gateways
- [ ] Grafana provisioning and the dashboard from section 10
- [ ] Structured logs with correlation ID and client ID
- **Done when:** the dashboard loads automatically and shows a k6 run live.

### Phase 9: Benchmarks and docs (Days 39-45)
- [ ] k6 runs for every scenario in section 16
- [ ] Redis memory per 100k keys for each algorithm
- [ ] Design doc, architecture diagram, README
- [ ] Optional: blog post about one hard problem
- **Done when:** the README checklist in section 17 is complete.

---

## 15. Testing Strategy

| Type | What to test | Pass criteria |
|---|---|---|
| Unit | In-memory bucket: refill, burst, retry-after, concurrency | Exactly capacity allowed under 100 threads |
| Unit | Hash ring distribution | Each of 3 backends gets about 33% of 100k keys (±5%) |
| Unit | Hash ring remap | Adding a 4th backend moves about 25% of keys; every moved key goes to the new backend |
| Unit | Route matching | Longest prefix wins; /api/ordersXYZ doesn't match /api/orders |
| Integration (Testcontainers) | Each Lua script against real Redis | Correct allow/deny, remaining, retry-after, expiry |
| Integration | Three Redis clients in one JVM, same key | Sliding log: exactly the limit. Token bucket: at most burst + rate × elapsed |
| Distributed (Compose) | 1000 requests from 50 virtual users across 3 gateways | Same bounds as above; naive limiter clearly over-admits |
| Chaos | Stop Redis | No hangs; fallback metric rises; login stays limited |
| Chaos | Backend at 100% failures, then stopped | Breaker opens, traffic fails over, half-open recovery works |
| Config | Update through one gateway | All instances apply it; polling covers missed messages |
| Load (k6) | Baseline, burst, soak | See section 16 |

Important: for token bucket tests, the correct bound is **burst + refill rate × elapsed time**, because tokens refill during the test. The sliding window counter is approximate, so allow a small tolerance.

### k6 load test plan
- Scenarios selected by an environment variable:
    - **Baseline:** constant 500 req/s for 1 minute
    - **Burst:** ramp from 50 to 2000 req/s for 10 seconds, then back down
    - **Accuracy:** 1000 total requests from 50 virtual users
- Inputs from environment variables: base URL, API key, target path. Don't name the path variable PATH; it clashes with the shell.
- Count 200s and 429s separately with custom counters.
- Tell k6 that 429 is an expected status, otherwise it reports every block as a failure.

### Chaos script
A small shell script that can:
- Stop and start Redis
- Set a backend's failure rate or latency through its chaos endpoint
- Stop and start one backend

---

## 16. Benchmarks to Publish

For throughput benchmarks, use a route with limits high enough that nothing gets blocked, so you measure limiter overhead rather than blocking. Measure the blocked path separately.

| Scenario | Throughput | p50 | p95 | p99 | Notes |
|---|---|---|---|---|---|
| Direct to backend (no gateway) | | | | | Absolute baseline |
| Gateway, algorithm NONE | | | | | Gateway overhead |
| Token bucket + Redis | | | | | |
| Sliding window log | | | | | Higher memory |
| Sliding window counter | | | | | |
| Redis down, local fallback | | | | | Should be faster than with Redis |
| Backend failure, breaker open | | | | | |

Also report:
- **Accuracy:** with limit 100 across 3 instances, how many requests passed with the naive limiter vs each Lua algorithm.
- **Memory:** Redis memory per 100k keys for each algorithm. Measure with a small test that fills 100k distinct keys, and compare Redis memory info before and after. Fill sliding log keys to the limit, since their size grows with it.
- **Caveat:** k6 and all containers ran on one laptop, so absolute numbers are limited by that machine. Being honest about this reads well.

---

## 17. README Checklist (what recruiters look for)

- [ ] One-paragraph "what and why"
- [ ] Architecture diagram
- [ ] Quick start: one compose command and a few sample curl requests (allowed, blocked, admin update)
- [ ] Algorithm comparison table
- [ ] Race condition demo: naive vs Lua results
- [ ] Benchmark table with graphs
- [ ] Chaos results: Redis down and backend down, with dashboard screenshots
- [ ] Design decisions and trade-offs (link to the design doc)
- [ ] "What I'd do next"
- [ ] Grafana dashboard screenshot

## 18. Design Doc Outline (docs/design-doc.md)

1. Context and problem
2. Requirements (functional and non-functional)
3. Architecture and request flow
4. Algorithm choice and trade-offs
5. Key design (hash tags, tier, algorithm code)
6. Failure modes: Redis down, backend down, missed config messages
7. Consistency vs performance
8. Scaling: horizontal gateways, Redis Cluster, hot keys
9. Alternatives considered (Spring Cloud Gateway, Hazelcast, local-only)
10. Benchmark results
11. Known gaps and future work

---

## 19. Design Patterns Used

| Pattern | Where |
|---|---|
| Strategy | Rate limiting algorithms |
| Factory | Picks the algorithm from route config |
| Chain of Responsibility | Gateway filter pipeline |
| Observer / Pub-Sub | Config refresh across instances |
| Circuit Breaker | Per-backend and Redis failure isolation |
| Proxy | The gateway itself |
| Consistent Hashing | Backend selection with cache affinity |

---

## 20. Interview Talking Points

1. **Why not in-memory counters?** Each instance counts separately, so N instances allow N× the limit.
2. **Why Lua?** Atomic read-check-update. Show the naive vs Lua accuracy results.
3. **Token bucket vs sliding window?** Burst tolerance vs accuracy vs memory.
4. **Redis down?** Per-route failure modes; why login is never fail-open; why the 100 ms timeout and breaker are needed.
5. **Why consistent hashing for these backends?** Cache affinity. With stateless backends you'd use round-robin.
6. **Why is the router before the circuit breaker?** Breakers are per backend, and retries should go to a different backend.
7. **How do you scale it?** Stateless gateways scale horizontally; Redis scales with Cluster, and hash tags keep a client's keys in one slot.
8. **Hot key problem?** Sharding can't split one key; use local pre-checks, token leasing or sub-keys.
9. **Clock skew?** Redis server time inside the script.
10. **Sliding log bug you avoided?** Same-millisecond requests collapsing into one entry.
11. **Why is the algorithm in the key?** Switching algorithms would otherwise cause WRONGTYPE errors.
12. **Pub/sub reliability?** Versioning plus a polling backup.
13. **Why Spring MVC, not Spring Cloud Gateway?** Learning internals; virtual threads give scalability with simple code.
14. **How do you know it works?** Distributed tests, chaos tests, benchmarks, dashboards.
15. **What would you improve?** Atomic config writes, Redis Sentinel or Cluster, token leasing, adaptive limits, distributed tracing.

---

## 21. Stretch Goals

- Local token leasing, benchmarked against the plain version
- Redis replica + Sentinel
- Distributed tracing (OpenTelemetry + Jaeger)
- Adaptive rate limiting (lower limits when backend latency rises)
- Bulkhead per backend
- Atomic config writes
- Simple admin UI
- Kubernetes deploy with autoscaling

---

## 22. Weekly Milestones

| Week | Deliverable |
|---|---|
| 1 | Phase 0 skeleton (gateway + mock-backend apps start); thread-safe in-memory bucket with tests |
| 2 | Filters, proxy, 429 contract; Redis token bucket started |
| 3 | **MVP:** Redis + Lua across 3 instances in Docker, accuracy demo, on your resume |
| 4 | All algorithms; Redis failure handling and chaos test |
| 5 | Consistent hashing, per-backend breakers, retries, backend chaos tests |
| 6 | Dynamic config and admin API; metrics and Grafana |
| 7 | Benchmarks, design doc, README, optional blog post |

**Rule:** always keep a working version. Get to the end-to-end MVP first, then add one feature at a time, committing small and often.