package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.PlaylistTrack
import com.lis.spotify.domain.PlaylistTracks
import com.lis.spotify.domain.Playlists
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpotifyPlaylistServiceTest {
  private val rest = mockk<SpotifyRestService>(relaxed = true)
  private val service = SpotifyPlaylistService(rest)

  @Test
  fun getDiffReturnsRemovedElements() {
    val method =
      SpotifyPlaylistService::class
        .java
        .getDeclaredMethod("getDiff", List::class.java, List::class.java)
    method.isAccessible = true
    val result = method.invoke(service, listOf("a", "b"), listOf("b")) as List<*>
    assertEquals(listOf("a"), result)
  }

  @Test
  fun playlistTrackIdsAreReturned() {
    val tracks =
      PlaylistTracks(
        listOf(PlaylistTrack(Track("1", "t", emptyList(), Album("a", "n", emptyList())))),
        null,
      )
    every { rest.doRequest(any<() -> Any>()) } returns tracks
    val ids = service.getPlaylistTrackIds("id", clientId = "cid")
    assertEquals(listOf("1"), ids)
  }

  @Test
  fun getOrCreateReturnsExisting() {
    val playlists = Playlists(listOf(Playlist("p1", "name")), null)
    every { rest.doRequest(any<() -> Any>()) } returns playlists
    val result = service.getOrCreatePlaylist("name", "cid")
    assertEquals("p1", result.id)
  }
}
