# Improvement Backlog
## Completion pass - 2026-09-24

The cloud completion change addresses items 1, 2, 4, 5, 6, 8, 18, 19, 20, and
31. Item 3 now uses additive-first updates and refuses non-atomic automatic
deduplication. Item 7 has refresh/revocation locking within the current single
instance; distributed token writes still need transactional coordination before
scaling. Item 15 adds health probes, graceful HTTP shutdown, and JVM memory
limits; durable background-job recovery remains open. Item 17 gains persistent
logout revocation; absolute/idle session expiry remains open. Item 21 gains
session checks and username validation; explicit endpoint rate limiting remains
open. CI now verifies the real browser workflows (part of item 28).

Spotify February 2026 API compatibility is also addressed: playlist items,
nullable/non-music entries, search limits, and artist catalog search for Band Mix.
The findings below remain a historical audit; items not listed here are still
follow-up work, not completion claims.

Prioritized backlog produced from a full codebase review (2026-07-08). Four review
passes covered core services, the auth/web layer, persistence, frontend, and
build/infra. High-priority findings were verified against the code; file
references point at the current `main`.

Priorities: **P0** = bugs and security issues worth fixing first, **P1** =
reliability/performance, **P2** = security hardening, **P3** = architecture and
code health, **P4** = infra/CI/frontend polish.

---

## P0 — Correctness and security bugs

### 1. Timing-unsafe comparison of privileged bearer tokens
- `service/SpotifyTopPlaylistsRefreshService.kt:47`, `service/LastFmAuthenticationService.kt:65,77`
- The `X-Refresh-Token` secret gating `POST /refreshConfiguredTopPlaylists` is
  compared with `==`, which short-circuits on the first differing byte — a
  timing side-channel on a network-supplied secret. Last.fm session comparisons
  have the same pattern.
- Fix: compare with `MessageDigest.isEqual(...)` on UTF-8 bytes.

### 2. Playlist reads crash on null / non-track playlist items
- `domain/SpotifyDomain.kt` (`PlaylistTrack.track` is non-null), `service/SpotifyPlaylistService.kt:57`
- Spotify returns `"track": null` for removed/local items and a different shape
  for podcast episodes. Deserialization then throws and fails the whole page, so
  any playlist containing such an item aborts every top/yearly/band refresh for
  that user.
- Fix: make `track` nullable, skip null/episode items in `getPlaylistTracks`.

### 3. Destructive-first playlist writes can lose tracks on partial failure
- `service/SpotifyPlaylistService.kt:173-206`
- `deduplicatePlaylist` PUT-replaces the playlist with the first 100 unique
  tracks, then re-adds the rest; if the add fails (429/network/token) the
  playlist stays truncated at 100. `modifyPlaylist` deletes before adding with
  the same failure mode.
- Fix: reorder to additive-first, or make the sequence idempotent/restartable.

### 4. Unsynchronized check-then-create produces duplicate playlists
- `service/SpotifyPlaylistService.kt:230-254`, callers `service/SpotifyTopPlaylistsService.kt:82-105`, `service/SpotifyBandPlaylistService.kt:83`
- `PlaylistProvisioner` exists to serialize creation per `clientId|playlistName`,
  but `updateTopPlaylists` and the band-mix flow still call the unprotected
  `getOrCreatePlaylist`. Concurrent submissions create duplicate playlists.
- Fix: route all creation through `PlaylistProvisioner`.

### 5. No CSRF protection on state-changing endpoints
- `controller/JobsController.kt`, `controller/SpotifyBandPlaylistController.kt:33`, `controller/SpotifyTopPlaylistsController.kt:32`
- No Spring Security on the classpath; every mutating POST authenticates purely
  from cookies, with `SameSite=Lax` as the only cross-site mitigation.
- Fix: add a CSRF token (double-submit or synchronizer) or adopt
  `spring-boot-starter-security` with its CSRF + header defaults.

### 6. Firestore `deleteExpired` TOCTOU race can delete live jobs
- `persistence/FirestoreAppStateStores.kt:30-40` vs `persistence/InMemoryAppStateStores.kt:22-34`
- The in-memory store deliberately uses compare-and-remove so a concurrently
  refreshed job survives; the Firestore version queries then deletes
  unconditionally, so a job refreshed between snapshot and delete is lost.
