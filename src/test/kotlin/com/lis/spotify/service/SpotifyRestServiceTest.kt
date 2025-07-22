package com.lis.spotify.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.retry.backoff.Sleeper
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

class SpotifyRestServiceTest {
  @Test
  fun serviceCanBeCreated() {
    val service = SpotifyRestService(RestTemplateBuilder(), mockk(relaxed = true))
    assertNotNull(service)
  }

  @Test
  fun doGetRetriesAndReturns() {
    val restTemplate = mockk<RestTemplate>()
    val builder = mockk<RestTemplateBuilder>()
    val auth = mockk<SpotifyAuthenticationService>()
    every { builder.requestFactory(HttpComponentsClientHttpRequestFactory::class.java) } returns
      builder
    every { builder.build() } returns restTemplate
    every { auth.getHeaders(any<String>()) } returns HttpHeaders()
    every {
      restTemplate.exchange<String>(
        any(),
        HttpMethod.GET,
        any(),
        any<ParameterizedTypeReference<String>>(),
        any<Map<String, *>>(),
      )
    } returns ResponseEntity("ok", HttpStatus.OK)

    val service = SpotifyRestService(builder, auth)
    val result = service.doGet<String>("http://test", clientId = "cid")
    assertEquals("ok", result)
  }

  @Test
  fun postAndDeleteReturnValues() {
    val restTemplate = mockk<RestTemplate>()
    val builder = mockk<RestTemplateBuilder>()
    val auth = mockk<SpotifyAuthenticationService>()
    every { builder.requestFactory(HttpComponentsClientHttpRequestFactory::class.java) } returns
      builder
    every { builder.build() } returns restTemplate
    every { auth.getHeaders(any<String>()) } returns HttpHeaders()
    every {
      restTemplate.exchange<String>(
        any(),
        HttpMethod.POST,
        any(),
        any<ParameterizedTypeReference<String>>(),
        any<Map<String, *>>(),
      )
    } returns ResponseEntity("ok", HttpStatus.OK)
    every {
      restTemplate.exchange<String>(
        any(),
        HttpMethod.DELETE,
        any(),
        any<ParameterizedTypeReference<String>>(),
        any<Map<String, *>>(),
      )
    } returns ResponseEntity("ok", HttpStatus.OK)

    val service = SpotifyRestService(builder, auth)
    val r1 = service.doPost<String>("http://test", clientId = "cid")
    val r2 = service.doDelete<String>("http://test", clientId = "cid")
    assertEquals("ok", r1)
    assertEquals("ok", r2)
  }

  @Test
  fun postReturnsUnitWhenBodyIsNull() {
    val restTemplate = mockk<RestTemplate>()
    val builder = mockk<RestTemplateBuilder>()
    val auth = mockk<SpotifyAuthenticationService>()
    every { builder.requestFactory(HttpComponentsClientHttpRequestFactory::class.java) } returns
      builder
    every { builder.build() } returns restTemplate
    every { auth.getHeaders(any<String>()) } returns HttpHeaders()
    every {
      restTemplate.exchange<Unit>(
        any(),
        HttpMethod.POST,
        any(),
        any<ParameterizedTypeReference<Unit>>(),
        any<Map<String, *>>(),
      )
    } returns ResponseEntity(null, HttpStatus.NO_CONTENT)

    val service = SpotifyRestService(builder, auth)
    val result = service.doPost<Unit>("http://test", clientId = "cid")
    assertEquals(Unit, result)
  }

  @Test
  fun retriesOnTooManyRequests() {
    val restTemplate = mockk<RestTemplate>()
    val builder = mockk<RestTemplateBuilder>()
    val auth = mockk<SpotifyAuthenticationService>()
    every { builder.requestFactory(HttpComponentsClientHttpRequestFactory::class.java) } returns
      builder
    every { builder.build() } returns restTemplate
    every { auth.getHeaders(any<String>()) } returns HttpHeaders()
    val ex =
      HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "",
        HttpHeaders().apply { set("Retry-After", "0") },
        ByteArray(0),
        null,
      )
    every {
      restTemplate.exchange<String>(
        any(),
        HttpMethod.GET,
        any(),
        any<ParameterizedTypeReference<String>>(),
        any<Map<String, *>>(),
      )
    } throws ex andThen ResponseEntity("ok", HttpStatus.OK)

    val service = SpotifyRestService(builder, auth)
    val result = service.doGet<String>("http://test", clientId = "cid")
    assertEquals("ok", result)
  }

  @Test
  fun usesRetryAfterHeaderAsBackoff() {
    val restTemplate = mockk<RestTemplate>()
    val builder = mockk<RestTemplateBuilder>()
    val auth = mockk<SpotifyAuthenticationService>()
    val sleeper = mockk<Sleeper>(relaxUnitFun = true)
    every { builder.requestFactory(HttpComponentsClientHttpRequestFactory::class.java) } returns
      builder
    every { builder.build() } returns restTemplate
    every { auth.getHeaders(any<String>()) } returns HttpHeaders()
    val ex =
      HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "",
        HttpHeaders().apply { set("Retry-After", "2") },
        ByteArray(0),
        null,
      )
    every {
      restTemplate.exchange<String>(
        any(),
        HttpMethod.GET,
        any(),
        any<ParameterizedTypeReference<String>>(),
        any<Map<String, *>>(),
      )
    } throws ex andThen ResponseEntity("ok", HttpStatus.OK)

    val service = SpotifyRestService(builder, auth, sleeper)
    val result = service.doGet<String>("http://test", clientId = "cid")
    assertEquals("ok", result)
    verify(exactly = 1) { sleeper.sleep(2000L) }
  }

  @Test
  fun unauthorizedThrowsAuthException() {
    val restTemplate = mockk<RestTemplate>()
    val builder = mockk<RestTemplateBuilder>()
    val auth = mockk<SpotifyAuthenticationService>()
    every { builder.requestFactory(HttpComponentsClientHttpRequestFactory::class.java) } returns
      builder
    every { builder.build() } returns restTemplate
    every { auth.getHeaders(any<String>()) } returns HttpHeaders()
    val ex =
      HttpClientErrorException.create(
        HttpStatus.UNAUTHORIZED,
        "",
        HttpHeaders(),
        ByteArray(0),
        null,
      )
    every {
      restTemplate.exchange<String>(
        any(),
        HttpMethod.GET,
        any(),
        any<ParameterizedTypeReference<String>>(),
        any<Map<String, *>>(),
      )
    } throws ex

    val service = SpotifyRestService(builder, auth)
    assertThrows(AuthenticationRequiredException::class.java) {
      service.doGet<String>("http://test", clientId = "cid")
    }
  }
}
