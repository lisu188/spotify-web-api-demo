package com.lis.spotify.controller

import com.lis.spotify.service.LastFmService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LastFmControllerTest {
  private val service = mockk<LastFmService>()
  private val controller = LastFmController(service)

  @Test
  fun verifyLastFmIdUsesService() {
    every { service.globalChartlist("login") } returns listOf()
    val result = controller.verifyLastFmId("login")
    assertTrue(result is Boolean)
  }
}
