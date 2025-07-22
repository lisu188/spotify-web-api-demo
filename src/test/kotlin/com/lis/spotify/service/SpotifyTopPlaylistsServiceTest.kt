package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifyTopPlaylistsServiceTest {
  @Test
  fun serviceInstantiates() {
    val service =
      SpotifyTopPlaylistsService(
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
      )
    assertNotNull(service)
  }

  @Test
  fun updateTopPlaylistsReturnsIds() {
    val playlistService = mockk<SpotifyPlaylistService>()
    val trackService = mockk<SpotifyTopTrackService>()
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()

    every { trackService.getTopTracksShortTerm(any()) } returns
      listOf(Track("1", "t", emptyList(), Album("a", "n", emptyList())))
    every { trackService.getTopTracksMidTerm(any()) } returns
      listOf(Track("2", "t", emptyList(), Album("a", "n", emptyList())))
    every { trackService.getTopTracksLongTerm(any()) } returns
      listOf(Track("3", "t", emptyList(), Album("a", "n", emptyList())))
    every { playlistService.getOrCreatePlaylist(any(), any()) } returns Playlist("id", "n")
    every { playlistService.modifyPlaylist(any(), any(), any()) } returns emptyMap()

    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)
    val ids = service.updateTopPlaylists("cid")
    assertEquals(4, ids.size)
    verify(exactly = 4) { playlistService.modifyPlaylist(any(), any(), any()) }
  }
}
