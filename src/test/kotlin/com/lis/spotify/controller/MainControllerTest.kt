package com.lis.spotify.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainControllerTest {
  private val controller = MainController()

  @Test
  fun forwardsFaviconIcoToSvg() {
    assertEquals("forward:/favicon.svg", controller.favicon())
  }

  @Test
  fun servesTheAppWithoutForcingAuthenticationOrRefreshingTokens() {
    assertEquals("forward:/index.html", controller.main())
  }
}
