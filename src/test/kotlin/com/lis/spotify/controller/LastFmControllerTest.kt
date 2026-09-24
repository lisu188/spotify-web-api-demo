package com.lis.spotify.controller

import com.lis.spotify.service.LastFmService
import com.lis.spotify.service.SpotifyAuthenticationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastFmControllerTest {
  private val service = mockk<LastFmService>()
  private val spotify = mockk<SpotifyAuthenticationService>()
  private val controller = LastFmController(service, spotify)

  @Test
  fun verifyLastFmIdUsesService() {
    every { service.userExists("login") } returns true
    every { spotify.isAuthorizedSession("session_test") } returns true
    val result = controller.verifyLastFmId("login", "session_test")
    assertTrue(result)
  }
}
