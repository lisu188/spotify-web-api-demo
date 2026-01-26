package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.ArtistSearchResult
import com.lis.spotify.domain.ArtistTopTracks
import com.lis.spotify.domain.Artists
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifyArtistServiceTest {
  @Test
  fun serviceInstantiates() {
    val service = SpotifyArtistService(mockk(relaxed = true))
    assertNotNull(service)
  }

  @Test
  fun searchArtistReturnsFirstMatch() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyArtistService(rest)
    val artist = Artist("1", "Band")
    every { rest.doRequest(any<() -> Any>()) } returns
      ArtistSearchResult(Artists(listOf(artist), null))

    val result = service.searchArtist("Band", "cid")
    assertEquals(artist, result)
  }

  @Test
  fun getArtistTopTracksReturnsTracks() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyArtistService(rest)
    val track = Track("t", "name", listOf(Artist("a", "band")), Album("b", "album", emptyList()))
    every { rest.doRequest(any<() -> Any>()) } returns ArtistTopTracks(listOf(track))

    val result = service.getArtistTopTracks("a", "cid")
    assertEquals(listOf(track), result)
  }
}
