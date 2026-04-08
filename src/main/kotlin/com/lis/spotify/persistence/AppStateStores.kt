package com.lis.spotify.persistence

interface JobStatusStore {
  fun save(job: StoredJobStatus): StoredJobStatus

  fun findById(jobId: String): StoredJobStatus?
}

interface SpotifyTokenStore {
  fun save(token: StoredSpotifyAuthToken): StoredSpotifyAuthToken

  fun findByClientId(clientId: String): StoredSpotifyAuthToken?
}

interface LastFmSessionStore {
  fun save(session: StoredLastFmSession): StoredLastFmSession

  fun findByLogin(login: String): StoredLastFmSession?

  fun findBySessionKey(sessionKey: String): StoredLastFmSession?
}

interface RefreshStateStore {
  fun saveTopPlaylists(state: StoredRefreshState): StoredRefreshState

  fun getTopPlaylists(): StoredRefreshState?
}

interface SpotifySearchCacheStore {
  fun save(entry: StoredSpotifySearchCacheEntry): StoredSpotifySearchCacheEntry

  fun findByKey(cacheKey: String): StoredSpotifySearchCacheEntry?
}

interface LastFmRecentTracksCacheStore {
  fun save(page: StoredLastFmRecentTracksPage): StoredLastFmRecentTracksPage

  fun findByKey(cacheKey: String): StoredLastFmRecentTracksPage?
}
