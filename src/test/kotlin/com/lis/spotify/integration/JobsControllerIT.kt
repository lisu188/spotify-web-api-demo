package com.lis.spotify.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.reset as wireMockReset
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.lis.spotify.service.AuthenticationRequiredException
import com.lis.spotify.service.ForgottenObsessionsPlaylistResult
import com.lis.spotify.service.PrivateMoodPlaylistResult
import com.lis.spotify.service.PrivateMoodTaxonomyResult
import com.lis.spotify.service.SpotifyTopPlaylistsService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.util.Date
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
import org.springframework.context.annotation.Primary
import org.springframework.http.*
import org.springframework.scheduling.TaskScheduler
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(JobsControllerIT.Config::class)
class JobsControllerIT
@Autowired
constructor(
  private val rest: TestRestTemplate,
  private val playlistService: SpotifyTopPlaylistsService,
) {
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
    clearMocks(playlistService)
    every { playlistService.updateYearlyPlaylists(any(), any(), any()) } returns Unit
    every { playlistService.updateForgottenObsessionsPlaylist(any(), any(), any()) } returns
      ForgottenObsessionsPlaylistResult("playlist-1", 12, 12, 18)
    every { playlistService.updatePrivateMoodTaxonomyPlaylists(any(), any(), any(), any()) } returns
      PrivateMoodTaxonomyResult(
        listOf(
          PrivateMoodPlaylistResult("Anchor", "Private Mood - Anchor", "anchor-id", 12, 20),
          PrivateMoodPlaylistResult("Happy", "Private Mood - Happy", "happy-id", 10, 16),
          PrivateMoodPlaylistResult("Sad", "Private Mood - Sad", "sad-id", 9, 15),
          PrivateMoodPlaylistResult("Surge", "Private Mood - Surge", "surge-id", 8, 14),
          PrivateMoodPlaylistResult("Night Drift", "Private Mood - Night Drift", "night-id", 6, 9),
          PrivateMoodPlaylistResult("Frontier", "Private Mood - Frontier", "frontier-id", 15, 24),
        )
      )
    every { playlistService.updateTopPlaylists(any()) } returns emptyList()
  }

  @Test
  fun jobLifecycle() {
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "clientId=cid")
    val req = HttpEntity(mapOf("lastFmLogin" to "login"), headers)
    val resp = rest.postForEntity("/jobs", req, Map::class.java)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
    val jobId = resp.body?.get("jobId") as String

    val status = rest.getForEntity("/jobs/$jobId", Map::class.java)

    assertEquals(HttpStatus.OK, status.statusCode)
    assertEquals("COMPLETED", status.body?.get("state"))
    assertEquals(100, status.body?.get("progressPercent"))
  }

  @Test
  fun jobAuthFailurePreservesLastFmRedirect() {
    every { playlistService.updateYearlyPlaylists(any(), any(), any()) } throws
      AuthenticationRequiredException("LASTFM")
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "clientId=cid")
    val req = HttpEntity(mapOf("lastFmLogin" to "login"), headers)

    val resp = rest.postForEntity("/jobs", req, Map::class.java)
    val jobId = resp.body?.get("jobId") as String

    val status = rest.getForEntity("/jobs/$jobId", Map::class.java)

    assertEquals(HttpStatus.OK, status.statusCode)
    assertEquals("FAILED", status.body?.get("state"))
    assertEquals("/auth/lastfm?lastFmLogin=login", status.body?.get("redirectUrl"))
  }

  @Test
  fun forgottenObsessionsJobReturnsPlaylistIds() {
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "clientId=cid")
    val req = HttpEntity(mapOf("lastFmLogin" to "login"), headers)

    val resp = rest.postForEntity("/jobs/forgotten-obsessions", req, Map::class.java)
    val jobId = resp.body?.get("jobId") as String
    val status = rest.getForEntity("/jobs/$jobId", Map::class.java)

    assertEquals(HttpStatus.OK, status.statusCode)
    assertEquals("COMPLETED", status.body?.get("state"))
    assertEquals(listOf("playlist-1"), status.body?.get("playlistIds"))
  }

  @Test
  fun forgottenObsessionsJobRejectsBlankLogin() {
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "clientId=cid")
    val req = HttpEntity(mapOf("lastFmLogin" to "   "), headers)

    val resp = rest.postForEntity("/jobs/forgotten-obsessions", req, Map::class.java)

    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun privateMoodTaxonomyJobReturnsPlaylistIds() {
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "clientId=cid")
    val req = HttpEntity(mapOf("lastFmLogin" to "login"), headers)

    val resp = rest.postForEntity("/jobs/private-mood-taxonomy", req, Map::class.java)
    val jobId = resp.body?.get("jobId") as String
    val status = rest.getForEntity("/jobs/$jobId", Map::class.java)

    assertEquals(HttpStatus.OK, status.statusCode)
    assertEquals("COMPLETED", status.body?.get("state"))
    assertEquals(
      listOf("anchor-id", "happy-id", "sad-id", "surge-id", "night-id", "frontier-id"),
      status.body?.get("playlistIds"),
    )
  }

  @Test
  fun privateMoodTaxonomyJobRejectsBlankLogin() {
    val headers = HttpHeaders()
    headers.add(HttpHeaders.COOKIE, "clientId=cid")
    val req = HttpEntity(mapOf("lastFmLogin" to "   "), headers)

    val resp = rest.postForEntity("/jobs/private-mood-taxonomy", req, Map::class.java)

    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  class Config {
    @Bean
    @Primary
    fun playlistService(taskScheduler: TaskScheduler): SpotifyTopPlaylistsService {
      val svc = mockk<SpotifyTopPlaylistsService>(relaxed = true)
      every { svc.updateYearlyPlaylists(any(), any(), any()) } returns Unit
      every { svc.updateForgottenObsessionsPlaylist(any(), any(), any()) } returns
        ForgottenObsessionsPlaylistResult("playlist-1", 12, 12, 18)
      every { svc.updatePrivateMoodTaxonomyPlaylists(any(), any(), any(), any()) } returns
        PrivateMoodTaxonomyResult(
          listOf(
            PrivateMoodPlaylistResult("Anchor", "Private Mood - Anchor", "anchor-id", 12, 20),
            PrivateMoodPlaylistResult("Happy", "Private Mood - Happy", "happy-id", 10, 16),
            PrivateMoodPlaylistResult("Sad", "Private Mood - Sad", "sad-id", 9, 15),
            PrivateMoodPlaylistResult("Surge", "Private Mood - Surge", "surge-id", 8, 14),
            PrivateMoodPlaylistResult(
              "Night Drift",
              "Private Mood - Night Drift",
              "night-id",
              6,
              9,
            ),
            PrivateMoodPlaylistResult("Frontier", "Private Mood - Frontier", "frontier-id", 15, 24),
          )
        )
      every { svc.updateTopPlaylists(any()) } returns emptyList()
      return svc
    }

    @Bean
    @Primary
    fun immediateTaskScheduler(): TaskScheduler {
      val scheduler = mockk<TaskScheduler>()
      every { scheduler.schedule(any<Runnable>(), any<Date>()) } answers
        {
          firstArg<Runnable>().run()
          mockk(relaxed = true)
        }
      every {
        scheduler.scheduleWithFixedDelay(any<Runnable>(), any<Instant>(), any<Duration>())
      } returns mockk(relaxed = true)
      return scheduler
    }
  }
}
