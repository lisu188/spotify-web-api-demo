package com.lis.spotify.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.reset as wireMockReset
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LastFmAuthenticationControllerIT @Autowired constructor(private val rest: TestRestTemplate) {
  companion object {
    val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
    val baseUrl: String
      get() = "http://localhost:${wm.port()}"

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      wm.start()
      configureFor("localhost", wm.port())
      val base = baseUrl
      registry.add("BASE_URL") { "http://localhost" }
      registry.add("SPOTIFY_CLIENT_ID") { "id" }
      registry.add("SPOTIFY_CLIENT_SECRET") { "secret" }
      registry.add("LASTFM_API_KEY") { "key" }
      registry.add("LASTFM_API_SECRET") { "secret" }
      registry.add("LASTFM_API_URL") { "$base/2.0/" }
      registry.add("LASTFM_AUTHORIZE_URL") { "$base/auth" }
      registry.add("SPOTIFY_AUTH_URL") { "$base/s-auth" }
      registry.add("SPOTIFY_TOKEN_URL") { "$base/s-token" }
      System.setProperty("BASE_URL", "http://localhost")
      System.setProperty("SPOTIFY_CLIENT_ID", "id")
      System.setProperty("SPOTIFY_CLIENT_SECRET", "secret")
      System.setProperty("LASTFM_API_KEY", "key")
      System.setProperty("LASTFM_API_SECRET", "secret")
      System.setProperty("LASTFM_API_URL", "$base/2.0/")
      System.setProperty("LASTFM_AUTHORIZE_URL", "$base/auth")
      System.setProperty("SPOTIFY_AUTH_URL", "$base/s-auth")
      System.setProperty("SPOTIFY_TOKEN_URL", "$base/s-token")
    }

    @JvmStatic
    @AfterAll
    fun stop() {
      wm.stop()
    }
  }

  @BeforeEach
  fun resetStubs() {
    wireMockReset()
  }

  @Test
  fun authenticateUserRedirects() {
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }
    val response =
      noRedirect.getForEntity("/auth/lastfm?lastFmLogin=saved-login", String::class.java)
    val cookies = response.headers["Set-Cookie"].orEmpty()
    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertTrue(response.headers.location!!.toString().startsWith(baseUrl)) },
      { assertTrue(cookies.any { it.contains("lastFmLogin=saved-login") }) },
      { assertTrue(cookies.any { it.contains("lastFmAuthState=") }) },
    )
  }

  @Test
  fun authenticateUserIgnoresForwardedHostForCallback() {
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }
    val headers = org.springframework.http.HttpHeaders()
    headers.add("X-Forwarded-Proto", "https")
    headers.add("X-Forwarded-Host", "attacker.example")
    headers.add("X-Forwarded-Port", "443")

    val response =
      noRedirect.exchange(
        "/auth/lastfm",
        org.springframework.http.HttpMethod.GET,
        org.springframework.http.HttpEntity<String>(headers),
        String::class.java,
      )
    val location = response.headers.location!!.toString()
    val decodedLocation = URLDecoder.decode(location, StandardCharsets.UTF_8)

    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertFalse(decodedLocation.contains("attacker.example")) },
      { assertTrue(decodedLocation.contains("cb=http://localhost/auth/lastfm/callback")) },
      { assertTrue(decodedLocation.contains("state=")) },
    )
  }

  @Test
  fun callbackSetsCookie() {
    stubFor(
      post(urlPathEqualTo("/2.0/"))
        .willReturn(okJson("""{"session":{"key":"val","name":"login"}}"""))
    )
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }
    val headers = org.springframework.http.HttpHeaders()
    headers.add(org.springframework.http.HttpHeaders.COOKIE, "lastFmAuthState=state")
    val response =
      noRedirect.exchange(
        "/auth/lastfm/callback?token=t&state=state",
        org.springframework.http.HttpMethod.GET,
        org.springframework.http.HttpEntity<String>(headers),
        String::class.java,
      )
    val cookies = response.headers["Set-Cookie"].orEmpty()
    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertTrue(cookies.any { it.contains("lastFmToken=val") }) },
      { assertTrue(cookies.any { it.contains("lastFmLogin=login") }) },
      { assertTrue(response.headers.location!!.toString().endsWith("/")) },
    )
  }

  @Test
  fun callbackUsesSavedLoginWhenSessionNameMissing() {
    stubFor(post(urlPathEqualTo("/2.0/")).willReturn(okJson("""{"session":{"key":"val"}}""")))
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }
    val headers = org.springframework.http.HttpHeaders()
    headers.add(
      org.springframework.http.HttpHeaders.COOKIE,
      "lastFmLogin=remembered; lastFmAuthState=state",
    )

    val response =
      noRedirect.exchange(
        "/auth/lastfm/callback?token=t&state=state",
        org.springframework.http.HttpMethod.GET,
        org.springframework.http.HttpEntity<String>(headers),
        String::class.java,
      )
    val cookies = response.headers["Set-Cookie"].orEmpty()

    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertTrue(cookies.any { it.contains("lastFmToken=val") }) },
      { assertTrue(cookies.any { it.contains("lastFmLogin=remembered") }) },
    )
  }

  @Test
  fun callbackMissingTokenRedirectsToError() {
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }

    val response = noRedirect.getForEntity("/auth/lastfm/callback", String::class.java)

    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertTrue(response.headers.location!!.toString().endsWith("/error")) },
    )
  }
}
