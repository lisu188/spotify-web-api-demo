package com.lis.spotify.persistence

import com.lis.spotify.domain.JobState
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryAppStateStoresTest {
  @Test
  fun jobStatusStoreDeletesExpiredJobsAndCanClearRemainingJobs() {
    val store = InMemoryJobStatusStore()
    val now = Instant.parse("2026-04-08T10:00:00Z")
    val expired = storedJob("expired", now.minusSeconds(1))
    val active = storedJob("active", now.plusSeconds(60))

    assertEquals(expired, store.save(expired))
    assertEquals(active, store.save(active))

    assertEquals(expired, store.findById("expired"))
    assertEquals(1, store.deleteExpired(now))
    assertNull(store.findById("expired"))
    assertEquals(active, store.findById("active"))

    store.clear()

    assertNull(store.findById("active"))
  }

  @Test
  fun tokenSessionAndRefreshStoresSaveFindAndClearValues() {
    val now = Instant.parse("2026-04-08T10:00:00Z")
    val tokenStore = InMemorySpotifyTokenStore()
    val token =
      StoredSpotifyAuthToken(
        clientId = "cid",
        accessToken = "access",
        refreshToken = "refresh",
        tokenType = "Bearer",
        scope = "playlist-modify-private",
        expiresAt = now.plusSeconds(3600),
        updatedAt = now,
      )
    val sessionStore = InMemoryLastFmSessionStore()
    val session = StoredLastFmSession(login = "login", sessionKey = "session", updatedAt = now)
    val refreshStore = InMemoryRefreshStateStore()
    val refreshState =
      StoredRefreshState(
        clientId = "cid",
        lastStartedAt = now.minusSeconds(60),
        lastCompletedAt = now,
        lastStatus = "COMPLETED",
        lastPlaylistIds = listOf("playlist-1"),
        updatedAt = now,
      )

    assertEquals(token, tokenStore.save(token))
    assertEquals(session, sessionStore.save(session))
    assertEquals(refreshState, refreshStore.saveTopPlaylists(refreshState))

    assertEquals(token, tokenStore.findByClientId("cid"))
    assertEquals(session, sessionStore.findByLogin("login"))
    assertEquals(session, sessionStore.findBySessionKey("session"))
    assertEquals(refreshState, refreshStore.getTopPlaylists())

    tokenStore.clear()
    sessionStore.clear()
    refreshStore.clear()

    assertNull(tokenStore.findByClientId("cid"))
    assertNull(sessionStore.findByLogin("login"))
    assertNull(sessionStore.findBySessionKey("session"))
    assertNull(refreshStore.getTopPlaylists())
  }

  @Test
  fun cacheStoresSaveFindAndClearValues() {
    val now = Instant.parse("2026-04-08T10:00:00Z")
    val spotifyCacheStore = InMemorySpotifySearchCacheStore()
    val spotifyCacheEntry =
      StoredSpotifySearchCacheEntry(
        cacheKey = "spotify-cache",
        clientId = "cid",
        query = "track:title artist:artist",
        payloadJson = "{\"tracks\":{\"items\":[]}}",
        updatedAt = now,
        expiresAt = now.plusSeconds(600),
      )
    val lastFmCacheStore = InMemoryLastFmRecentTracksCacheStore()
    val lastFmCachePage =
      StoredLastFmRecentTracksPage(
        cacheKey = "lastfm-cache",
        login = "login",
        from = 1L,
        to = 2L,
        page = 3,
        sessionKey = "session",
        payloadJson = "{\"totalPages\":1,\"songs\":[]}",
        updatedAt = now,
        expiresAt = now.plusSeconds(600),
      )

    assertEquals(spotifyCacheEntry, spotifyCacheStore.save(spotifyCacheEntry))
    assertEquals(lastFmCachePage, lastFmCacheStore.save(lastFmCachePage))
    assertEquals(spotifyCacheEntry, spotifyCacheStore.findByKey("spotify-cache"))
    assertEquals(lastFmCachePage, lastFmCacheStore.findByKey("lastfm-cache"))

    spotifyCacheStore.clear()
    lastFmCacheStore.clear()

    assertNull(spotifyCacheStore.findByKey("spotify-cache"))
    assertNull(lastFmCacheStore.findByKey("lastfm-cache"))
  }

  private fun storedJob(jobId: String, expiresAt: Instant): StoredJobStatus {
    val createdAt = Instant.parse("2026-04-08T09:00:00Z")
    return StoredJobStatus(
      jobId = jobId,
      state = JobState.RUNNING,
      progressPercent = 50,
      message = "Running",
      clientId = "cid",
      lastFmLogin = "login",
      createdAt = createdAt,
      updatedAt = createdAt.plusSeconds(30),
      expiresAt = expiresAt,
    )
  }
}
