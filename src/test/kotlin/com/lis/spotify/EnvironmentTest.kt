package com.lis.spotify

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvironmentTest {
  @Test
  fun callbackUrlContainsBase() {
    val url = AppEnvironment.Spotify.CALLBACK_URL
    assertTrue(url.contains(AppEnvironment.BASE_URL))
  }
}
