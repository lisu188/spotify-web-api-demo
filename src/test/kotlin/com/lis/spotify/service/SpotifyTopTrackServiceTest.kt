package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Track
import com.lis.spotify.domain.Tracks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifyTopTrackServiceTest {
  @Test
  fun serviceInstantiates() {
    val service = SpotifyTopTrackService(mockk(relaxed = true))
    assertNotNull(service)
  }

  @Test
  fun allTermsReturnTracks() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyTopTrackService(rest)
    val track = Track("1", "n", emptyList(), Album("a", "n", emptyList()))
    val result = Tracks(listOf(track))
    every { rest.doRequest(any<() -> Any>()) } returns result

    val short = service.getTopTracksShortTerm("c")
    val mid = service.getTopTracksMidTerm("c")
    val long = service.getTopTracksLongTerm("c")

    assertEquals(listOf(track), short)
    assertEquals(listOf(track), mid)
    assertEquals(listOf(track), long)
  }
}
