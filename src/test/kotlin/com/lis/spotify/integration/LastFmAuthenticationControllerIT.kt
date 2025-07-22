package com.lis.spotify.integration

import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.reset as wireMockReset
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class LastFmAuthenticationControllerIT @Autowired constructor(private val rest: TestRestTemplate) {
  companion object {
    val wm =
      GenericContainer(DockerImageName.parse("wiremock/wiremock:3.5.2-alpine"))
        .withExposedPorts(8080)
    val baseUrl: String
      get() = "http://localhost:${wm.getMappedPort(8080)}"

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      System.setProperty("BASE_URL", "http://localhost")
      System.setProperty("SPOTIFY_CLIENT_ID", "id")
      System.setProperty("SPOTIFY_CLIENT_SECRET", "secret")
      System.setProperty("LASTFM_API_KEY", "key")
      System.setProperty("LASTFM_API_SECRET", "secret")
      wm.start()
      configureFor("localhost", wm.getMappedPort(8080))
      val base = baseUrl
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
    val response = rest.getForEntity("/auth/lastfm", String::class.java)
    assertAll(
      { assertEquals(HttpStatus.FOUND, response.statusCode) },
      { assertTrue(response.headers.location!!.toString().startsWith(baseUrl)) },
    )
  }

  @Test
  fun callbackSetsCookie() {
    stubFor(post(urlPathEqualTo("/2.0/")).willReturn(okJson("""{"session":{"key":"val"}}""")))
    val response = rest.getForEntity("/auth/lastfm/callback?token=t", String::class.java)
    val cookie = response.headers["Set-Cookie"]!!.first()
    assertAll(
      { assertEquals(HttpStatus.OK, response.statusCode) },
      { assertTrue(cookie.contains("lastFmToken=val")) },
      { assertTrue(response.body!!.startsWith("redirect:/")) },
    )
  }
}
