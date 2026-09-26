# Replay - Spotify playlist studio

[Open the cloud application](https://spotify-web-api-demo-1040938023586.us-central1.run.app/).

Connect Spotify to create playlists from your favorites, Last.fm history, or a
mix of artists. The studio includes Spotify favorites, yearly Last.fm playlists,
Forgotten Obsessions, six private mood playlists, and Band Mix. The public home
page explains each tool before sign-in; account status, retries, and background
job progress are shown in the studio.

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
  -e BASE_URL="http://127.0.0.1:8080" \
  -e SPOTIFY_CLIENT_ID="your-id" \
  -e SPOTIFY_CLIENT_SECRET="your-secret" \
  -e LASTFM_API_KEY="your-key" \
  -e LASTFM_API_SECRET="your-secret" \
  spotify-web-api-demo
```

Verify the container is responding:

```shell
curl http://127.0.0.1:8080
```

This project targets Java 21 and uses the Gradle toolchain to provision it when
needed.

If Gradle fails to configure when running with newer Java versions, set
`JAVA_HOME` to a Java 21 install or update `org.gradle.java.home` in
`gradle.properties` to point at a Java 21 runtime.

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
- `LASTFM_LIBRARY_ALLOWED_USERS` – comma-separated Last.fm users whose public artist library may be read through the export endpoint
- `LYRICS_MOOD_PROVIDER` – Optional lyric mood classifier (`auto`, `heuristic`, or `openai`)
- `LYRICS_MOOD_OPENAI_API_KEY` – Optional OpenAI API key for lyric mood scoring
- `OPENAI_API_KEY` – Standard OpenAI API key env var, also picked up automatically
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
- `LASTFM_JOBS_MAX_PARALLELISM`
- `LASTFM_RECENT_TRACKS_MAX_PARALLELISM`
- `LASTFM_RECENT_TRACKS_PERSISTENT_CACHE_ENABLED`

For Cloud Run, the Last.fm job fan-out defaults are intentionally conservative:

- `LASTFM_JOBS_MAX_PARALLELISM=4`
- `LASTFM_RECENT_TRACKS_MAX_PARALLELISM=4`
- `LASTFM_RECENT_TRACKS_PERSISTENT_CACHE_ENABLED=false`

The recent-tracks Firestore cache is disabled by default to avoid large document
reads during full-history scans such as Private Mood Taxonomy. The app still
keeps an in-memory cache per instance.

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

## Last.fm library export

The service can expose paginated public Last.fm artist libraries without returning or logging the
configured Last.fm API key. Access is restricted to usernames listed in
`LASTFM_LIBRARY_ALLOWED_USERS`.

```shell
curl 'http://localhost:8080/api/lastfm/users/lisek188/artists?page=1&limit=200'
```

The response contains artist name, play count, optional MusicBrainz ID and Last.fm URL, together
with Last.fm pagination metadata. `limit` must be between 1 and 200. Users outside the configured
allowlist receive `404 Not Found`.

For analysis jobs that need the complete library in one request, use:

```shell
curl 'http://localhost:8080/api/lastfm/users/lisek188/library'
```

The full export is cached for one hour and includes `totalArtists` and `totalScrobbles` in
addition to every artist entry.

## Spotify taste export

An authenticated Spotify session can export the current user's top artists and tracks across all
three Spotify affinity windows in one request:

```shell
curl --cookie 'clientId=session_...' 'http://localhost:8080/api/spotify/taste?limit=50'
```

The response contains up to 50 top artists and 50 top tracks for `short_term` (about four weeks),
`medium_term` (about six months), and `long_term` (about one year). Artist genre tags are
included when Spotify provides them. The endpoint uses the existing `user-top-read` permission,
requires an authorized session, returns `Cache-Control: no-store`, and never exposes Spotify
access or refresh tokens.

## Workflow Notes

Enter your Last.fm login on the main page and click **LAST.FM** to refresh yearly
playlists. The refresh runs in the background and shows progress in the UI.
The current flow preserves the login across Last.fm auth redirects so the retry
path can continue after the user authorizes access.

Use **PRIVATE MOOD TAXONOMY** to build six private playlists from deterministic
Spotify + Last.fm listening heuristics, then rerank the mood playlists using
song lyrics fetched from LRCLIB:

- `Private Mood - Anchor`
- `Private Mood - Happy`
- `Private Mood - Sad`
- `Private Mood - Surge`
- `Private Mood - Night Drift`
- `Private Mood - Frontier`

The app uses listening history, Spotify top tracks, Last.fm similar
tracks/artists, and lyric scoring from LRCLIB. By default it uses the built-in
keyword heuristic. If `LYRICS_MOOD_PROVIDER=openai` or
`LYRICS_MOOD_PROVIDER=auto` with `LYRICS_MOOD_OPENAI_API_KEY` or
`OPENAI_API_KEY` set, it batches lyric classifications through OpenAI
`gpt-5.4-mini` using strict JSON schema output. The lyric lookup and OpenAI
call both use bounded retries for transient network, rate-limit, and `5xx`
failures. In `LYRICS_MOOD_PROVIDER=auto`, the existing heuristic scorer remains
the fallback if lyrics are unavailable or the model does not return a usable
classification. In `LYRICS_MOOD_PROVIDER=openai`, lyric validation is OpenAI
only: songs without a successful OpenAI classification remain unscored instead
of falling back to the heuristic scorer. The UI reuses the background job
polling flow and shows the resulting playlists as embedded Spotify iframes.

The underlying API accepts an optional playlist size when starting the job:

```shell
curl -X POST http://localhost:8080/jobs/private-mood-taxonomy \
  -H 'Content-Type: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' \
  -H 'Cookie: clientId=your-client-id' \
  -d '{"lastFmLogin":"your-lastfm-login","playlistSize":50}'
```

Use **BAND MIX** on the main page to generate a playlist from multiple band
names. Enter at least two bands separated by commas, then click **BAND MIX** to
create a playlist containing Spotify catalog matches from each band.
## Cloud deployment and verification

`cloudbuild.yaml` builds the Java 21 container and deploys it to the existing
Cloud Run service. Environment and secret updates are additive so unrelated
runtime settings remain intact. Public access is configured on the service;
the deploy does not change its IAM policy. The container listens on `PORT`
(default `8080`) and runs as a non-root user with a bounded JVM heap.

The deployment uses `/actuator/health/readiness` as its startup probe and
`/actuator/health/liveness` for subsequent checks. Only health is publicly
exposed through Actuator; environment values, dumps, and configuration endpoints
are unavailable. Health probes describe process availability, not a successful
Spotify or Last.fm operation.

To deploy an already reviewed and tested commit from the repository root:

```shell
./gradlew ktfmtCheck build
COMMIT_SHA=$(git rev-parse HEAD)
gcloud builds submit --project semiotic-mender-415520 \
  --config cloudbuild.yaml --substitutions COMMIT_SHA="$COMMIT_SHA" .
```

The existing service is configured for one instance. Background jobs use that
instance's executor and persisted status. They are not a durable task queue:
instance termination can interrupt a long history scan, and request-based CPU
allocation can pause background work while no requests arrive. Keep the studio
open while a job runs. Multi-instance or unattended processing needs a durable
worker/queue design before increasing the instance count.

Job expiry cleanup runs periodically rather than on every status poll. Firestore
cleanup rechecks expiry inside a transaction so a concurrently refreshed job is
preserved. Enable Firestore TTL on `expiresAt` for `jobs`, `spotifySearchCache`,
and `lastFmRecentTracksCache` as the retention backstop.

## Browser request and Spotify API compatibility

Cookie-authenticated write requests must include
`X-Requested-With: XMLHttpRequest`. Cross-origin writes are rejected; the
scheduler's separate `X-Refresh-Token` authentication remains available.
`GET /api/session` returns connection/configuration booleans without credentials.
`POST /api/logout` revokes the current Spotify session and clears browser account
cookies. HTTPS deployments issue Secure, HttpOnly, SameSite cookies.

Playlist requests use Spotify's `/playlists/{id}/items` API and accept both the
current `item` field and legacy `track` responses. Null, local, and episode
entries are excluded from music matching. Search requests respect the current
10-result page limit. Band Mix uses artist-filtered catalog search because
artist top tracks are no longer available to development-mode apps. See the
[Spotify February 2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

Regression tests use mocked upstream services and an in-memory state store.
They never modify real Spotify playlists. A full live run still requires the
user to connect Spotify, authorize Last.fm when prompted, and start a playlist
operation.

## Browser checks

Requires Node.js and pnpm. The browser suite runs all five workflows against
mock responses and checks connection failures, expired sessions, job failures,
keyboard controls, and narrow screens:

```shell
pnpm install --frozen-lockfile
pnpm exec playwright install chromium
pnpm test
```

Set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to use an installed Chrome/Chromium.
The suite writes desktop and mobile screenshots under `build/`.

Playlist updates add missing tracks before removing stale tracks, so an add
failure preserves the original contents and a later retry can reconcile them.
Automatic deduplication uses a single replacement and refuses to rewrite mixed
media or more than 100 unique tracks; those playlists remain unchanged rather
than risking a truncated result. Creation locks prevent concurrent submissions
from creating duplicate named playlists within the current single instance.

## Yearly generation concurrency

Yearly generation processes four years at a time and selects up to the latest
250 Last.fm scrobbles from each year, from 2005 through the current year. This
preserves the existing selection; it is not a full-year play-count ranking.

The spotify.search.max-parallelism setting (environment variable
SPOTIFY_SEARCH_MAX_PARALLELISM) defaults to **8**. It limits upstream search
requests across all years and jobs in this application instance. Previously it
defaulted to 64 and applied separately to each batch, while yearly matching was
sequential within each year. Cache hits do not consume network capacity, and
concurrent lookups for the same client and query share their result.

The lastfm.jobs.max-parallelism setting continues to default to **4**. The job
scheduler and cloud CPU, memory, and instance settings are unchanged. Search
limits and the shared Spotify rate-limit cooldown are process-local and assume
the existing single-instance deployment. Spotify HTTP connections use an explicit
pool of 16 with a five-second acquisition timeout. A 429 pauses new Spotify
requests until the latest observed Retry-After deadline, with bounded retries.

Matching preserves source order despite out-of-order responses. Cancellation
stops queued work and is checked before playlist operations; an already-issued
playlist request may still complete. Progress remains year-based, avoiding a
Firestore status write for every track.

The opt-in benchmark harness under benchmark/ compares actual pinned revisions
using synthetic provider latency and validates resulting playlists before
reporting timings. Its results do not measure live Spotify quotas, Firestore
latency, or Cloud Run background CPU availability.