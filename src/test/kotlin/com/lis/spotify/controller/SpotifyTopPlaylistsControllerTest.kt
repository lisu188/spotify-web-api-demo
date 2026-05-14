package com.lis.spotify.controller

import com.lis.spotify.service.LastFmService
import com.lis.spotify.service.SpotifyAuthenticationService
import com.lis.spotify.service.SpotifyTopPlaylistsService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class SpotifyTopPlaylistsControllerTest {
  private val lastFmService = mockk<LastFmService>(relaxed = true)
  private val topService = mockk<SpotifyTopPlaylistsService>()
  private val authService = mockk<SpotifyAuthenticationService>()
  private val controller = SpotifyTopPlaylistsController(lastFmService, topService, authService)

  init {
    every { authService.isAuthorizedSession("cid") } returns true
  }

  @Test
  fun updateTopPlaylistsRejectsUnauthorizedSession() {
    every { authService.isAuthorizedSession("forged") } returns false

    val ex =
      assertThrows(ResponseStatusException::class.java) { controller.updateTopPlaylists("forged") }

    assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
  }

  @Test
  fun updateTopPlaylistsDelegatesToService() {
    every { topService.updateTopPlaylists("cid") } returns listOf("1", "2")
    val result = controller.updateTopPlaylists("cid")
    assertEquals(listOf("1", "2"), result)
  }
}
