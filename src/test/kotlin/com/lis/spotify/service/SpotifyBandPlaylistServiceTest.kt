package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpotifyBandPlaylistServiceTest {
  private val artistService = mockk<SpotifyArtistService>()
  private val playlistService = mockk<SpotifyPlaylistService>()
  private val service = SpotifyBandPlaylistService(artistService, playlistService)

  @Test
  fun createBandPlaylistReturnsPlaylistId() {
    val bandA = Artist("a1", "Band A")
    val bandB = Artist("a2", "Band B")
    val trackA = Track("t1", "Song 1", listOf(bandA), Album("al1", "Album", listOf(bandA)))
    val trackB = Track("t2", "Song 2", listOf(bandB), Album("al2", "Album", listOf(bandB)))
    val playlist = Playlist("p1", "Band Mix: Band A, Band B")

    every { artistService.searchArtist("Band A", "cid") } returns bandA
    every { artistService.searchArtist("Band B", "cid") } returns bandB
    every { artistService.getArtistTopTracks("a1", "cid") } returns listOf(trackA)
    every { artistService.getArtistTopTracks("a2", "cid") } returns listOf(trackB)
    every { playlistService.getOrCreatePlaylist(any(), "cid") } returns playlist
    every { playlistService.modifyPlaylist("p1", any(), "cid") } returns emptyMap()

    val playlistId = service.createBandPlaylist("cid", listOf("Band A", "Band B"))

    assertEquals("p1", playlistId)
    verify {
      playlistService.modifyPlaylist("p1", match { it.containsAll(listOf("t1", "t2")) }, "cid")
    }
  }

  @Test
  fun createBandPlaylistReturnsNullForEmptyInput() {
    val playlistId = service.createBandPlaylist("cid", listOf(" ", ""))
    assertNull(playlistId)
    verify(exactly = 0) { playlistService.modifyPlaylist(any(), any(), any()) }
  }

  @Test
  fun createBandPlaylistReturnsNullWhenNoTracksFound() {
    every { artistService.searchArtist(any(), "cid") } returns null

    val playlistId = service.createBandPlaylist("cid", listOf("Band A", "Band B"))

    assertNull(playlistId)
    verify(exactly = 0) { playlistService.modifyPlaylist(any(), any(), any()) }
  }
}
