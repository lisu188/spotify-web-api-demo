package com.lis.spotify.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpotifyTopPlaylistsRefreshServiceTest {
  @Test
  fun refreshConfiguredPlaylistsUsesConfiguredRefreshToken() {
    val authService = mockk<SpotifyAuthenticationService>(relaxed = true)
    val topPlaylistsService = mockk<SpotifyTopPlaylistsService>()
    every { authService.refreshToken("cid") } returns true
    every { topPlaylistsService.updateTopPlaylists("cid") } returns listOf("playlist-1")

    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = authService,
        spotifyTopPlaylistsService = topPlaylistsService,
        refreshEnabled = true,
        refreshClientId = "cid",
        refreshToken = "refresh-token",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    service.refreshConfiguredPlaylists("test")

    verify { authService.seedRefreshToken("cid", "refresh-token") }
    verify { authService.refreshToken("cid") }
    verify { topPlaylistsService.updateTopPlaylists("cid") }
  }

  @Test
  fun refreshConfiguredPlaylistsSkipsWhenRefreshFails() {
    val authService = mockk<SpotifyAuthenticationService>(relaxed = true)
    val topPlaylistsService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    every { authService.refreshToken("cid") } returns false

    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = authService,
        spotifyTopPlaylistsService = topPlaylistsService,
        refreshEnabled = true,
        refreshClientId = "cid",
        refreshToken = "refresh-token",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    service.refreshConfiguredPlaylists("test")

    verify { authService.seedRefreshToken("cid", "refresh-token") }
    verify { authService.refreshToken("cid") }
    verify(exactly = 0) { topPlaylistsService.updateTopPlaylists(any()) }
  }

  @Test
  fun isTriggerAuthorizedMatchesConfiguredToken() {
    val service =
      SpotifyTopPlaylistsRefreshService(
        spotifyAuthenticationService = mockk(relaxed = true),
        spotifyTopPlaylistsService = mockk(relaxed = true),
        refreshEnabled = true,
        refreshClientId = "cid",
        refreshToken = "refresh-token",
        refreshOnStartupEnabled = true,
        refreshTriggerToken = "secret",
      )

    assertTrue(service.isTriggerAuthorized("secret"))
    assertFalse(service.isTriggerAuthorized("wrong"))
  }
}