- Fix: conditional deletes (transaction re-reading `expiresAt`, or `updateTime`
  precondition) to match in-memory semantics.

### 7. Token refresh persistence is last-write-wins
- `persistence/FirestoreAppStateStores.kt:22`, `service/SpotifyAuthenticationService.kt:101`
- `save` does a blind full-document `set()`. Two concurrent refreshes can
  clobber a newer `refresh_token` with an older one, invalidating the session.
- Fix: wrap refresh persistence in a transaction or precondition on `updatedAt`.

---

## P1 — Reliability and performance

### 8. Expired-job cleanup runs on every status poll
- `service/JobService.kt:36` (+ `:181`), frontend polls every 1 s (`static/index.js:245`)
- Each poll triggers a Firestore query plus up to 100 sequential blocking
  deletes on the request thread — latency and Firestore cost on the hottest path.
- Fix: move cleanup to an `@Scheduled` sweep (scheduling is already enabled)
  and/or a native Firestore TTL policy on `jobs.expiresAt`.

### 9. Scheduler pool (4) far below job cap (25)
- `Application.kt:31` vs `service/JobService.kt:380` (`MAX_ACTIVE_JOBS = 25`)
- Up to 21 "active" jobs sit queued while holding client slots; long jobs
  monopolize the 4 threads.
- Fix: align pool size with the job cap (make both configurable), or dispatch
  job bodies to a separate bounded executor.

### 10. Blocking calls and nested `runBlocking` on `Dispatchers.IO`
- `service/LastFmService.kt:236` (`yearlyChartlist` wraps its body in
  `runBlocking(Dispatchers.IO)` but is called from `async(Dispatchers.IO)` in
  `PrivateMoodTaxonomyService.kt:114-124` and `SpotifyTopPlaylistsService.kt:141-153,214-224`);
  `service/SpotifySearchService.kt:50-51,117` (default parallelism 64 — the entire
  default IO pool); blocking Firestore `.get().get()` calls from coroutines
  (`persistence/FirestoreAppStateStores.kt`).
- One search batch can occupy every IO thread while nested `runBlocking` blocks
  more of them; concurrent jobs starve each other.
- Fix: make `yearlyChartlist`/`fetchRecent` suspend, keep `runBlocking` only at
  top-level entry points, run blocking HTTP/Firestore work on a dedicated
  bounded dispatcher (`Dispatchers.IO.limitedParallelism(n)`), and lower the
  default search parallelism.

### 11. HTTP connection pool defaults below intended concurrency
- `service/SpotifyRestService.kt:36-40`
- Apache HttpClient defaults (~5 per route) silently serialize the up-to-64
  concurrent Spotify calls, holding threads while they wait for connections.
- Fix: configure `PoolingHttpClientConnectionManager` sized to the search
  parallelism.

### 12. Configured cache TTL is ignored; caches are unbounded
- `service/LastFmService.kt:57-62`, `service/SpotifySearchService.kt:77-78`, `service/LyricsService.kt:62-66`
- Guava caches hardcode `expireAfterWrite(1 hour)` while the `cache-ttl`
  properties (default 7 days) only stamp stored entries — with the persistent
  cache disabled (the default) the TTL knob is a no-op. No cache sets
  `maximumSize`, so entries can accumulate heap under load.
- Fix: derive in-memory expiry from the configured TTL and add
  `maximumSize`/`maximumWeight` bounds to every cache.

### 13. Firestore cache collections grow forever
- `persistence/FirestoreAppStateStores.kt:126-156`, `persistence/PersistenceDocuments.kt:230-331`
- Search-cache and recent-tracks documents carry `expiresAt` but nothing purges
  them; only `jobs` has a delete path.
- Fix: Firestore TTL policies on `expiresAt` (document in README), or scheduled
  `deleteExpired` for these stores. Use `WriteBatch`/`BulkWriter` instead of the
  sequential per-document deletes at `FirestoreAppStateStores.kt:38`.

### 14. Startup refresh blocks `ApplicationReadyEvent`
- `service/SpotifyTopPlaylistsRefreshService.kt:32-37`
- The full multi-request refresh (with retry sleeps) runs synchronously on the
  event thread, delaying readiness by up to minutes.
