package com.lis.spotify.persistence

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.lis.spotify.domain.AuthToken
import com.lis.spotify.domain.JobState
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.Date
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersistenceDocumentsTest {
  @Test
  fun storedJobStatusMapsOptionalFieldsAndReloadsDocumentFallbacks() {
    val createdAt = Instant.parse("2026-04-08T10:00:00Z")
    val updatedAt = createdAt.plusSeconds(30)
    val expiresAt = createdAt.plusSeconds(3600)
    val stored =
      StoredJobStatus(
        jobId = "job-1",
        state = JobState.COMPLETED,
        progressPercent = 100,
        message = "Done",
        redirectUrl = "/auth",
        playlistIds = listOf("playlist-1", "playlist-2"),
        clientId = "cid",
        lastFmLogin = "login",
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt,
      )
    val map = stored.toFirestoreMap()
    val document = mockk<DocumentSnapshot>()

    every { document.exists() } returns true
    every { document.id } returns "job-from-id"
    every { document.getString("jobId") } returns null
    every { document.getString("state") } returns "COMPLETED"
    every { document.getLong("progressPercent") } returns 100L
    every { document.getString("message") } returns "Done"
    every { document.getString("redirectUrl") } returns "/auth"
    every { document.get("playlistIds") } returns listOf("playlist-1", 42, "playlist-2")
    every { document.getString("clientId") } returns "cid"
    every { document.getString("lastFmLogin") } returns "login"
    every { document.get("createdAt") } returns createdAt.toString()
    every { document.get("updatedAt") } returns Date.from(updatedAt)
    every { document.get("expiresAt") } returns expiresAt.toTimestamp()

    val reloaded = StoredJobStatus.fromDocument(document)

    assertEquals("job-1", map["jobId"])
    assertEquals("COMPLETED", map["state"])
    assertEquals(listOf("playlist-1", "playlist-2"), map["playlistIds"])
    assertEquals("job-from-id", reloaded?.jobId)
    assertEquals(listOf("playlist-1", "playlist-2"), reloaded?.playlistIds)
    assertEquals(createdAt, reloaded?.createdAt)
    assertEquals(updatedAt, reloaded?.updatedAt)
    assertEquals(expiresAt, reloaded?.expiresAt)
  }

  @Test
  fun invalidOrMissingDocumentsReturnNull() {
    val missing = mockk<DocumentSnapshot>()
    val invalidJob = mockk<DocumentSnapshot>()
    val sessionWithoutKey = mockk<DocumentSnapshot>()
    val searchCacheWithoutPayload = mockk<DocumentSnapshot>()

    every { missing.exists() } returns false
    every { invalidJob.exists() } returns true
    every { invalidJob.getString("jobId") } returns "job-1"
    every { invalidJob.getString("state") } returns "NOT_A_STATE"
    every { sessionWithoutKey.exists() } returns true
    every { sessionWithoutKey.getString("login") } returns "login"
    every { sessionWithoutKey.getString("sessionKey") } returns null
    every { searchCacheWithoutPayload.exists() } returns true
    every { searchCacheWithoutPayload.getString("cacheKey") } returns "cache"
    every { searchCacheWithoutPayload.getString("payloadJson") } returns null

    assertNull(StoredJobStatus.fromDocument(missing))
    assertNull(StoredJobStatus.fromDocument(invalidJob))
    assertNull(StoredLastFmSession.fromDocument(sessionWithoutKey))
    assertNull(StoredSpotifySearchCacheEntry.fromDocument(searchCacheWithoutPayload))
  }

  @Test
  fun spotifyAuthTokensConvertExpiryDefaultsAndRequiredClientId() {
    val now = Instant.parse("2026-04-08T10:00:00Z")
    val token =
      AuthToken(
        access_token = "access",
        token_type = "Bearer",
        scope = "scope",
        expires_in = 60,
        refresh_token = "refresh",
        clientId = "cid",
      )
    val stored = StoredSpotifyAuthToken.fromAuthToken(token, now)
    val expiredStored =
      StoredSpotifyAuthToken(
        clientId = "cid",
        accessToken = "access",
        refreshToken = null,
        tokenType = "",
        scope = "scope",
        expiresAt = now.minusSeconds(1),
        updatedAt = now,
      )

    val map = stored.toFirestoreMap()
    val rehydrated = stored.toAuthToken(now.plusSeconds(15))
    val expired = expiredStored.toAuthToken(now)

    assertEquals(now.plusSeconds(60), stored.expiresAt)
    assertEquals("refresh", map["refresh_token"])
    assertTrue(map.containsKey("expiresAt"))
    assertEquals(45, rehydrated.expires_in)
    assertEquals("Bearer", expired.token_type)
    assertEquals(0, expired.expires_in)
    assertThrows(IllegalArgumentException::class.java) {
      StoredSpotifyAuthToken.fromAuthToken(token.copy(clientId = null), now)
    }
  }

  @Test
  fun spotifyAuthTokenDocumentUsesDefaultsForMissingOptionalFields() {
    val document = mockk<DocumentSnapshot>()

    every { document.exists() } returns true
    every { document.id } returns "cid-from-id"
    every { document.getString("clientId") } returns null
    every { document.getString("access_token") } returns "access"
    every { document.getString("refresh_token") } returns null
    every { document.getString("token_type") } returns null
    every { document.getString("scope") } returns null
    every { document.get("expiresAt") } returns null
    every { document.get("updatedAt") } returns null

    val reloaded = StoredSpotifyAuthToken.fromDocument(document)

    assertEquals("cid-from-id", reloaded?.clientId)
    assertEquals("Bearer", reloaded?.tokenType)
    assertEquals("", reloaded?.scope)
    assertNull(reloaded?.expiresAt)
    assertEquals(Instant.EPOCH, reloaded?.updatedAt)
  }

  @Test
  fun cacheAndRefreshDocumentsBuildMapsAndReloadDefaults() {
    val now = Instant.parse("2026-04-08T10:00:00Z")
    val refreshMap =
      StoredRefreshState(
          clientId = null,
          lastStartedAt = null,
          lastCompletedAt = null,
          lastStatus = "RUNNING",
          lastPlaylistIds = emptyList(),
          updatedAt = now,
        )
        .toFirestoreMap()
    val lastFmPageMap =
      StoredLastFmRecentTracksPage(
          cacheKey = "recent",
          login = "login",
          from = 1L,
          to = 2L,
          page = 3,
          sessionKey = null,
          payloadJson = "{}",
          updatedAt = now,
          expiresAt = now.plusSeconds(600),
        )
        .toFirestoreMap()
    val spotifyCacheDocument = mockk<DocumentSnapshot>()
    val lastFmCacheDocument = mockk<DocumentSnapshot>()

    every { spotifyCacheDocument.exists() } returns true
    every { spotifyCacheDocument.id } returns "spotify-cache"
    every { spotifyCacheDocument.getString("cacheKey") } returns null
    every { spotifyCacheDocument.getString("payloadJson") } returns "{}"
    every { spotifyCacheDocument.getString("clientId") } returns null
    every { spotifyCacheDocument.getString("query") } returns null
    every { spotifyCacheDocument.get("updatedAt") } returns null
    every { spotifyCacheDocument.get("expiresAt") } returns null
    every { lastFmCacheDocument.exists() } returns true
    every { lastFmCacheDocument.id } returns "lastfm-cache"
    every { lastFmCacheDocument.getString("cacheKey") } returns null
    every { lastFmCacheDocument.getString("payloadJson") } returns "{}"
    every { lastFmCacheDocument.getString("login") } returns null
    every { lastFmCacheDocument.getLong("from") } returns null
    every { lastFmCacheDocument.getLong("to") } returns null
    every { lastFmCacheDocument.getLong("page") } returns null
    every { lastFmCacheDocument.getString("sessionKey") } returns null
    every { lastFmCacheDocument.get("updatedAt") } returns null
    every { lastFmCacheDocument.get("expiresAt") } returns null

    val spotifyCache = StoredSpotifySearchCacheEntry.fromDocument(spotifyCacheDocument)
    val lastFmCache = StoredLastFmRecentTracksPage.fromDocument(lastFmCacheDocument)

    assertFalse(refreshMap.containsKey("clientId"))
    assertFalse(refreshMap.containsKey("lastStartedAt"))
    assertFalse(refreshMap.containsKey("lastCompletedAt"))
    assertFalse(lastFmPageMap.containsKey("sessionKey"))
    assertEquals("spotify-cache", spotifyCache?.cacheKey)
    assertEquals(Instant.EPOCH, spotifyCache?.updatedAt)
    assertEquals(Instant.EPOCH, spotifyCache?.expiresAt)
    assertEquals("lastfm-cache", lastFmCache?.cacheKey)
    assertEquals(0L, lastFmCache?.from)
    assertEquals(1, lastFmCache?.page)
  }

  private fun Instant.toTimestamp(): Timestamp {
    return Timestamp.ofTimeSecondsAndNanos(epochSecond, nano)
  }
}
