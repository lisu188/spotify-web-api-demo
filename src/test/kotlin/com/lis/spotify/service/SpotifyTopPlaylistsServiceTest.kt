package com.lis.spotify.service

import io.mockk.mockk
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
}
