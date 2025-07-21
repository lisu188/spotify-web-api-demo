package com.lis.spotify.service

import com.lis.spotify.domain.AuthToken
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.web.client.RestTemplate

class SpotifyAuthenticationServiceTest {
  private val restTemplate = mockk<RestTemplate>()
  private val builder = mockk<RestTemplateBuilder>()
  private val service = SpotifyAuthenticationService(builder)

  init {
    every { builder.build() } returns restTemplate
  }

  @Test
  fun setAndGetAuthTokenWorks() {
    val token = AuthToken("a", "b", "c", 0, "refresh", "cid")
    service.setAuthToken(token)
    assertEquals(token, service.getAuthToken("cid"))
  }

  @Test
  fun isAuthorizedChecksCache() {
    assertFalse(service.isAuthorized("cid"))
    service.setAuthToken(AuthToken("a", "b", "c", 0, "r", "cid"))
    assertTrue(service.isAuthorized("cid"))
  }

  @Test
  fun refreshTokenStoresNewToken() {
    val builderAuthed = mockk<RestTemplateBuilder>()
    every { builder.basicAuthentication(any(), any()) } returns builderAuthed
    every { builderAuthed.build() } returns restTemplate
    val newToken = AuthToken("access", "Bearer", "", 0, "new", "cid")
    every { restTemplate.postForObject(any<URI>(), any(), AuthToken::class.java) } returns newToken
    service.setAuthToken(AuthToken("old", "", "", 0, "refresh", "cid"))
    service.refreshToken("cid")
    assertEquals(newToken, service.getAuthToken("cid"))
  }
}
