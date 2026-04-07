package com.lis.spotify.controller

import com.lis.spotify.service.SpotifyTopPlaylistsRefreshService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class SpotifyTopPlaylistsRefreshControllerTest {
  @Test
  fun refreshConfiguredTopPlaylistsReturnsAcceptedWhenAuthorized() {
    val refreshService = mockk<SpotifyTopPlaylistsRefreshService>()
    every { refreshService.isTriggerAuthorized("secret") } returns true
    every { refreshService.refreshConfiguredPlaylists("http") } returns listOf("playlist-1")

    val controller = SpotifyTopPlaylistsRefreshController(refreshService)
    val response = controller.refreshConfiguredTopPlaylists("secret")

    assertEquals(HttpStatus.ACCEPTED, response.statusCode)
    assertEquals(listOf("playlist-1"), response.body)
  }

  @Test
  fun refreshConfiguredTopPlaylistsReturnsForbiddenWhenTokenIsInvalid() {
    val refreshService = mockk<SpotifyTopPlaylistsRefreshService>()
    every { refreshService.isTriggerAuthorized(any()) } returns false

    val controller = SpotifyTopPlaylistsRefreshController(refreshService)
    val response = controller.refreshConfiguredTopPlaylists("wrong")

    assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
  }
}
