package com.lis.spotify.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.reset as wireMockReset
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.lis.spotify.service.SpotifyTopPlaylistsService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.*
import org.springframework.scheduling.TaskScheduler
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(JobsControllerIT.Config::class)
class JobsControllerIT @Autowired constructor(private val rest: TestRestTemplate) {
  companion object {
    val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
    val baseUrl: String
      get() = "http://localhost:${wm.port()}"

    @JvmStatic
    @DynamicPropertySource
    fun props(registry: DynamicPropertyRegistry) {
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
    }

    @JvmStatic
    @AfterAll
    fun stop() {
      wm.stop()
    }
  }

  @BeforeEach
  fun reset() {
    wireMockReset()
  }

  @Test
  fun jobLifecycle() {
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "clientId=cid")
    val req = HttpEntity(mapOf("lastFmLogin" to "login"), headers)
    val resp = rest.postForEntity("/jobs", req, Map::class.java)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  class Config {
    @Bean
    fun playlistService(taskScheduler: TaskScheduler): SpotifyTopPlaylistsService {
      val svc = mockk<SpotifyTopPlaylistsService>()
      every { svc.updateYearlyPlaylists(any(), any()) } returns Unit
      every { svc.updateTopPlaylists(any()) } returns emptyList()
      return svc
    }
  }
}