- Fix: hand the startup refresh to the scheduler/executor instead.

### 15. No health endpoints, graceful shutdown, or container JVM flags
- `build.gradle.kts` (no actuator), `application.properties`, `Dockerfile:21`
- Cloud Run has no readiness/liveness signal; SIGTERM kills in-flight jobs
  abruptly (they stay `RUNNING` forever); the JVM sizes its heap blind to the
  container limit.
- Fix: add `spring-boot-starter-actuator` (expose health/liveness/readiness),
  set `server.shutdown=graceful` + shutdown-phase timeout + scheduler
  `setWaitForTasksToCompleteOnShutdown(true)`, and run with
  `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`.

### 16. Sequential fan-outs dominating job latency
- `service/PrivateMoodTaxonomyService.kt:765-790` (~24 serial Last.fm calls for
  frontier seeds), `service/LyricsService.kt:320-364` (OpenAI batches submitted
  strictly sequentially while lyric fetching is already parallel)
- Fix: bounded `async`/`awaitAll` fan-out for both, mirroring the year scan.

---

## P2 — Security hardening

### 17. Session lifecycle: bearer cookie with no expiry or revocation
- `service/SpotifyAuthenticationService.kt:83-95`, `controller/SpotifyAuthenticationController.kt:137-140`, `controller/MainController.kt:54`
- The `clientId` cookie value is the sole credential; the backing token persists
  indefinitely and auto-refreshes on every `/` hit. No logout, no idle/absolute
  expiry, no rotation.
- Fix: server-side expiry + rotation, and a logout path that deletes the stored
  token.

### 18. No security response headers
- Whole web layer.
- Missing `X-Content-Type-Options`, frame-ancestors/CSP, `Referrer-Policy`,
  HSTS. The app embeds third-party iframes, so CSP/framing controls matter.
- Fix: small header filter or Spring Security defaults.

### 19. Outdated frontend libraries with known XSS CVEs
- `static/index.html:21,69,72,75`
- jQuery 3.4.1 (CVE-2020-11022/11023), Bootstrap 4.3.1, Popper 1.14.7. SRI is
  present but the pinned versions are the vulnerable ones.
- Fix: upgrade (jQuery ≥ 3.5, Bootstrap 5) or drop jQuery — the footprint is
  small enough for vanilla JS.

### 20. `Secure` cookie flag derived from spoofable header
- `controller/SpotifyAuthenticationController.kt:58-61`, `controller/LastFmAuthenticationController.kt:25-28`
- `X-Forwarded-Proto` is read directly; a client reaching the app not via the
  trusted proxy can get session cookies without `Secure`.
- Fix: rely on `server.forward-headers-strategy=native` + `request.isSecure`,
  or set `Secure` unconditionally in production.

### 21. Unauthenticated Last.fm login verification endpoint
- `controller/LastFmController.kt:23-30`
- `POST /verifyLastFmId/{login}` requires no session: username enumeration and
  outbound-traffic amplification against Last.fm.
- Fix: require an authorized session, validate the path variable, rate-limit.

### 22. Last.fm login (PII) logged raw at INFO
- `controller/LastFmController.kt:25-28`, `controller/JobsController.kt:59,93,131`, `controller/MainController.kt:32`, `service/LastFmAuthenticationService.kt:45,144`
- `clientId` is redacted via `LogSanitizer` but Last.fm logins are logged in
  cleartext on every request.
- Fix: apply the same sanitization or demote to DEBUG.

### 23. Add PKCE to the Spotify OAuth flow
- `controller/SpotifyAuthenticationController.kt:90-94,155-180`
- `state` handling is solid (SecureRandom, httpOnly cookie, validated on
  callback), but the code flow lacks PKCE, which Spotify supports.

---

## P3 — Architecture and code health

### 24. Decompose `PrivateMoodTaxonomyService` (1201 lines)
- Orchestration, stats aggregation, six candidate heuristics, lyric analysis
  with large inline weight tables, frontier construction, and Spotify matching
  live in one class with ~10 nested data classes.
- Fix: split into stats builder / candidate selector / lyrics analyzer / thin
  orchestrator; move weight tables to a resource. Makes heuristics testable.

