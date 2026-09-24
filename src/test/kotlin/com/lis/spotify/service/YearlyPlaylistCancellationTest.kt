package com.lis.spotify.service

import com.lis.spotify.domain.Song
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

class YearlyPlaylistCancellationTest {
  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun cancellationDuringPlaylistItemsReadPreventsLaterMutation(duringDeduplication: Boolean) =
    runBlocking {
      val auth = mockk<SpotifyAuthenticationService>()
      every { auth.getHeaders("cid") } returns HttpHeaders()
      val writes = AtomicInteger()
      val rest =
        SpotifyRestService(
          RestTemplateBuilder()
            .additionalInterceptors({ request, body, execution ->
              if (request.method != HttpMethod.GET) writes.incrementAndGet()
              execution.execute(request, body)
            }),
          auth,
        )
      val server = MockRestServiceServer.createServer(rest.restTemplate)
      val itemsUrl = "https://api.spotify.com/v1/playlists/playlist-2024/items"
      server
        .expect(requestTo("https://api.spotify.com/v1/me/playlists"))
        .andRespond(
          withSuccess(
            """{"items":[{"id":"playlist-2024","name":"LAST.FM 2024"}],"next":null}""",
            MediaType.APPLICATION_JSON,
          )
        )
      if (duringDeduplication) {
        server
          .expect(requestTo(itemsUrl))
          .andExpect(method(HttpMethod.GET))
          .andRespond(withSuccess("""{"items":[],"next":null}""", MediaType.APPLICATION_JSON))
        server
          .expect(requestTo(itemsUrl))
          .andExpect(method(HttpMethod.POST))
          .andRespond(withSuccess("""{"snapshot_id":"updated"}""", MediaType.APPLICATION_JSON))
      }
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val item =
        """{"track":{"id":"track","name":"Title","artists":[],"album":{"id":"album","name":"Album","artists":[]}}}"""
      server.expect(requestTo(itemsUrl)).andExpect(method(HttpMethod.GET)).andRespond { request ->
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        withSuccess(
            if (duringDeduplication) """{"items":[$item,$item],"next":null}"""
            else """{"items":[],"next":null}""",
            MediaType.APPLICATION_JSON,
          )
          .createResponse(request)
      }
      val lastFm = mockk<LastFmService>()
      val search = mockk<SpotifySearchService>()
      every { lastFm.yearlyChartlist("cid", 2024, "login", 250) } returns
        listOf(Song("Artist", "Title"))
      coEvery { search.searchTrackIds(any(), "cid", any()) } returns listOf("track")
      val yearly =
        SpotifyTopPlaylistsService(
            SpotifyPlaylistService(rest),
            mockk(relaxed = true),
            lastFm,
            search,
          )
          .apply {
            firstSupportedYear = 2024
            currentYearProvider = { 2024 }
          }
      val job = launch(Dispatchers.IO) { yearly.updateYearlyPlaylistsSuspending("cid", "login") }
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()
      } finally {
        release.countDown()
      }
      try {
        job.join()
        assertEquals(if (duringDeduplication) 1 else 0, writes.get())
        server.verify()
      } finally {
        rest.close()
      }
    }
}
