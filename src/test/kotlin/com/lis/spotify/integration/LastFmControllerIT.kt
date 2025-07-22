package com.lis.spotify.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.reset as wireMockReset
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
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
class LastFmControllerIT @Autowired constructor(private val rest: TestRestTemplate) {
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
  fun verifyLoginTrue() {
    stubFor(
      get(urlPathEqualTo("/2.0/"))
        .willReturn(
          okJson(
            """{"recenttracks":{"@attr":{"totalPages":"1"},"track":[{"artist":{"#text":"A"},"name":"B"}]}}"""
          )
        )
    )
    val resp = rest.postForEntity("/verifyLastFmId/login", null, Boolean::class.java)
    assertAll({ assertEquals(HttpStatus.OK, resp.statusCode) }, { assertEquals(true, resp.body) })
  }

  @Test
  fun verifyLoginFalse() {
    stubFor(
      get(urlPathEqualTo("/2.0/"))
        .willReturn(okJson("""{"recenttracks":{"@attr":{"totalPages":"1"},"track":[]}}"""))
    )
    val resp = rest.postForEntity("/verifyLastFmId/login", null, Boolean::class.java)
    assertAll({ assertEquals(HttpStatus.OK, resp.statusCode) }, { assertEquals(false, resp.body) })
  }

  @Test
  fun verifyLoginUnauthorized() {
    stubFor(
      get(urlPathEqualTo("/2.0/"))
        .willReturn(aResponse().withStatus(401).withBody("""{"error":17,"message":"Login"}"""))
    )
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }
    val resp = noRedirect.postForEntity("/verifyLastFmId/login", null, String::class.java)
    assertAll(
      { assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode) },
      { assertEquals("/auth/lastfm", resp.headers.location.toString()) },
    )
  }
}
