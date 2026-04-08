package com.lis.spotify.service

import com.lis.spotify.AppEnvironment
import com.lis.spotify.persistence.InMemoryLastFmSessionStore
import io.mockk.every
import io.mockk.mockk
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val service = LastFmAuthenticationService(InMemoryLastFmSessionStore())
    val url = service.getAuthorizationUrl()
    assert(url.contains("api_key"))
    val encoded = URLEncoder.encode(AppEnvironment.LastFm.CALLBACK_URL, "UTF-8")
    assert(url.contains("cb=$encoded"))
  }

  @Test
  fun getSessionHandlesResponse() {
    val rest = mockk<RestTemplate>()
    val service = LastFmAuthenticationService(InMemoryLastFmSessionStore())
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
    val service = LastFmAuthenticationService(InMemoryLastFmSessionStore())
    service.setSession("user", "val")
    assertEquals(true, service.isAuthorized("val"))
    assertEquals(true, service.isAuthorized("user", "val"))
    assertEquals(false, service.isAuthorized("other-user", "val"))
    assertEquals(false, service.isAuthorized("other"))
  }

  @Test
  fun sessionDoesNotExpirePrematurely() {
    val service = LastFmAuthenticationService(InMemoryLastFmSessionStore())
    service.setSession("user", "val")

    assertEquals(true, service.isAuthorized("val"))
    assertEquals("val", service.getSessionKey("user"))
  }

  @Test
  fun persistedSessionCanBeReadByNewServiceInstance() {
    val store = InMemoryLastFmSessionStore()
    LastFmAuthenticationService(store).setSession("user", "val")

    val restartedService = LastFmAuthenticationService(store)

    assertEquals(true, restartedService.isAuthorized("val"))
    assertEquals(true, restartedService.isAuthorized("user", "val"))
    assertEquals("val", restartedService.getSessionKey("user"))
  }

  @Test
  fun concurrentReadWriteDoesNotThrow() = runBlocking {
    val service = LastFmAuthenticationService(InMemoryLastFmSessionStore())
    coroutineScope {
      repeat(50) {
        launch(Dispatchers.Default) {
          val login = "u$it"
          val session = "s$it"
          service.setSession(login, session)
          service.isAuthorized(session)
          service.getSessionKey(login)
        }
      }
    }
  }
}
