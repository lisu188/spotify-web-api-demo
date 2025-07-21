package com.lis.spotify.controller

import com.lis.spotify.service.LastFmAuthenticationService
import com.lis.spotify.service.SpotifyAuthenticationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainControllerTest {
  private val spotifyService = mockk<SpotifyAuthenticationService>(relaxed = true)
  private val lastfmService = mockk<LastFmAuthenticationService>(relaxed = true)
  private val controller = MainController(spotifyService, lastfmService)

  @Test
  fun redirectsToIndexWhenAuthorized() {
    every { spotifyService.getAuthToken("abc") } returns mockk()
    val result = controller.main("abc", "token")
    assertEquals("forward:/index.html", result)
    verify { spotifyService.refreshToken("abc") }
  }

  @Test
  fun redirectsToSpotifyWhenMissingToken() {
    every { spotifyService.getAuthToken("abc") } returns null
    val result = controller.main("abc", "token")
    assertEquals("redirect:/auth/spotify", result)
  }
}
