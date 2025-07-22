This repository uses Gradle and Kotlin.

### Development guidelines
- Indent Kotlin code with 2 spaces.
- Use ktfmt for formatting: run `./gradlew ktfmtFormat` before committing.
- Run the test suite with `./gradlew test`.
- Run `./gradlew build` to ensure the project compiles.

### Logging
- Use SLF4J with a `private val logger` per class.
- Default log level is INFO.
- Use INFO for high-level events, DEBUG for details, WARN for recoverable problems, and ERROR for failures.

### Integration tests
Integration tests live under `src/test/kotlin/.../integration`. They should start
the application with `@SpringBootTest(webEnvironment = RANDOM_PORT)` and use
`TestRestTemplate` for HTTP requests. External HTTP services must be stubbed
using WireMock:

- Create a `WireMockServer` with a dynamic port in a `companion object` and
  expose its URL via `DynamicPropertySource` so the application uses the stub
  endpoints.
- Call `wireMockReset()` in `@BeforeEach` to clear stubs and stop the server in
  an `@AfterAll` method.

### Pull request guidelines
- Ensure all code is formatted and tests pass.
- Ensure at least 80% code coverage by running `./gradlew jacocoTestCoverageVerification`. Do not exclude any tests or lower the Jacoco coverage threshold. Try to add more tests to increase coverage.
- Provide a concise summary in the PR description.
