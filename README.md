# spotify-web-api-demo
https://spotify-web-api-demo.herokuapp.com/

This demo shows how to access the Spotify Web API using Kotlin and Spring Boot.

## Running locally

Start the service with:

```shell
./gradlew bootRun
```

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
