# Test Manual

How to verify the rate-limiter gateway, phase by phase: automated tests first, then manual checks with exact commands and expected results. **Updated at the end of every phase.**

- **Covers:** Phase 0 – Phase 3 (MVP)
- **Last verified:** 2026-11-11 on Windows 11, JDK 21, Docker Desktop 28.4, Maven wrapper 3.9.9
- **Commands:** PowerShell, run from the project root `D:\rate-limiter-gateway`. In Git Bash, use `./mvnw` and plain `curl`.

---

## 1. Prerequisites

| Need | Why | Check |
|---|---|---|
| JDK 21 | Build and run | `java -version` |
| Docker Desktop **running** | Redis tests (Testcontainers), images, Compose, k6 | `docker version` shows a *Server* section |
| Free ports | See below | `Get-NetTCPConnection -State Listen -LocalPort 8081,8082,8083,8088,8090,6379` |

**Ports on this machine:**

| Port | Used by |
|---|---|
| **8080** | **Jenkins.** Don't use it |
| 8090 | Local gateway (via the gitignored `application-local.yml`) |
| 8088 | Nginx in Compose (`NGINX_PORT=8088`) |
| 8081–8083 | Mock backends |
| 6379 / 16379 | Redis |

**PowerShell notes:**
- **Use `curl.exe`, not `curl`.** In PowerShell, `curl` is an alias for `Invoke-WebRequest`.
- **Escape JSON quotes in PowerShell 5:** `-d '{\"item\":\"book\"}'`.

**Demo API keys:**

| Key | Client | Tier |
|---|---|---|
| `demo-free-key-1` | `user_1` | free |
| `demo-free-key-2` | `user_2` | free |
| `demo-premium-key-3` | `user_3` | premium |

---

## 2. Automated tests

### 2.1 Full suite (what CI runs)

```powershell
.\mvnw.cmd -B -ntp clean verify
```

**Expected** (end of Phase 3): `gateway` **137** tests, `mock-backend` **13** tests, 0 failures, `BUILD SUCCESS`.

### 2.2 One module, class or method

```powershell
.\mvnw.cmd -f gateway\pom.xml test
.\mvnw.cmd -f mock-backend\pom.xml test
.\mvnw.cmd -f gateway\pom.xml test "-Dtest=RateLimitFilterTest"
.\mvnw.cmd -f gateway\pom.xml test "-Dtest=InMemoryTokenBucketTest#shouldAllowExactlyCapacityRequestsUnderConcurrency"
```

Keep the quotes around `-Dtest=...`; without them, PowerShell can split the argument at `.` or `#`.

### 2.3 Tests that need Docker

These start a throwaway `redis:7-alpine` container (Testcontainers):

| Class | Tests | What it proves |
|---|---|---|
| `RedisTokenBucketLimiterTest` | 10 | Lua bucket: allow, deny, retry-after, refill, hash + expiry, overflow caps; **3 clients × 60 threads → exactly 100 allowed** (×5) |
| `NaiveFixedWindowLimiterTest` | 7 | Never under-admits; Redis counter = allowed; logs the over-admission |
| `RedisWarmupTest` | 3 | Warm-up works, survives an unreachable Redis, skipped in MEMORY mode |

- **Docker off:** these classes are **skipped**, not failed (`Skipped: N`). CI always runs them.
- **Docker on:** they must show `Skipped: 0`.

### 2.4 CI-like check: no backend reachable

Local runs can pass "for the wrong reason" if a mock backend happens to be running on 8081. To simulate CI, point the upstream at a dead port:

```powershell
.\mvnw.cmd -f gateway\pom.xml test "-Dgateway.upstreams.mock.urls[0]=http://127.0.0.1:1" "-Dtest=!GatewayPropertiesBindingTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

**Expected:** all tests pass. `GatewayPropertiesBindingTest` is excluded because it asserts the real `application.yml` URL, which this override replaces on purpose.

### 2.5 CI (GitHub Actions)

| Job | Runs | Must be |
|---|---|---|
| `Build and test` | `./mvnw -B -ntp verify` on JDK 21 | green |
| `Build Docker images` | builds both images; fails if either runs as root | green |

---

## 3. Phase 0: skeleton

| ID | Steps | Expected |
|---|---|---|
| P0-01 | `.\mvnw.cmd -f mock-backend\pom.xml spring-boot:run`, then `curl.exe http://localhost:8081/actuator/health` | `{"status":"UP"}` |
| P0-02 | `$env:LIMITER_STORE="MEMORY"; .\mvnw.cmd -f gateway\pom.xml spring-boot:run`, then `curl.exe http://localhost:8090/actuator/health` | `{"status":"UP"}` on **8090** (local override) |
| P0-03 | IntelliJ: open the root folder → Maven tool window lists `gateway` and `mock-backend`; Run `GatewayApplication` | Starts with no red imports. If imports are red, check that the Maven wrapper is **3.9.9**: IntelliJ 2025.1 can't load 3.10.0 |

