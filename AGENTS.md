This repository uses Gradle and Kotlin.

### Development guidelines
- Indent Kotlin code with 2 spaces.
- Use ktfmt for formatting: run `./gradlew ktfmtFormat` before committing.
- Run the test suite with `./gradlew test`.
- Run `./gradlew build` to ensure the project compiles.

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
- Provide a concise summary in the PR description.
