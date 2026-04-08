package com.lis.spotify.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.reset as wireMockReset
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.lis.spotify.persistence.InMemorySpotifyTokenStore
import com.lis.spotify.persistence.SpotifyTokenStore
import com.lis.spotify.service.SpotifyAuthenticationService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SpotifyAuthenticationControllerIT
@Autowired
constructor(
  private val rest: TestRestTemplate,
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
  private val spotifyTokenStore: SpotifyTokenStore,
) {
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
      registry.add("SPOTIFY_AUTH_URL") { "$base/s-auth" }
      registry.add("SPOTIFY_TOKEN_URL") { "$base/s-token" }
      registry.add("SPOTIFY_API_BASE_URL") { base }
      registry.add("LASTFM_API_KEY") { "key" }
      registry.add("LASTFM_API_SECRET") { "secret" }
      registry.add("LASTFM_API_URL") { "$base/2.0/" }
      registry.add("LASTFM_AUTHORIZE_URL") { "$base/auth" }
      System.setProperty("BASE_URL", "http://localhost")
      System.setProperty("SPOTIFY_CLIENT_ID", "id")
      System.setProperty("SPOTIFY_CLIENT_SECRET", "secret")
      System.setProperty("SPOTIFY_AUTH_URL", "$base/s-auth")
      System.setProperty("SPOTIFY_TOKEN_URL", "$base/s-token")
      System.setProperty("SPOTIFY_API_BASE_URL", base)
      System.setProperty("LASTFM_API_KEY", "key")
      System.setProperty("LASTFM_API_SECRET", "secret")
      System.setProperty("LASTFM_API_URL", "$base/2.0/")
      System.setProperty("LASTFM_AUTHORIZE_URL", "$base/auth")
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
    spotifyAuthenticationService.clearCache()
    (spotifyTokenStore as? InMemorySpotifyTokenStore)?.clear()
  }

  @Test
  fun authorizeRedirectsToSpotifyAndSetsStateCookie() {
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }

    val response = noRedirect.getForEntity("/auth/spotify", String::class.java)
    val stateCookie =
      response.headers["Set-Cookie"].orEmpty().firstOrNull { it.contains("spotifyAuthState=") }

    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertTrue(response.headers.location!!.toString().startsWith(baseUrl)) },
      { assertNotNull(stateCookie) },
    )
  }

  @Test
  fun callbackStoresAuthTokenAndClientCookie() {
    stubFor(
      post(urlPathEqualTo("/s-token"))
        .withRequestBody(containing("grant_type=authorization_code"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(
              """
              {
                "access_token":"access",
                "token_type":"Bearer",
                "scope":"user-top-read playlist-modify-public",
                "expires_in":3600,
                "refresh_token":"refresh"
              }
              """
                .trimIndent()
            )
        )
    )
    stubFor(
      get(urlPathEqualTo("/v1/me"))
        .withHeader("Authorization", equalTo("Bearer access"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""{"id":"cid"}""")
        )
    )

    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }
    val authorize = noRedirect.getForEntity("/auth/spotify", String::class.java)
    val stateCookie =
      authorize.headers["Set-Cookie"].orEmpty().first { it.contains("spotifyAuthState=") }
    val state = stateCookie.substringAfter("spotifyAuthState=").substringBefore(";")
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "spotifyAuthState=$state")

    val response =
      noRedirect.exchange(
        "/auth/spotify/callback?code=good-code&state=$state",
        HttpMethod.GET,
        HttpEntity<String>(headers),
        String::class.java,
      )
    val cookies = response.headers["Set-Cookie"].orEmpty()

    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertTrue(response.headers.location!!.toString().endsWith("/")) },
      { assertTrue(cookies.any { it.contains("clientId=cid") }) },
      { assertTrue(cookies.any { it.contains("spotifyAuthState=") && it.contains("Max-Age=0") }) },
      { assertNotNull(spotifyAuthenticationService.getAuthToken("cid")) },
    )
  }

  @Test
  fun callbackRejectsInvalidState() {
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "spotifyAuthState=expected")

    val response =
      noRedirect.exchange(
        "/auth/spotify/callback?code=bad-code&state=wrong",
        HttpMethod.GET,
        HttpEntity<String>(headers),
        String::class.java,
      )

    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertTrue(response.headers.location!!.toString().endsWith("/error")) },
      { assertNull(spotifyAuthenticationService.getAuthToken("cid")) },
    )
  }
}
