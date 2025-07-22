package com.lis.spotify

/**
 * AppEnvironment centralizes all configuration needed for Spotify and Last.fm integrations. It
 * extracts a common BASE_URL (used for assembling callback URLs) and organizes each service’s
 * configuration in its own object, using SPOTIFY_ and LASTFM_ prefixes for environment variables.
 */
object AppEnvironment {
  // Base URL used to assemble callback URLs for both Spotify and Last.fm.
  // CHANGE START
  val BASE_URL: String
    get() =
      System.getenv("BASE_URL")
        ?: System.getProperty("BASE_URL")
        ?: throw IllegalArgumentException("BASE_URL environment variable is missing")

  object Spotify {
    val CLIENT_ID: String
      get() =
        System.getenv("SPOTIFY_CLIENT_ID")
          ?: System.getProperty("SPOTIFY_CLIENT_ID")
          ?: throw IllegalArgumentException("SPOTIFY_CLIENT_ID environment variable is missing")

    val CLIENT_SECRET: String
      get() =
        System.getenv("SPOTIFY_CLIENT_SECRET")
          ?: System.getProperty("SPOTIFY_CLIENT_SECRET")
          ?: throw IllegalArgumentException("SPOTIFY_CLIENT_SECRET environment variable is missing")

    // Define the callback path and combine it with BASE_URL.
    const val CALLBACK_PATH: String = "/auth/spotify/callback"
    val CALLBACK_URL: String
      get() = "$BASE_URL$CALLBACK_PATH"

    // Spotify endpoints and scopes.
    val AUTH_URL: String
      get() =
        System.getenv("SPOTIFY_AUTH_URL")
          ?: System.getProperty("SPOTIFY_AUTH_URL")
          ?: "https://accounts.spotify.com/authorize"

    val TOKEN_URL: String
      get() =
        System.getenv("SPOTIFY_TOKEN_URL")
          ?: System.getProperty("SPOTIFY_TOKEN_URL")
          ?: "https://accounts.spotify.com/api/token"

    const val SCOPES: String = "user-top-read playlist-modify-public"
  }

  object LastFm {
    val API_KEY: String
      get() =
        System.getenv("LASTFM_API_KEY")
          ?: System.getProperty("LASTFM_API_KEY")
          ?: throw IllegalArgumentException("LASTFM_API_KEY environment variable is missing")

    val API_SECRET: String
      get() =
        System.getenv("LASTFM_API_SECRET")
          ?: System.getProperty("LASTFM_API_SECRET")
          ?: throw IllegalArgumentException("LASTFM_API_SECRET environment variable is missing")

    // Define the callback path and combine it with BASE_URL.
    const val CALLBACK_PATH: String = "/auth/lastfm/callback"
    val CALLBACK_URL: String
      get() = "$BASE_URL$CALLBACK_PATH"

    // Last.fm endpoints.
    val AUTHORIZE_URL: String
      get() =
        System.getenv("LASTFM_AUTHORIZE_URL")
          ?: System.getProperty("LASTFM_AUTHORIZE_URL")
          ?: "https://www.last.fm/api/auth/"

    val API_URL: String
      get() =
        System.getenv("LASTFM_API_URL")
          ?: System.getProperty("LASTFM_API_URL")
          ?: "https://ws.audioscrobbler.com/2.0/"
  }
}
// CHANGE END
