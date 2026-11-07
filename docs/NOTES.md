# Notes: bugs hit and decisions made

Interview prep log (plan Phase 0). Add an entry whenever something breaks or a choice is made.

## Phase 0: setup

- **One Maven build, standalone modules.** The root `pom.xml` only aggregates `gateway` and `mock-backend`. Each module inherits from `spring-boot-starter-parent` directly, so it can also be built alone, which is what the Phase 3 Dockerfiles need.
- **`application.yml` is committed.** It contains no secrets. Secrets come from environment variables; personal overrides go in `application-local.yml`, which is gitignored.
- **Spring Boot 3.5.x, not 4.x.** The plan targets the Resilience4j Spring Boot 3 starter, so the Initializr default of 4.1.2 was changed to 3.5.16.

- **Bug hit: the gateway died on startup.** Jenkins was already listening on 8080 on this machine. `Get-NetTCPConnection -LocalPort 8080` showed a java process whose parent was `jenkins`. The fix was a local-only port override (8090) in the gitignored `application-local.yml`, which `application.yml` imports with `optional:classpath:` so it applies without a profile flag (the IDE run button doesn't pass one). The committed default stays 8080 for Docker; Phase 3's `.dockerignore` must exclude `**/application-local.yml`.
- **Bug hit: IntelliJ showed everything red and Run did nothing.** The `.idea` folder described a plain Java module whose only source folder was `mock-backend/src`. It had no Maven import, so it had no Spring jars and the package paths were wrong. The fix was to register the root `pom.xml` with IntelliJ's Maven support, remove the hand-made module, and add shared run configurations in `.run/`. Lesson: check what owns a port before assuming the app is broken. **Phase 3 follow-up:** Nginx publishes host port 8080 too, so make it configurable in Compose (`"${NGINX_PORT:-8080}:80"`).

- **Bug hit: still red after the Maven import.** IntelliJ's `idea.log` showed a Guice `CreationException` in `Maven3XServerEmbedder.customizeComponents` (`ArtifactHandlerManager`, `DefaultArtifactFactory`). The cause: Initializr pinned the Maven wrapper to **3.10.0**, and IntelliJ 2025.1 (set to "use Maven wrapper") can't embed a Maven that new, so dependency resolution aborted. The command line worked because it runs Maven directly. The fix was to pin `.mvn/wrapper/maven-wrapper.properties` to **3.9.9**, the version IntelliJ 2025.1 bundles. Lesson: when the IDE and the CLI disagree, read the IDE log; don't keep re-importing.

## Phase 1: in-memory token bucket

- _To do: remove `synchronized`, watch the concurrency test fail, explain why here._