---

## 4. Phase 1: in-memory token bucket

| ID | Steps | Expected |
|---|---|---|
| P1-01 | `.\mvnw.cmd -f gateway\pom.xml test "-Dtest=InMemoryTokenBucketTest"` | **26** tests pass: 6 single + concurrency ×20 |
| P1-02 | **Race experiment (manual, then undo):** in `InMemoryTokenBucket.tryAcquire`, change `synchronized (bucket) {` to `{`, then re-run P1-01 | The concurrency test **fails**: more than 100 allowed, varying per run. Restore with `git checkout -- gateway/src/main/java/io/github/nandha1306/gateway/limiter/local/InMemoryTokenBucket.java` |

---

## 5. Phase 2: mock backend, filters and proxy

**Setup:** Terminal 1 runs the mock backend (P0-01). Terminal 2 runs the gateway on 8090, either:
- with **MEMORY** store: `$env:LIMITER_STORE="MEMORY"` (no Redis needed), or
- with Redis: `docker run -d --name rlg-redis -p 6379:6379 redis:7-alpine`.

Set `$G = "http://localhost:8090"`.

| ID | Steps | Expected |
|---|---|---|
| P2-01 | `curl.exe -i -H "X-API-Key: demo-free-key-1" $G/api/orders/42`, run twice | `200`; `x-cache: MISS` then `HIT`; `X-RateLimit-Limit: 10`, `X-RateLimit-Remaining` decreasing; `X-Served-By: localhost:8081`; `X-Correlation-Id`; `X-Gateway-Id: gateway-local` |
| P2-02 | `curl.exe -i -X POST -H "X-API-Key: demo-free-key-1" -H "Content-Type: application/json" -d '{\"item\":\"book\"}' $G/api/orders` | `201`, `Location: /api/orders/<uuid>`, `"item":"book"` |
| P2-03 | `curl.exe -i -H "X-API-Key: demo-premium-key-3" $G/api/orders/1` | `200`, `X-RateLimit-Limit: 100` (premium bucket) |
| P2-04 | Burst: `1..12 \| % { curl.exe -s -o NUL -w "%{http_code} " -H "X-API-Key: demo-free-key-2" $G/api/orders/1 }` | About ten `200`s (1 token/s refills during the loop), then `429` |
| P2-05 | Inspect one 429: `curl.exe -i -H "X-API-Key: demo-free-key-2" $G/api/orders/1` right after P2-04 | `429`, `Retry-After: 1`, `X-RateLimit-Remaining: 0`, body `{"error":"rate_limited","retryAfterMs":...}` |
| P2-06 | `1..6 \| % { curl.exe -s -o NUL -w "%{http_code} " -X POST $G/auth/login }` | `200` ×5, then `429` (public route, 5/min per IP, no key needed) |
| P2-07 | `curl.exe -i $G/api/products` and `curl.exe -i -H "X-API-Key: wrong" $G/api/products` | `401 {"error":"invalid_api_key"}` both times |
| P2-08 | `curl.exe -i -H "X-API-Key: demo-free-key-1" $G/api/unknown` and `.../api/ordersXYZ` | `404 {"error":"no_route"}` both times; prefixes match whole path segments only |
| P2-09 | `curl.exe -i -H "X-API-Key: demo-free-key-1" "$G/api/%6Frders/1"` | `200`: the encoded path is decoded and still matched to `orders` |
| P2-10 | `curl.exe -i -H "X-API-Key: demo-free-key-2" -H "X-Correlation-Id: my-trace-001" $G/api/products` | `X-Correlation-Id: my-trace-001` echoed back |
| P2-11 | Same as P2-10 with `-H "X-Correlation-Id: has space"` | Replaced by a generated UUID (unsafe IDs are never logged) |
| P2-12 | `curl.exe -i -H "X-API-Key: demo-free-key-2" -H "X-Client-Id: admin" $G/api/orders/7` | Body `"clientId":"user_2"`: the spoofed header is overwritten |
| P2-13 | `curl.exe -X POST -H "Content-Type: application/json" -d '{\"failureRate\":1.0}' http://localhost:8081/chaos`, then `curl.exe -i -H "X-API-Key: demo-free-key-2" $G/api/products` | The backend's own `503 {"error":"chaos_failure"}` is passed through. **Reset:** `-d '{}'` to `/chaos` |
| P2-14 | `-d '{\"latencyMs\":2500}'` to `/chaos`, then P2-13's request | `503 {"error":"no_healthy_backend"}`, `Retry-After: 5` (2 s read timeout). **Reset afterwards** |
| P2-15 | Stop the mock backend, then `curl.exe -i -H "X-API-Key: demo-free-key-2" $G/api/products` | `503 no_healthy_backend`, `Retry-After: 5`; gateway log shows a WARN tagged `[correlationId] [user_2]` |
| P2-16 | `curl.exe $G/actuator/health` (no key) | `200 {"status":"UP"}`: actuator skips routing and auth |
| P2-17 | Bad config: build the jar, then `java -jar gateway\target\gateway-0.0.1-SNAPSHOT.jar "--gateway.routes[0].path-prefix=api/orders"` | Startup **fails** and lists every error (e.g. `pathPrefix must start with '/'`). Overriding one list element replaces the whole list, so several errors appear; that's expected |

