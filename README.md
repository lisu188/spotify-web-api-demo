# spotify-web-api-demo
https://spotify-web-api-demo.herokuapp.com/

This demo shows how to access the Spotify Web API using Kotlin and Spring Boot.

## Running locally

Start the service with:

```shell
./gradlew bootRun
```

## Running with Docker

Build the image:

```shell
docker build -t spotify-web-api-demo .
```

Run the container (replace the environment variables as needed):

```shell
docker run --rm -p 8080:8080 \
  -e BASE_URL="http://localhost:8080" \
  -e SPOTIFY_CLIENT_ID="your-id" \
  -e SPOTIFY_CLIENT_SECRET="your-secret" \
  -e LASTFM_API_KEY="your-key" \
  -e LASTFM_API_SECRET="your-secret" \
  spotify-web-api-demo
```

Verify the container is responding:

```shell
curl http://localhost:8080
```

This project targets Java 17 and uses the Gradle toolchain to provision it when
needed.

If Gradle fails to configure when running with newer Java versions, set
`JAVA_HOME` to a Java 17 install or update `org.gradle.java.home` in
`gradle.properties` to point at a Java 17 runtime.

The Java version is centralized in `gradle.properties` (`javaVersion`) and the
Docker image build uses the same version via the `JAVA_VERSION` build arg. Keep
these values aligned if you update Java versions.

Ensure the required environment variables are configured before running.

## Required environment variables

Set these values when deploying (e.g., to Cloud Run) so callback URLs and API
credentials are available at runtime:

- `BASE_URL` – Public URL of the running service (used as a fallback for
  callbacks)
- `SPOTIFY_CLIENT_ID` – Spotify application client ID
- `SPOTIFY_CLIENT_SECRET` – Spotify application client secret
- `LASTFM_API_KEY` – Last.fm API key
- `LASTFM_API_SECRET` – Last.fm API secret
- Last.fm endpoints default to HTTPS. Override `LASTFM_API_URL` and
  `LASTFM_AUTHORIZE_URL` only if custom values are required.

## Yearly playlists

Enter your Last.fm login on the main page and click **LAST.FM** to refresh yearly
playlists. The refresh runs in the background and logs progress to the
application console. To avoid overwhelming Last.fm, yearly charts are fetched
sequentially rather than all at once.

If you want to use Spotify-only features, click **Skip Last.fm** on the main
page. This disables Last.fm UI controls until you re-enable them.

## Band mix playlists

Use **BAND MIX** on the main page to generate a playlist from multiple band
names. Enter at least two bands separated by commas, then click **BAND MIX** to
create a playlist containing top tracks from each band.
