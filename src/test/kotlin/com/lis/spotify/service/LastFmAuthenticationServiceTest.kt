package com.lis.spotify.service

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate

class LastFmAuthenticationServiceTest {
  @Test
  fun getAuthorizationUrlFormatsCorrectly() {
    val service = LastFmAuthenticationService()
    val url = service.getAuthorizationUrl()
    assert(url.contains("api_key"))
  }

  @Test
  fun getSessionHandlesResponse() {
    val rest = mockk<RestTemplate>()
    val service = LastFmAuthenticationService()
    val field = LastFmAuthenticationService::class.java.getDeclaredField("restTemplate")
    field.isAccessible = true
    field.set(service, rest)
    val expected = mapOf<String, Any>("session" to mapOf("name" to "user", "key" to "val"))
    every {
      rest.postForEntity(
        any<String>(),
        any<HttpEntity<MultiValueMap<String, String>>>(),
        Map::class.java,
      )
    } returns ResponseEntity(expected, HttpStatus.OK)
    val result = service.getSession("token")
    assertEquals(expected, result)
    assertEquals("val", service.getSessionKey("user"))
  }
}
