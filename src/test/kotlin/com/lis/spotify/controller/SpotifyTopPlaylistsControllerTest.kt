package com.lis.spotify.controller

import com.lis.spotify.service.LastFmService
import com.lis.spotify.service.SpotifyTopPlaylistsService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpotifyTopPlaylistsControllerTest {
  private val lastFmService = mockk<LastFmService>(relaxed = true)
  private val topService = mockk<SpotifyTopPlaylistsService>()
  private val controller = SpotifyTopPlaylistsController(lastFmService, topService)

  @Test
  fun updateTopPlaylistsDelegatesToService() {
    every { topService.updateTopPlaylists("cid") } returns listOf("1", "2")
    val result = controller.updateTopPlaylists("cid")
    assertEquals(listOf("1", "2"), result)
  }
}
