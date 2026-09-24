package com.lis.spotify.service

import com.lis.spotify.domain.AuthToken
import com.lis.spotify.persistence.InMemorySpotifyTokenStore
import com.lis.spotify.persistence.SpotifyTokenStore
import com.lis.spotify.persistence.StoredSpotifyAuthToken
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.web.client.RestTemplate

class SpotifyAuthenticationServiceTest {
  private val restTemplate = mockk<RestTemplate>()
  private val builder = mockk<RestTemplateBuilder>()
  private val store = InMemorySpotifyTokenStore()
  private val service = SpotifyAuthenticationService(builder, store)

  init {
    every { builder.connectTimeout(any()) } returns builder
    every { builder.readTimeout(any()) } returns builder
    every { builder.build() } returns restTemplate
  }

  @Test
  fun setAndGetAuthTokenWorks() {
    val token = AuthToken("a", "b", "c", 0, "refresh", "cid")
    service.setAuthToken(token)
    assertEquals(token, service.getAuthToken("cid"))
  }

  @Test
  fun isAuthorizedChecksCache() {
    assertFalse(service.isAuthorized("cid"))
    service.setAuthToken(AuthToken("a", "b", "c", 0, "r", "cid"))
    assertTrue(service.isAuthorized("cid"))
  }

  @Test
  fun isAuthorizedSessionRequiresOpaqueSessionId() {
    val sessionId = service.createSessionId()
    service.setAuthToken(AuthToken("a", "b", "c", 0, "r", sessionId))
    service.setAuthToken(AuthToken("victim-token", "b", "c", 0, "r", "victim-public-id"))

    assertTrue(service.isSessionId(sessionId))
    assertTrue(service.isAuthorizedSession(sessionId))
    assertFalse(service.isSessionId("victim-public-id"))
    assertFalse(service.isAuthorizedSession("victim-public-id"))
  }

  @Test
  fun seedRefreshTokenStoresRefreshTokenInCache() {
    service.seedRefreshToken("cid", "refresh")

    val token = service.getAuthToken("cid")
    assertEquals("refresh", token?.refresh_token)
    assertEquals("cid", token?.clientId)
  }

  @Test
  fun persistedTokenCanBeReadByNewServiceInstance() {
    val token = AuthToken("a", "Bearer", "scope", 120, "refresh", "cid")
    service.setAuthToken(token)

    val restartedService = SpotifyAuthenticationService(builder, store)

    assertTrue(restartedService.isAuthorized("cid"))
    assertEquals("refresh", restartedService.getAuthToken("cid")?.refresh_token)
  }

  @Test
  fun refreshTokenStoresRotatedRefreshToken() {
    val builderAuthed = mockk<RestTemplateBuilder>()
    every { builder.basicAuthentication(any(), any()) } returns builderAuthed
    every { builderAuthed.build() } returns restTemplate
    // Spotify rotated the refresh token; the response carries a new one that must be stored.
    val newToken = AuthToken("access", "Bearer", "", 0, "new", "cid")
    every { restTemplate.postForObject(any<URI>(), any(), AuthToken::class.java) } returns newToken
    service.setAuthToken(AuthToken("old", "", "", 0, "refresh", "cid"))
    val refreshed = service.refreshToken("cid")
    assertTrue(refreshed)
    val stored = service.getAuthToken("cid")
    assertEquals("access", stored?.access_token)
    assertEquals("new", stored?.refresh_token)
    assertEquals("cid", stored?.clientId)
  }

  @Test
  fun refreshTokenPreservesExistingRefreshTokenWhenResponseOmitsIt() {
    val builderAuthed = mockk<RestTemplateBuilder>()
    every { builder.basicAuthentication(any(), any()) } returns builderAuthed
    every { builderAuthed.build() } returns restTemplate
    // Response without a rotated refresh token: the existing one must be preserved.
    val newToken = AuthToken("access2", "Bearer", "", 0, null, "cid")
    every { restTemplate.postForObject(any<URI>(), any(), AuthToken::class.java) } returns newToken
    service.setAuthToken(AuthToken("old", "", "", 0, "refresh", "cid"))
    val refreshed = service.refreshToken("cid")
    assertTrue(refreshed)
    val stored = service.getAuthToken("cid")
    assertEquals("access2", stored?.access_token)
    assertEquals("refresh", stored?.refresh_token)
  }

  @Test
  fun getHeadersMissingTokenReturnsEmpty() {
    val headers = service.getHeaders("unknown")
    assertTrue(headers.isEmpty())
  }

  @Test
  fun refreshTokenWithoutRefreshTokenDoesNothing() {
    val token = AuthToken("a", "b", "c", 0, null, "cid")
    service.setAuthToken(token)
    val refreshed = service.refreshToken("cid")
    assertFalse(refreshed)
    assertEquals(token, service.getAuthToken("cid"))
  }

  @Test
  fun logoutCannotBeUndoneByAnInFlightTokenCacheLoad() {
    val sessionId = "session_concurrentLogout"
    store.save(
      StoredSpotifyAuthToken.fromAuthToken(
        AuthToken("access", "Bearer", "scope", 3600, "refresh", sessionId),
        Instant.now(),
      )
    )
    val readStarted = CountDownLatch(1)
    val finishRead = CountDownLatch(1)
    val logoutStarted = CountDownLatch(1)
    val logoutCompleted = CountDownLatch(1)
    val firstRead = AtomicBoolean(true)
    val blockingStore =
      object : SpotifyTokenStore by store {
        override fun findByClientId(clientId: String): StoredSpotifyAuthToken? {
          val snapshot = store.findByClientId(clientId)
          if (firstRead.compareAndSet(true, false)) {
            readStarted.countDown()
            assertTrue(finishRead.await(5, TimeUnit.SECONDS))
          }
          return snapshot
        }
      }
    val concurrentService = SpotifyAuthenticationService(builder, blockingStore)
    Executors.newFixedThreadPool(2).use { executor ->
      val reading = executor.submit<AuthToken?> { concurrentService.getAuthToken(sessionId) }
      assertTrue(readStarted.await(5, TimeUnit.SECONDS))
      val revoking =
        executor.submit {
          logoutStarted.countDown()
          concurrentService.revokeSession(sessionId)
          logoutCompleted.countDown()
        }
      try {
        assertTrue(logoutStarted.await(5, TimeUnit.SECONDS))
        assertFalse(logoutCompleted.await(100, TimeUnit.MILLISECONDS))
      } finally {
        finishRead.countDown()
      }
      reading.get(5, TimeUnit.SECONDS)
      revoking.get(5, TimeUnit.SECONDS)
    }
    assertFalse(concurrentService.isAuthorizedSession(sessionId))
    assertNull(store.findByClientId(sessionId))
  }

  @Test
  fun concurrentReadWriteDoesNotThrow() = runBlocking {
    coroutineScope {
      repeat(50) {
        launch(Dispatchers.Default) {
          val id = "c$it"
          val token = AuthToken("a$id", "type", "", 0, "r", id)
          service.setAuthToken(token)
          service.getAuthToken(id)
        }
      }
    }
  }
}