### 25. Extract the duplicated year-scan pipeline
- `service/PrivateMoodTaxonomyService.kt:66-136,850-878` vs `service/SpotifyTopPlaylistsService.kt:48-208,270-306`
- Near-identical scaffolding: year fan-out with semaphore, lazy
  `existingPlaylists` map, `firstSupportedYear`/`getYear`, candidate →
  batch-search → match loops.
- Fix: shared "yearly scrobble scanner" and "candidate matcher" collaborators.

### 26. Unify retry logic
- `service/LastFmService.kt:140-187`, `service/LyricsService.kt:545-567`, `service/SpotifySearchService.kt:199-261`, plus `RetryTemplate` in `service/SpotifyRestService.kt:41-46`
- Three hand-rolled retry loops and one Spring Retry usage with subtly
  different backoff/retryable predicates.
- Fix: one shared retry helper (or standardize on Spring Retry everywhere).

### 27. Small code-health items
- Duplicate `sha256` (`LastFmService.kt:762`, `SpotifySearchService.kt:325`) and
  `normalizeToken` copies (`PlaylistSupport.kt:26` vs `LyricsService.kt:673`).
- `addTracksToPlaylist`/`replacePlaylistTracks` blindly prefix `spotify:track:`
  while `deleteTracksFromPlaylist` validates and accepts URIs — inconsistent
  contracts (`SpotifyPlaylistService.kt:128,161`).
- `JobService` hardcodes `Clock.systemUTC()` instead of injecting the existing
  `Clock` bean (`JobService.kt:29`).
- `PlaylistProvisioner.creationLocks` never evicts (`PlaylistSupport.kt:48`).
- `weightedLyricScore` uses `String.windowed` per phrase over full lyrics —
  large repeated allocations (`PrivateMoodTaxonomyService.kt:731-734`); count
  with `indexOf` instead.
- Cached recent-tracks pages stamp missing timestamps with the window end
  (`LastFmService.kt:735-748`), skewing night-play heuristics; exclude fallback
  timestamps from hour-histogram metrics.
- Spotify token document mixes snake_case and camelCase fields
  (`PersistenceDocuments.kt:114-126`).

---

## P4 — Infra, CI, and frontend polish

### 28. CI gaps
- `.github/workflows/ci.yml`
- No `docker build` validation (image only builds at deploy time), no static
  analysis (only ktfmt formatting), no Dependabot/Renovate for Gradle, Actions,
  and Docker base images.
- Fix: add a docker build step, detekt, and `.github/dependabot.yml`.

### 29. Dockerfile improvements
- `Dockerfile:5-8` — `COPY . .` before the Gradle build defeats dependency-layer
  caching; copy build scripts first and warm dependencies.
- `Dockerfile:12` — `tomcat-native` adds surface without clear benefit; confirm
  or drop.

### 30. Jacoco gate tuning
- `build.gradle.kts:84-96` — 90% LINE-only, global, no exclusions. Add a BRANCH
  counter and exclude config/DTO packages so the gate measures logic, not
  boilerplate.

### 31. Frontend polish
- `static/index.js:244-249` — 1 s polling with no backoff, cap, or
  `visibilitychange` pause.
- `static/index.js:41-46` — validate playlist IDs (`^[A-Za-z0-9]+$`) before
  building iframe URLs; add iframe `title` and input `<label>`s for a11y.
- `static/index.js:13-14` — `URL` constant shadows the global; keep one base-URL
  constant.

### 32. Config cleanup
- `build.gradle.kts:10` — unused Kotlin JPA plugin (no JPA anywhere).
- `application.properties:6` — `server.tomcat.max-keep-alive-requests=1`
  disables connection reuse behind Cloud Run's proxy; re-evaluate or document.

---

## What is already in good shape

- `clientId` redaction in logs (`LogSanitizer`), OAuth `state` validation on
  both callbacks, httpOnly session cookies, secrets from env vars only.
- Frontend avoids `innerHTML` (uses `textContent`/`.text()`), SRI on CDN assets.
- Spotify API chunking (100-track adds/deletes), pagination loops, and the
  `Retry-After` backoff math were checked and are correct.
- In-memory job expiry uses compare-and-remove correctly; persistent cache
  access is best-effort with fallbacks; Docker runs as non-root; CI caches
  Gradle and cancels superseded runs.
