package com.lis.spotify.service

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifyTopTrackServiceTest {
  @Test
  fun serviceInstantiates() {
    val service = SpotifyTopTrackService(mockk(relaxed = true))
    assertNotNull(service)
  }
}
