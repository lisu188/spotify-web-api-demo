package com.lis.spotify.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.reset as wireMockReset
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.lis.spotify.domain.AuthToken
import com.lis.spotify.service.LastFmLibraryExport
import com.lis.spotify.service.LastFmLibraryPage
import com.lis.spotify.service.LastFmMonthSummary
import com.lis.spotify.service.SpotifyAuthenticationService
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
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LastFmControllerIT
@Autowired
constructor(private val rest: TestRestTemplate, private val spotify: SpotifyAuthenticationService) {
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
      registry.add("lastfm.library.allowed-users") { "login" }
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
    spotify.setAuthToken(AuthToken("access", "Bearer", "scope", 3600, "refresh", "session_verify"))
  }

  @Test
  fun libraryExportReturnsAllPages() {
    listOf(1, 2).forEach { page ->
      stubFor(
        get(urlPathEqualTo("/2.0/"))
          .withQueryParam("method", equalTo("library.getArtists"))
          .withQueryParam("api_key", equalTo("key"))
          .withQueryParam("user", equalTo("login"))
          .withQueryParam("page", equalTo(page.toString()))
          .withQueryParam("limit", equalTo("200"))
          .willReturn(
            okJson(
              """
              {
                "artists": {
                  "@attr": {
                    "page": "$page",
                    "perPage": "200",
                    "totalPages": "2",
                    "total": "2"
                  },
                  "artist": [
                    {
                      "name": "Artist $page",
                      "playcount": "${page * 10}",
                      "mbid": "",
                      "url": "https://www.last.fm/music/Artist+$page"
                    }
                  ]
                }
              }
              """
                .trimIndent()
            )
          )
      )
    }

    val resp = rest.getForEntity("/api/lastfm/users/login/library", LastFmLibraryExport::class.java)

    assertAll(
      { assertEquals(HttpStatus.OK, resp.statusCode) },
      { assertEquals(2L, resp.body?.totalArtists) },
      { assertEquals(30L, resp.body?.totalScrobbles) },
      { assertEquals(listOf("Artist 1", "Artist 2"), resp.body?.artists?.map { it.name }) },
    )
  }

  @Test
  fun monthlyHistoryReturnsRequestedMonths() {
    listOf(
        Triple("1767225600", "1769903999", "January Artist"),
        Triple("1769904000", "1772323199", "February Artist"),
      )
      .forEachIndexed { index, (from, to, artist) ->
        stubFor(
          get(urlPathEqualTo("/2.0/"))
            .withQueryParam("method", equalTo("user.getWeeklyArtistChart"))
            .withQueryParam("api_key", equalTo("key"))
            .withQueryParam("user", equalTo("login"))
            .withQueryParam("from", equalTo(from))
            .withQueryParam("to", equalTo(to))
            .withQueryParam("limit", equalTo("1000"))
            .willReturn(
              okJson(
                """
                {
                  "weeklyartistchart": {
                    "artist": [
                      {"name": "$artist", "playcount": "${(index + 1) * 10}"}
                    ]
                  }
                }
                """
                  .trimIndent()
              )
            )
        )
      }

    val resp =
      rest.getForEntity(
        "/api/lastfm/users/login/monthly-history?from=2026-01&to=2026-02&limit=50",
        Array<LastFmMonthSummary>::class.java,
      )

    assertAll(
      { assertEquals(HttpStatus.OK, resp.statusCode) },
      { assertEquals(2, resp.body?.size) },
      { assertEquals("January Artist", resp.body?.get(0)?.topArtists?.single()?.name) },
      { assertEquals(20L, resp.body?.get(1)?.totalScrobbles) },
    )
  }

  @Test
  fun libraryArtistsReturnsAllowlistedPublicPage() {
    stubFor(
      get(urlPathEqualTo("/2.0/"))
        .withQueryParam("method", equalTo("library.getArtists"))
        .withQueryParam("api_key", equalTo("key"))
        .withQueryParam("user", equalTo("login"))
        .withQueryParam("page", equalTo("2"))
        .withQueryParam("limit", equalTo("100"))
        .willReturn(
          okJson(
            """
            {
              "artists": {
                "@attr": {
                  "page": "2",
                  "perPage": "100",
                  "totalPages": "79",
                  "total": "7803"
                },
                "artist": [
                  {
                    "name": "Linkin Park",
                    "playcount": "10900",
                    "mbid": "",
                    "url": "https://www.last.fm/music/Linkin+Park"
                  }
                ]
              }
            }
            """
              .trimIndent()
          )
        )
    )

    val resp =
      rest.getForEntity(
        "/api/lastfm/users/login/artists?page=2&limit=100",
        LastFmLibraryPage::class.java,
      )

    assertAll(
      { assertEquals(HttpStatus.OK, resp.statusCode) },
      { assertEquals(7803L, resp.body?.total) },
      { assertEquals(79, resp.body?.totalPages) },
      { assertEquals("Linkin Park", resp.body?.artists?.single()?.name) },
      { assertEquals(10900L, resp.body?.artists?.single()?.playcount) },
    )
  }

  @Test
  fun libraryArtistsReturnsNotFoundOutsideAllowlist() {
    val resp = rest.getForEntity("/api/lastfm/users/other/artists", String::class.java)

    assertEquals(HttpStatus.NOT_FOUND, resp.statusCode)
  }

  @Test
  fun verifyLoginTrue() {
    stubFor(
      get(urlPathEqualTo("/2.0/"))
        .withQueryParam("method", equalTo("user.getInfo"))
        .withQueryParam("user", equalTo("login"))
        .willReturn(okJson("""{"user":{"name":"login"}}"""))
    )
    val resp =
      rest.postForEntity(
        "/verifyLastFmId/login",
        HttpEntity<String>(
          HttpHeaders().apply {
            set("X-Requested-With", "XMLHttpRequest")
            set(HttpHeaders.COOKIE, "clientId=session_verify")
          }
        ),
        Boolean::class.java,
      )
    assertAll({ assertEquals(HttpStatus.OK, resp.statusCode) }, { assertEquals(true, resp.body) })
  }

  @Test
  fun verifyLoginFalse() {
    stubFor(
      get(urlPathEqualTo("/2.0/"))
        .withQueryParam("method", equalTo("user.getInfo"))
        .withQueryParam("user", equalTo("login"))
        .willReturn(
          aResponse().withStatus(404).withBody("""{"error":6,"message":"User not found"}""")
        )
    )
    val resp =
      rest.postForEntity(
        "/verifyLastFmId/login",
        HttpEntity<String>(
          HttpHeaders().apply {
            set("X-Requested-With", "XMLHttpRequest")
            set(HttpHeaders.COOKIE, "clientId=session_verify")
          }
        ),
        Boolean::class.java,
      )
    assertAll({ assertEquals(HttpStatus.OK, resp.statusCode) }, { assertEquals(false, resp.body) })
  }

  @Test
  fun verifyLoginUnauthorized() {
    stubFor(
      get(urlPathEqualTo("/2.0/"))
        .withQueryParam("method", equalTo("user.getInfo"))
        .withQueryParam("user", equalTo("login"))
        .willReturn(aResponse().withStatus(401).withBody("""{"error":17,"message":"Login"}"""))
    )
    val noRedirect =
      rest.withRequestFactorySettings {
        it.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
      }
    val resp =
      noRedirect.postForEntity(
        "/verifyLastFmId/login",
        HttpEntity<String>(
          HttpHeaders().apply {
            set("X-Requested-With", "XMLHttpRequest")
            set(HttpHeaders.COOKIE, "clientId=session_verify")
          }
        ),
        String::class.java,
      )
    assertAll(
      { assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode) },
      { assertEquals("/auth/lastfm", resp.headers.location.toString()) },
    )
  }
}
