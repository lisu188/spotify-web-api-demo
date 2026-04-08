package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.SearchResultInternal
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import com.lis.spotify.persistence.InMemorySpotifySearchCacheStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpServerErrorException

class SpotifySearchServiceTest {
  @Test
  fun serviceInstantiates() {
    val service =
      SpotifySearchService(mockk(relaxed = true), InMemorySpotifySearchCacheStore(), fixedClock())
    assertNotNull(service)
  }

  @Test
  fun searchListReturnsIds() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    every { rest.doRequest(any<() -> Any>()) } returns result

    val ids = service.doSearch(listOf(Song("a", "t")), "cid")

    assertEquals(listOf("1"), ids)
  }

  @Test
  fun searchCacheIsScopedByClientId() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    every { rest.doRequest(any<() -> Any>()) } returns result

    val song = Song("a", "t")
    service.doSearch(song, "cid1")
    service.doSearch(song, "cid2")

    verify(exactly = 2) { rest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun searchUsesPersistentCacheAcrossServiceInstances() {
    val store = InMemorySpotifySearchCacheStore()
    val rest = mockk<SpotifyRestService>()
    val firstService = SpotifySearchService(rest, store, fixedClock())
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    every { rest.doRequest(any<() -> Any>()) } returns result

    val song = Song("artist", "title")
    val firstResult = firstService.doSearch(song, "cid")
    val secondRest = mockk<SpotifyRestService>(relaxed = true)
    val secondService = SpotifySearchService(secondRest, store, fixedClock())
    val secondResult = secondService.doSearch(song, "cid")

    assertEquals("1", firstResult?.tracks?.items?.firstOrNull()?.id)
    assertEquals("1", secondResult?.tracks?.items?.firstOrNull()?.id)
    verify(exactly = 1) { rest.doRequest(any<() -> Any>()) }
    verify(exactly = 0) { secondRest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun expiredPersistentCacheIsRefreshed() {
    val store = InMemorySpotifySearchCacheStore()
    val firstClock = fixedClock("2026-04-08T10:00:00Z")
    val rest = mockk<SpotifyRestService>()
    val firstService = SpotifySearchService(rest, store, firstClock)
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    every { rest.doRequest(any<() -> Any>()) } returns result

    val song = Song("artist", "title")
    firstService.doSearch(song, "cid")

    val secondRest = mockk<SpotifyRestService>()
    every { secondRest.doRequest(any<() -> Any>()) } returns result
    val expiredClock = fixedClock("2026-04-16T10:00:01Z")
    val secondService = SpotifySearchService(secondRest, store, expiredClock)
    secondService.doSearch(song, "cid")

    verify(exactly = 1) { rest.doRequest(any<() -> Any>()) }
    verify(exactly = 1) { secondRest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun batchSearchUsesConfiguredParallelism() {
    val rest = mockk<SpotifyRestService>()
    val service =
      SpotifySearchService(
        spotifyRestService = rest,
        spotifySearchCacheStore = InMemorySpotifySearchCacheStore(),
        clock = fixedClock(),
        configuredMaxParallelism = 2,
        configuredCacheTtl = Duration.ofDays(7),
      )
    val activeRequests = AtomicInteger()
    val maxActiveRequests = AtomicInteger()
    val startedRequests = CountDownLatch(2)
    val releaseRequests = CountDownLatch(1)
    val ids = AtomicInteger()

    every { rest.doRequest(any<() -> Any>()) } answers
      {
        val active = activeRequests.incrementAndGet()
        maxActiveRequests.accumulateAndGet(active, ::maxOf)
        startedRequests.countDown()
        startedRequests.await(2, TimeUnit.SECONDS)
        releaseRequests.await(2, TimeUnit.SECONDS)
        activeRequests.decrementAndGet()
        val id = ids.incrementAndGet().toString()
        SearchResult(
          SearchResultInternal(
            listOf(
              Track(
                id,
                "track-$id",
                listOf(Artist("artist-$id", "artist-$id")),
                Album("album-$id", "album-$id", emptyList()),
              )
            )
          )
        )
      }

    val songs = (1..4).map { Song("artist-$it", "title-$it") }
    val executor = Executors.newSingleThreadExecutor()
    try {
      val future = executor.submit<List<String>> { service.doSearch(songs, "cid") }

      assertTrue(startedRequests.await(2, TimeUnit.SECONDS))
      releaseRequests.countDown()
      val resultIds = future.get(5, TimeUnit.SECONDS)

      assertEquals(4, resultIds.size)
    } finally {
      releaseRequests.countDown()
      executor.shutdownNow()
    }

    assertEquals(2, maxActiveRequests.get())
    verify(exactly = 4) { rest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun transientServerErrorsAreRetriedAndEventuallySucceed() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val sleepCalls = mutableListOf<Long>()
    service.sleeper = SpotifySearchSleeper { millis -> sleepCalls += millis }
    val result =
      SearchResult(
        SearchResultInternal(
          listOf(Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList())))
        )
      )
    val exception =
      HttpServerErrorException.create(
        HttpStatus.BAD_GATEWAY,
        "",
        HttpHeaders(),
        "{\"error\":{\"status\":502,\"message\":\"An unexpected error occurred. Please try again later.\"}}"
          .toByteArray(),
        null,
      )
    every { rest.doRequest(any<() -> Any>()) } throws exception andThen result

    val searchResult = service.doSearch(Song("artist", "title"), "cid")

    assertEquals("1", searchResult?.tracks?.items?.firstOrNull()?.id)
    assertEquals(listOf(SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS), sleepCalls)
    verify(exactly = 2) { rest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun repeatedServerErrorsSkipOnlyFailingTrack() {
    val rest = mockk<SpotifyRestService>()
    val service =
      SpotifySearchService(
        spotifyRestService = rest,
        spotifySearchCacheStore = InMemorySpotifySearchCacheStore(),
        clock = fixedClock(),
        configuredMaxParallelism = 1,
        configuredCacheTtl = Duration.ofDays(7),
      )
    val sleepCalls = mutableListOf<Long>()
    service.sleeper = SpotifySearchSleeper { millis -> sleepCalls += millis }
    val result =
      SearchResult(
        SearchResultInternal(
          listOf(Track("ok", "ok", listOf(Artist("2", "a")), Album("3", "al", emptyList())))
        )
      )
    val exception =
      HttpServerErrorException.create(
        HttpStatus.BAD_GATEWAY,
        "",
        HttpHeaders(),
        "{\"error\":{\"status\":502,\"message\":\"An unexpected error occurred. Please try again later.\"}}"
          .toByteArray(),
        null,
      )
    every { rest.doRequest(any<() -> Any>()) } throws
      exception andThenThrows
      exception andThenThrows
      exception andThen
      result

    val ids = service.doSearch(listOf(Song("bad", "track"), Song("good", "track")), "cid")

    assertEquals(listOf("ok"), ids)
    assertEquals(
      listOf(
        SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS,
        SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS * 2,
      ),
      sleepCalls,
    )
    verify(exactly = 4) { rest.doRequest(any<() -> Any>()) }
  }

  private fun fixedClock(instant: String = "2026-04-08T10:00:00Z"): Clock {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
  }
}
