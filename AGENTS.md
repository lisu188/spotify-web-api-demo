# Repository Guidelines

## Project Structure & Module Organization
This Gradle-based Kotlin/Spring Boot service keeps production sources in `src/main/kotlin` and views/static assets in `src/main/resources`. Tests live in `src/test/kotlin`, with HTTP-focused suites under `.../integration`. Docker, Cloud Build, and infra templates sit at the repo root, while `build.gradle.kts`, `settings.gradle`, and `gradle.properties` track dependencies, Java toolchains, and shared configuration.

## Build, Test, and Development Commands
- `./gradlew ktfmtFormat` — format Kotlin sources using the enforced style.
- `./gradlew build` — compile, run all tests, and assemble production artifacts.
- `./gradlew test` — execute unit and integration tests locally.
- `./gradlew jacocoTestCoverageVerification` — ensure ≥80% coverage before merging.
- `./gradlew bootRun` — start the API with dev settings.
- `docker build -t spotify-web-api-demo .` / `docker run --rm -p 8080:8080 spotify-web-api-demo` — package and run the image; verify via `curl http://localhost:8080`.

## Coding Style & Naming Conventions
Indent Kotlin code with two spaces, keep companion objects at the bottom of their class, and prefer expressive, camelCase identifiers (`LastFmClient`, `refreshYearlyCharts`). Each class should expose `private val logger = LoggerFactory.getLogger(...)` and use INFO for lifecycle events, DEBUG for details, WARN for recoverable errors, and ERROR for failures. Run ktfmt before committing.

## Testing Guidelines
JUnit 5 and Spring Boot Test power the suite. Place slice or integration tests in `src/test/kotlin/.../integration`, boot the app via `@SpringBootTest(webEnvironment = RANDOM_PORT)`, and interact through `TestRestTemplate`. Stub external APIs with WireMock using a dynamically bound port published through `@DynamicPropertySource`, call `wireMockReset()` in `@BeforeEach`, and stop the server in `@AfterAll`. Test names should describe behavior (`shouldReturnTopTracksForArtist`). Always hit the Jacoco verification target and add coverage when touching new logic.

## Commit & Pull Request Guidelines
Work on topic branches (e.g., `feature/add-band-mix-filter`), keep commits scoped and written in the imperative mood (“Add Last.fm auth fallback”), and rebase onto `main` before pushing. Pull requests must summarize what changed, why, and how it was validated (tests, ktfmt, docker run). Link tracking issues when available and mention remaining limitations or follow-ups. Never merge with failing checks or less than required coverage.

## Configuration & Security Tips
`BASE_URL`, `SPOTIFY_CLIENT_ID/SECRET`, and Last.fm keys must be present before running locally or in Docker; use `.envrc` or your shell profile to export them. Keep Java 17 aligned across `gradle.properties` and the Dockerfile `JAVA_VERSION` build arg. Treat credentials as secrets—do not hardcode them in tests or logs.
