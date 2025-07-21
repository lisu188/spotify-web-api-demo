package com.lis.spotify.controller

import com.lis.spotify.domain.AuthToken
import com.lis.spotify.service.SpotifyAuthenticationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.web.client.RestTemplate

class SpotifyAuthenticationControllerTest {
  private val spotifyService = mockk<SpotifyAuthenticationService>(relaxed = true)
  private val restTemplate = mockk<RestTemplate>()
  private val builder = mockk<RestTemplateBuilder>()
  private val controller = SpotifyAuthenticationController(spotifyService, builder)

  @Test
  fun getCurrentUserIdHandlesError() {
    every { builder.build() } returns restTemplate
    val token = AuthToken("a", "b", "c", 0, null, "cid")
    every { spotifyService.getHeaders(token) } returns org.springframework.http.HttpHeaders()
    every { restTemplate.exchange<Any>(any<String>(), any(), any(), Any::class.java) } throws
      RuntimeException()
    val id = controller.getCurrentUserId(token)
    assertNull(id)
  }

  @Test
  fun authorizeReturnsRedirect() {
    val result = controller.authorize(mockk(), mockk(), "cid")
    assert(result.startsWith("redirect:"))
  }
}
