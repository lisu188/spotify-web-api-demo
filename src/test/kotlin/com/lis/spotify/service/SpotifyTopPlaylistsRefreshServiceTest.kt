package com.lis.spotify.service

import com.lis.spotify.persistence.InMemoryRefreshStateStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpotifyTopPlaylistsRefreshServiceTest {
  @Test
  fun refreshConfiguredPlaylistsUsesConfiguredRefreshToken() {
    val authService = mockk<SpotifyAuthenticationService>(relaxed = true)
    val topPlaylistsService = mockk<SpotifyTopPlaylistsService>()
    val refreshStateStore = InMemoryRefreshStateStore()
    val cacheClientId = slot<String>()
    every { authService.refreshToken(capture(cacheClientId)) } returns true
    every { topPlaylistsService.updateTopPlaylists(any()) } returns listOf("playlist-1")

    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = authService,
        spotifyTopPlaylistsService = topPlaylistsService,
        refreshStateStore = refreshStateStore,
        refreshEnabled = true,
        refreshClientId = "cid",
        refreshToken = "refresh-token",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    service.refreshConfiguredPlaylists("test")

    assertTrue(cacheClientId.captured.startsWith("configured-top-playlists-refresh-"))
    assertFalse(cacheClientId.captured == "cid")
    verify { authService.seedRefreshToken(cacheClientId.captured, "refresh-token") }
    verify(exactly = 0) { authService.seedRefreshToken("cid", any()) }
    verify { authService.refreshToken(cacheClientId.captured) }
    verify { topPlaylistsService.updateTopPlaylists(cacheClientId.captured) }
    val savedState = refreshStateStore.getTopPlaylists()
    assertNotNull(savedState)
    assertEquals("COMPLETED", savedState?.lastStatus)
    assertEquals(listOf("playlist-1"), savedState?.lastPlaylistIds)
    assertEquals("cid", savedState?.clientId)
    assertNotNull(savedState?.lastStartedAt)
    assertNotNull(savedState?.lastCompletedAt)
  }

  @Test
  fun refreshConfiguredPlaylistsSkipsWhenRefreshFails() {
    val authService = mockk<SpotifyAuthenticationService>(relaxed = true)
    val topPlaylistsService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val refreshStateStore = InMemoryRefreshStateStore()
    val cacheClientId = slot<String>()
    every { authService.refreshToken(capture(cacheClientId)) } returns false

    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = authService,
        spotifyTopPlaylistsService = topPlaylistsService,
        refreshStateStore = refreshStateStore,
        refreshEnabled = true,
        refreshClientId = "cid",
        refreshToken = "refresh-token",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    service.refreshConfiguredPlaylists("test")

    assertTrue(cacheClientId.captured.startsWith("configured-top-playlists-refresh-"))
    assertFalse(cacheClientId.captured == "cid")
    verify { authService.seedRefreshToken(cacheClientId.captured, "refresh-token") }
    verify(exactly = 0) { authService.seedRefreshToken("cid", any()) }
    verify { authService.refreshToken(cacheClientId.captured) }
    verify(exactly = 0) { topPlaylistsService.updateTopPlaylists(any()) }
    assertEquals("FAILED_SPOTIFY_REFRESH", refreshStateStore.getTopPlaylists()?.lastStatus)
  }

  @Test
  fun isTriggerAuthorizedMatchesConfiguredToken() {
    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = mockk(relaxed = true),
        spotifyTopPlaylistsService = mockk(relaxed = true),
        refreshStateStore = InMemoryRefreshStateStore(),
        refreshEnabled = true,
        refreshClientId = "cid",
        refreshToken = "refresh-token",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    assertTrue(service.isTriggerAuthorized("secret"))
    assertFalse(service.isTriggerAuthorized("wrong"))
  }

  @Test
  fun refreshConfiguredPlaylistsSkipsWhenCredentialsMissing() {
    val refreshStateStore = InMemoryRefreshStateStore()
    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = mockk(relaxed = true),
        spotifyTopPlaylistsService = mockk(relaxed = true),
        refreshStateStore = refreshStateStore,
        refreshEnabled = true,
        refreshClientId = "",
        refreshToken = "",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    val result = service.refreshConfiguredPlaylists("test")

    assertEquals(null, result)
    assertEquals("SKIPPED_MISSING_CREDENTIALS", refreshStateStore.getTopPlaylists()?.lastStatus)
  }

  @Test
  fun refreshConfiguredPlaylistsLeavesClientIdEmptyWhenCredentialsMissing() {
    val refreshStateStore = InMemoryRefreshStateStore()
    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = mockk(relaxed = true),
        spotifyTopPlaylistsService = mockk(relaxed = true),
        refreshStateStore = refreshStateStore,
        refreshEnabled = true,
        refreshClientId = "",
        refreshToken = "",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    service.refreshConfiguredPlaylists("test")

    assertNull(refreshStateStore.getTopPlaylists()?.clientId)
  }

  @Test
  fun refreshConfiguredPlaylistsMarksFailureWhenPlaylistUpdateThrows() {
    val authService = mockk<SpotifyAuthenticationService>(relaxed = true)
    val topPlaylistsService = mockk<SpotifyTopPlaylistsService>()
    val refreshStateStore = InMemoryRefreshStateStore()
    every { authService.refreshToken(any()) } returns true
    every { topPlaylistsService.updateTopPlaylists(any()) } throws IllegalStateException("boom")
    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = authService,
        spotifyTopPlaylistsService = topPlaylistsService,
        refreshStateStore = refreshStateStore,
        refreshEnabled = true,
        refreshClientId = "cid",
        refreshToken = "refresh-token",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    assertThrows<IllegalStateException> { service.refreshConfiguredPlaylists("test") }
    assertEquals("FAILED", refreshStateStore.getTopPlaylists()?.lastStatus)
  }
}
