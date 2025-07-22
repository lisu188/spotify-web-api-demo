package com.lis.spotify.service

import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.Artists
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifyTopArtistServiceTest {
  @Test
  fun serviceInstantiates() {
    val service = SpotifyTopArtistService(mockk(relaxed = true))
    assertNotNull(service)
  }

  @Test
  fun allTermsReturnArtists() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyTopArtistService(rest)
    val artist = Artist("1", "n")
    val result = Artists(listOf(artist), null)
    every { rest.doRequest(any<() -> Any>()) } returns result

    val short = service.getTopArtistsShortTerm("c")
    val mid = service.getTopArtistsMidTerm("c")
    val long = service.getTopArtistsLongTerm("c")

    assertEquals(listOf(artist), short)
    assertEquals(listOf(artist), mid)
    assertEquals(listOf(artist), long)
  }
}
