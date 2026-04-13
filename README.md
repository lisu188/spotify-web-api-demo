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

Spotify authorization now requests these scopes:

- `user-top-read`
- `playlist-modify-public`
- `playlist-modify-private`
- `playlist-read-private`

The private playlist scopes are required for the Private Mood Taxonomy flow so
the app can create private playlists and find them again on later refreshes.

## Google Cloud

Run the service as a single public Cloud Run service. Keep the browser UI and
OAuth callbacks public, and protect `POST /refreshConfiguredTopPlaylists` with
`X-Refresh-Token` so Cloud Scheduler can call it safely.

Use Application Default Credentials everywhere. Do not ship a service account
key file. In Cloud Run, set the runtime service account to one that can access
Firestore and Secret Manager. Locally, use `gcloud auth application-default login`
or a Firestore emulator.

Recommended runtime inputs:

- `BASE_URL`
- `SPOTIFY_CLIENT_ID`
- `SPOTIFY_CLIENT_SECRET`
- `LASTFM_API_KEY`
- `LASTFM_API_SECRET`
- `SPOTIFY_TOP_PLAYLISTS_REFRESH_ENABLED`
- `SPOTIFY_TOP_PLAYLISTS_REFRESH_CLIENT_ID`
- `SPOTIFY_TOP_PLAYLISTS_REFRESH_TOKEN`
- `SPOTIFY_TOP_PLAYLISTS_REFRESH_ON_STARTUP`
- `SPOTIFY_TOP_PLAYLISTS_REFRESH_INTERVAL_MS`
- `SPOTIFY_TOP_PLAYLISTS_REFRESH_TRIGGER_TOKEN`

Cloud Scheduler should invoke `POST /refreshConfiguredTopPlaylists` with
`X-Refresh-Token` set to the configured trigger token. Keep the existing startup
and scheduled refresh behavior in the app as best-effort, but treat Scheduler as
the reliable periodic trigger.

Artifact Registry image naming replaces Container Registry. The Cloud Build image
should follow the form:

`us-central1-docker.pkg.dev/$PROJECT_ID/spotify-web-api-demo/spotify-web-api-demo:$COMMIT_SHA`

Store secrets in Secret Manager and inject them at deploy time or runtime. Keep
Spotify, Last.fm, and refresh trigger credentials out of the repository.

Suggested deployment flow:

1. Create a Firestore Native mode database in the target project.
2. Create Secret Manager secrets for Spotify credentials, Last.fm credentials,
   and the refresh trigger token.
3. Grant the Cloud Run runtime service account access to Firestore and Secret
   Manager.
4. Deploy the public Cloud Run service with `BASE_URL` set to the final HTTPS
   URL and the secrets injected as environment variables.
5. Configure Cloud Scheduler to call `POST /refreshConfiguredTopPlaylists` with
   the `X-Refresh-Token` header.

## Firestore

Firestore Native mode is the source of truth for app state. The app uses these
collections:

- `jobs/{jobId}`
- `spotifyAuthTokens/{clientId}`
- `lastFmSessions/{login}`
- `refreshState/topPlaylists`

Document expectations:

- `jobs/{jobId}` should store `jobId`, `state`, `progressPercent`, `message`,
  `redirectUrl`, `clientId`, `lastFmLogin`, `createdAt`, `updatedAt`, and
  `expiresAt`
- `spotifyAuthTokens/{clientId}` should store `clientId`, `access_token`,
  `refresh_token`, `token_type`, `scope`, `expiresAt`, and `updatedAt`
- `lastFmSessions/{login}` should store `login`, `sessionKey`, and `updatedAt`
- `refreshState/topPlaylists` should store `clientId`, `lastStartedAt`,
  `lastCompletedAt`, `lastStatus`, `lastPlaylistIds`, and `updatedAt`

Enable TTL on `jobs.expiresAt` in Google Cloud after deployment. Use `Instant`
for persisted timestamps and let the app map them back to the existing HTTP
response models.

For local development, either point the app at a Firestore emulator or use ADC
against a project that has Firestore Native mode enabled. The emulator is
optional but useful for persistence testing without touching production data.
When using the emulator, set `FIRESTORE_EMULATOR_HOST` and a matching
`GOOGLE_CLOUD_PROJECT` before starting the app.

For local ADC against a real project:

```shell
gcloud auth application-default login
export GOOGLE_CLOUD_PROJECT="your-project-id"
./gradlew bootRun
```

For the optional Firestore emulator:

```shell
export FIRESTORE_EMULATOR_HOST="127.0.0.1:8081"
export GOOGLE_CLOUD_PROJECT="spotify-web-api-demo-dev"
./gradlew bootRun
```

## Refresh Settings

The configured refresh flow is controlled by these Spring Boot properties, which
can also be supplied as uppercase underscore environment variables:

- `spotify.top-playlists.refresh-enabled`
- `spotify.top-playlists.refresh-client-id`
- `spotify.top-playlists.refresh-token`
- `spotify.top-playlists.refresh-on-startup`
- `spotify.top-playlists.refresh-interval-ms`
- `spotify.top-playlists.refresh-trigger-token`

These settings keep the configured refresh path working for both startup and
scheduled refreshes while still allowing manual Cloud Scheduler execution.

## Workflow Notes

Enter your Last.fm login on the main page and click **LAST.FM** to refresh yearly
playlists. The refresh runs in the background and shows progress in the UI.
The current flow preserves the login across Last.fm auth redirects so the retry
path can continue after the user authorizes access.

Use **PRIVATE MOOD TAXONOMY** to build four private playlists from deterministic
Spotify + Last.fm listening heuristics:

- `Private Mood - Anchor`
- `Private Mood - Surge`
- `Private Mood - Night Drift`
- `Private Mood - Frontier`

The app uses only listening history, Spotify top tracks, and Last.fm similar
tracks/artists. It does not use Last.fm tags, Spotify recommendations, audio
features, audio analysis, or any trained model. The UI reuses the background
job polling flow and shows the resulting playlists as embedded Spotify iframes.

The underlying API accepts an optional playlist size when starting the job:

```shell
curl -X POST http://localhost:8080/jobs/private-mood-taxonomy \
  -H 'Content-Type: application/json' \
  -H 'Cookie: clientId=your-client-id' \
  -d '{"lastFmLogin":"your-lastfm-login","playlistSize":50}'
```

Use **BAND MIX** on the main page to generate a playlist from multiple band
names. Enter at least two bands separated by commas, then click **BAND MIX** to
create a playlist containing top tracks from each band.
