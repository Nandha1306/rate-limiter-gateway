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
