package com.lis.spotify.service

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifySearchServiceTest {
  @Test
  fun serviceInstantiates() {
    val service = SpotifySearchService(mockk(relaxed = true))
    assertNotNull(service)
  }
}
