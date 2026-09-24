package com.lis.spotify.integration

import com.lis.spotify.domain.AuthToken
import com.lis.spotify.persistence.SpotifyTokenStore
import com.lis.spotify.service.SpotifyAuthenticationService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionControllerIT
@Autowired
constructor(
  private val rest: TestRestTemplate,
  private val spotify: SpotifyAuthenticationService,
  private val store: SpotifyTokenStore,
) {
  @Test
  fun rootAndSessionStatusArePublicWithoutDisclosingCredentials() {
    val page = rest.getForEntity("/", String::class.java)
    assertEquals(HttpStatus.OK, page.statusCode)
    assertTrue(page.body.orEmpty().contains("<html"))
    assertEquals("nosniff", page.headers.getFirst("X-Content-Type-Options"))
    val status = rest.getForEntity("/api/session", Map::class.java)
    assertEquals(false, status.body?.get("authenticated"))
    assertEquals(
      setOf("authenticated", "spotifyConfigured", "lastFmAuthenticated", "lastFmConfigured"),
      status.body?.keys,
    )
    assertEquals("no-store", status.headers.cacheControl)
  }

  @Test
  fun publicSpotifyUserIdCannotAuthenticateOrVerifyLastFm() {
    spotify.setAuthToken(AuthToken("access", "Bearer", "scope", 3600, "refresh", "public-user-id"))
    val headers = ajaxHeaders("public-user-id")
    val status =
      rest.exchange("/api/session", HttpMethod.GET, HttpEntity<String>(headers), Map::class.java)
    assertEquals(false, status.body?.get("authenticated"))
    val verification =
      rest.postForEntity("/verifyLastFmId/profile", HttpEntity<String>(headers), String::class.java)
    assertEquals(HttpStatus.UNAUTHORIZED, verification.statusCode)
  }

  @Test
  fun logoutRequiresSameOriginRequestAndRevokesThePersistedSession() {
    val sessionId = spotify.createSessionId()
    spotify.setAuthToken(AuthToken("access", "Bearer", "scope", 3600, "refresh", sessionId))
    val headers = HttpHeaders().apply { set(HttpHeaders.COOKIE, "clientId=$sessionId") }
    assertEquals(
      HttpStatus.FORBIDDEN,
      rest.postForEntity("/api/logout", HttpEntity<String>(headers), String::class.java).statusCode,
    )
    assertTrue(spotify.isAuthorizedSession(sessionId))
    headers.set("X-Requested-With", "XMLHttpRequest")
    headers.set("Origin", "https://attacker.example")
    assertEquals(
      HttpStatus.FORBIDDEN,
      rest.postForEntity("/api/logout", HttpEntity<String>(headers), String::class.java).statusCode,
    )
    assertTrue(spotify.isAuthorizedSession(sessionId))
    headers.remove("Origin")
    val logout = rest.postForEntity("/api/logout", HttpEntity<String>(headers), String::class.java)
    assertEquals(HttpStatus.NO_CONTENT, logout.statusCode)
    assertEquals(
      5,
      logout.headers[HttpHeaders.SET_COOKIE].orEmpty().count {
        it.contains("Max-Age=0") && it.contains("HttpOnly")
      },
    )
    assertNull(store.findByClientId(sessionId))
    assertFalse(spotify.isAuthorizedSession(sessionId))
    spotify.clearCache()
    assertFalse(spotify.isAuthorizedSession(sessionId))
    val status =
      rest.exchange("/api/session", HttpMethod.GET, HttpEntity<String>(headers), Map::class.java)
    assertEquals(false, status.body?.get("authenticated"))
  }

  @Test
  fun rejectsInvalidLastFmUsernameBeforeExternalRequests() {
    val sessionId = spotify.createSessionId()
    spotify.setAuthToken(AuthToken("access", "Bearer", "scope", 3600, "refresh", sessionId))
    val result =
      rest.postForEntity(
        "/verifyLastFmId/invalid.name",
        HttpEntity<String>(ajaxHeaders(sessionId)),
        String::class.java,
      )
    assertEquals(HttpStatus.BAD_REQUEST, result.statusCode)
  }

  private fun ajaxHeaders(sessionId: String) =
    HttpHeaders().apply {
      set(HttpHeaders.COOKIE, "clientId=$sessionId")
      set("X-Requested-With", "XMLHttpRequest")
    }
}
