# spotify-web-api-demo

This project is a small Kotlin/Spring Boot application that demonstrates how to integrate the [Spotify Web API](https://developer.spotify.com/documentation/web-api/) together with Last.fm. The application exposes a simple web UI and a few REST/WebSocket endpoints for generating playlists based on your listening history.

The demo was originally deployed on Heroku but now runs on Google Cloud Run:
<https://spotify-web-api-demo-rea3tmjdpq-uc.a.run.app>

## Requirements

To run the application you need valid credentials for both Spotify and Last.fm. They are expected to be provided using environment variables:

- `BASE_URL` – public base URL of the running application (used for callback URLs)
- `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET`
- `LASTFM_API_KEY` and `LASTFM_API_SECRET`

## Running locally

1. Export the required environment variables.
2. Build and run the app using Gradle:

```bash
./gradlew bootRun
```

The server starts on port `8080` by default.

## Docker

A `Dockerfile` is provided. To build and run the image:

```bash
docker build -t spotify-web-api-demo .
docker run -p 8080:8080 -e BASE_URL=... -e SPOTIFY_CLIENT_ID=... -e SPOTIFY_CLIENT_SECRET=... -e LASTFM_API_KEY=... -e LASTFM_API_SECRET=... spotify-web-api-demo
```

## Tests

Unit tests can be executed with:

```bash
./gradlew test
```

Make sure the same environment variables are exported when running the tests.
For convenience you can prefix the command:

```bash
BASE_URL=http://localhost \
SPOTIFY_CLIENT_ID=dummy \
SPOTIFY_CLIENT_SECRET=dummy \
LASTFM_API_KEY=dummy \
LASTFM_API_SECRET=dummy \
./gradlew test
```

Note that the tests expect the same environment variables to be present.

