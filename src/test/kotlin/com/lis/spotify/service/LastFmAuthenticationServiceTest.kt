package com.lis.spotify.service

import com.lis.spotify.AppEnvironment.LastFm
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

class LastFmAuthenticationServiceTest {
  @Test
  fun getAuthorizationUrlFormatsCorrectly() {
    val service = LastFmAuthenticationService()
    val url = service.getAuthorizationUrl()
    assert(url.contains("api_key"))
  }

  @Test
  fun callbackParameterIsEncoded() {
    val service = LastFmAuthenticationService()
    val url = service.getAuthorizationUrl()
    val uri = URI(url)
    val params = UriComponentsBuilder.fromUri(uri).build().queryParams
    val cb = params.getFirst("cb")
    val decoded = java.net.URLDecoder.decode(cb, StandardCharsets.UTF_8)
    assertEquals(LastFm.CALLBACK_URL, decoded)
    assert(cb != LastFm.CALLBACK_URL)
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

  @Test
  fun isAuthorizedChecksCache() {
    val service = LastFmAuthenticationService()
    service.setSession("user", "val")
    assertEquals(true, service.isAuthorized("val"))
    assertEquals(false, service.isAuthorized("other"))
  }

  @Test
  fun expiredSessionIsRemoved() {
    val service = LastFmAuthenticationService()
    // Use a timestamp far in the past so the entry is expired
    service.sessionCache["user"] = Pair("val", System.currentTimeMillis() - 10 * 60 * 1000L)

    assertEquals(false, service.isAuthorized("val"))
    assertEquals(null, service.getSessionKey("user"))
  }
}