---

## 6. Phase 3: Redis, Docker, Nginx, Compose (MVP)

### 6.1 Gateway on Redis (local)

Run the gateway with Redis (P2 setup, Redis option) and send a few P2-01 requests.

| ID | Steps | Expected |
|---|---|---|
| P3-01 | `docker exec rlg-redis redis-cli --scan --pattern "rl:*"` | `rl:{orders:free:user_1}:tb` |
| P3-02 | `docker exec rlg-redis redis-cli TYPE "rl:{orders:free:user_1}:tb"`, then `HGETALL` and `PTTL` on it | `hash`; fields `tokens` (fractional) and `ts` (Redis clock, ms); PTTL ≤ 20000 (2 × full refill) |
| P3-03 | Gateway log at startup | `Redis connection ready (attempt N of 10)`, and the **first** request returns `200`, not `500` |
| P3-04 | `docker stop rlg-redis`, then one limited request | `500` within about 300 ms, **not a hang** (100 ms timeout). Known gap: Phase 5 adds the fallback. Restart Redis with `docker start rlg-redis` |
| P3-05 | `/actuator/health` while Redis is stopped | `200 UP`: Redis is deliberately excluded from health |

### 6.2 Docker images

| ID | Steps | Expected |
|---|---|---|
| P3-06 | `docker build -t rlg-gateway:dev gateway` and `docker build -t rlg-mock-backend:dev mock-backend` | Both build (first build takes minutes) |
| P3-07 | `docker run --rm --entrypoint id rlg-gateway:dev` (and the same for `rlg-mock-backend:dev`) | `uid=999(app)`: not root |
| P3-08 | `docker run --rm -m 512m --entrypoint java rlg-gateway:dev -XshowSettings:vm -version` | `Max. Heap Size (Estimated)` about 370 MB (75% cap) |
| P3-09 | Start the gateway image (see the README's Docker images section) and check its log | `Tomcat started on port 8080`, so `application-local.yml` was **not** baked in |

### 6.3 Nginx and Compose

| ID | Steps | Expected |
|---|---|---|
| P3-10 | Nginx syntax: `docker run --rm --add-host gateway1:127.0.0.1 --add-host gateway2:127.0.0.1 --add-host gateway3:127.0.0.1 -v "${PWD}\nginx:/conf:ro" nginx:alpine nginx -t -c /conf/nginx.conf` | `syntax is ok`, `test is successful` |
| P3-11 | `$env:NGINX_PORT="8088"; docker compose up -d --build --wait`, then `docker compose ps` | Exit 0; all services `healthy`; gateways show only `8080/tcp` (no host port); Nginx `0.0.0.0:8088->80` |
| P3-12 | `1..6 \| % { curl.exe -s -i -H "X-API-Key: demo-premium-key-3" http://localhost:8088/api/orders/$_ \| Select-String "X-Gateway-Id\|X-Served-By" }` | Rotates **gw-1 → gw-2 → gw-3**; each gateway is served by its own backend (`backend-1/2/3:8081`) |
| P3-13 | `curl.exe -s -o NUL -w "%{http_code}" http://localhost:8088/actuator/health` | `404` (hidden by Nginx) |
| P3-14 | Spoofing: `curl.exe -s -o NUL -X POST -H "X-Real-IP: 1.2.3.4" http://localhost:8088/auth/login`, then `docker compose exec redis redis-cli --scan --pattern "rl:{login*"` | Key uses the **real** address (e.g. `ip:172.x.0.1`), **not** `1.2.3.4` |
| P3-15 | Shared limit: `docker compose exec redis redis-cli DEL "rl:{demo-lua:free:user_2}:tb"`, then 104 sequential calls with `demo-free-key-2` to `http://localhost:8088/api/demo/lua` | **Exactly 100** `200`s and 4 `429`s, spread over gw-1/2/3 |
| P3-16 | `curl.exe http://localhost:8081/chaos` | `{"failureRate":0.0,"latencyMs":0}`: the backends are reachable for chaos |

### 6.4 Accuracy test: naive vs Lua (Phase 3 "Done when")

Run with the stack up (P3-11):

```powershell
# 1. warm up the JVMs (ignore this result)
docker run --rm --network rate-limiter-gateway_default -v "${PWD}\loadtest:/scripts:ro" -e API_KEY=demo-free-key-2 grafana/k6 run --quiet /scripts/k6-script.js
# 2. clear the demo buckets
docker compose exec redis redis-cli DEL "rl:{demo-lua:free:user_1}:tb" "rl:{demo-naive:free:user_1}:nfw"
# 3. measure each route
docker run --rm --network rate-limiter-gateway_default -v "${PWD}\loadtest:/scripts:ro" -e TARGET_PATH=/api/demo/lua grafana/k6 run --quiet /scripts/k6-script.js
docker run --rm --network rate-limiter-gateway_default -v "${PWD}\loadtest:/scripts:ro" -e TARGET_PATH=/api/demo/naive grafana/k6 run --quiet /scripts/k6-script.js
# 4. cross-check the naive counter in Redis
docker compose exec redis redis-cli GET "rl:{demo-naive:free:user_1}:nfw"
```

| ID | Check | Expected |
|---|---|---|
| P3-17 | Lua run | `allowed (200): 100`, `blocked (429): 900`, `unexpected: 0`, spread across gw-1/2/3, finishes in about 1–2 s |
| P3-18 | Naive run | `allowed (200)` **≥ 100**, usually over (measured 104, 111, 116); `unexpected: 0` |
| P3-19 | Naive counter (step 4) | Equals the naive `allowed (200)` exactly |
| P3-20 | `docker compose down`, then `docker ps -a --filter "label=com.docker.compose.project=rate-limiter-gateway"` | No containers left |

**Reading the results:**
- **Naive exactly 100** in one run isn't a failure: the race depends on timing. Run it again; over several runs it goes over.
- **`unexpected` > 0 in the first runs after startup** means cold JVMs exceeded the 100 ms Redis timeout. Do the warm-up (step 1). Phase 5's fallback removes these errors.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Gateway won't start: "Port 8080/8090 was already in use" | Jenkins on 8080, or an old gateway still running | `Get-NetTCPConnection -State Listen -LocalPort 8090` → find the owner and stop the stale process |
| Results look wrong (no Redis keys, wrong instance ID, cache already HIT) | Requests are going to a **different** process on that port (IntelliJ run, old jar) | Check the port owner first; this has happened three times |
| Red imports in IntelliJ | Maven wrapper newer than IntelliJ supports | Keep `.mvn/wrapper/maven-wrapper.properties` on 3.9.9; reload Maven |
| Redis tests show `Skipped` | Docker Desktop not running | Start it; re-run |
| `500` on the first request(s) after startup | Cold JVM exceeded the 100 ms Redis timeout | Should not happen since the warm-up; check for `Redis connection ready` in the log |
| CI fails, local passes | A test depended on something running locally | Run §2.4 (CI-like check) |
| `curl` behaves oddly in PowerShell | `curl` is `Invoke-WebRequest` there | Use `curl.exe` |

---

## 8. Verification log

| Date | Phase | Automated | Manual highlights |
|---|---|---|---|
| 2026-11-07 | 0 | Build green | Both apps start; IntelliJ fixed (Maven 3.9.9) |
| 2026-11-10 | 1 | 26/26 `InMemoryTokenBucketTest` | Concurrency test exactly 100, 20/20 |
| 2026-11-10 | 2 | 106 gateway + 12 mock | 10×200 then 429 `Retry-After: 1`; 401/404/503 contract; spoofed client ID replaced |
| 2026-11-11 | 3 | 137 gateway + 13 mock, Redis tests not skipped | Compose healthy in 68 s; rotation gw-1/2/3; spoofed `X-Real-IP` ignored; accuracy: Lua 100/100/100, naive 104/111/116 |
