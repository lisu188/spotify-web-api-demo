package com.lis.spotify.service

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifyTopArtistServiceTest {
  @Test
  fun serviceInstantiates() {
    val service = SpotifyTopArtistService(mockk(relaxed = true))
    assertNotNull(service)
  }
}
