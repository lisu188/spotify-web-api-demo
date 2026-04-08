package com.lis.spotify.service

import com.lis.spotify.domain.Song
import com.lis.spotify.persistence.InMemoryLastFmRecentTracksCacheStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate

class LastFmServiceTest {
  @Test
  fun yearlyChartlistParsesSongs() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T"))

    val songs = service.yearlyChartlist("cid", 2020, "login")

    assertEquals(listOf(Song("A", "T")), songs)
  }

  @Test
  fun pagingReturnsAllSongs() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } answers
      {
        when (page(firstArg())) {
          1 -> recentTracksPage(3, Song("A", "T1"))
          2 -> recentTracksPage(3, Song("B", "T2"))
          else -> recentTracksPage(3)
        }
      }

    val songs = service.yearlyChartlist("cid", 2020, "login")

    assertEquals(listOf(Song("A", "T1"), Song("B", "T2")), songs)
  }

  @Test
  fun limitStopsPaging() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(3, Song("A", "T1"))

    val songs = service.yearlyChartlist("cid", 2020, "login", limit = 1)

    assertEquals(listOf(Song("A", "T1")), songs)
    verify(exactly = 1) { rest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun yearlyChartlistUsesPersistentCacheAcrossServiceInstances() {
    val store = InMemoryLastFmRecentTracksCacheStore()
    val rest = mockk<RestTemplate>()
    val firstService = service(store = store)
    firstService.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))

    val firstSongs = firstService.yearlyChartlist("cid", 2020, "login")

    val secondRest = mockk<RestTemplate>(relaxed = true)
    val secondService = service(store = store)
    secondService.rest = secondRest
    val cachedSongs = secondService.yearlyChartlist("cid", 2020, "login")

    assertEquals(firstSongs, cachedSongs)
    verify(exactly = 1) { rest.getForObject(any<URI>(), Map::class.java) }
    verify(exactly = 0) { secondRest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun yearlyChartlistRefreshesExpiredPersistentCache() {
    val store = InMemoryLastFmRecentTracksCacheStore()
    val rest = mockk<RestTemplate>()
    val firstService = service(store = store, clock = fixedClock("2026-04-08T10:00:00Z"))
    firstService.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))

    firstService.yearlyChartlist("cid", 2020, "login")

    val expiredRest = mockk<RestTemplate>()
    every { expiredRest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))
    val secondService = service(store = store, clock = fixedClock("2026-04-16T10:00:01Z"))
    secondService.rest = expiredRest

    secondService.yearlyChartlist("cid", 2020, "login")

    verify(exactly = 1) { rest.getForObject(any<URI>(), Map::class.java) }
    verify(exactly = 1) { expiredRest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun yearlyChartlistFetchesRemainingPagesInParallelUpToConfiguredLimit() {
    val rest = mockk<RestTemplate>()
    val service = service(recentTracksParallelism = 2)
    val activeRequests = AtomicInteger()
    val maxActiveRequests = AtomicInteger()
    val startedRequests = CountDownLatch(2)
    val releaseRequests = CountDownLatch(1)
    service.rest = rest

    every { rest.getForObject(any<URI>(), Map::class.java) } answers
      {
        when (page(firstArg())) {
          1 -> recentTracksPage(3, Song("A", "T1"))
          else -> {
            val active = activeRequests.incrementAndGet()
            maxActiveRequests.accumulateAndGet(active, ::maxOf)
            startedRequests.countDown()
            startedRequests.await(2, TimeUnit.SECONDS)
            releaseRequests.await(2, TimeUnit.SECONDS)
            activeRequests.decrementAndGet()
            recentTracksPage(3, Song("B${page(firstArg())}", "T${page(firstArg())}"))
          }
        }
      }

    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    try {
      val future = executor.submit<List<Song>> { service.yearlyChartlist("cid", 2020, "login") }

      assertTrue(startedRequests.await(2, TimeUnit.SECONDS))
      releaseRequests.countDown()
      val songs = future.get(5, TimeUnit.SECONDS)

      assertEquals(3, songs.size)
    } finally {
      releaseRequests.countDown()
      executor.shutdownNow()
    }

    assertEquals(2, maxActiveRequests.get())
  }

  @Test
  fun missingUserThrows400() {
    val service = service()
    assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "") }
  }

  @Test
  fun rateLimitExceptionParsed() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    val ex =
      HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "",
        HttpHeaders(),
        "{\"error\":29,\"message\":\"Rate limit\"}".toByteArray(),
        null,
      )
    every { rest.getForObject(any<URI>(), Map::class.java) } throws ex

    val thrown =
      assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "u").first() }

    assertEquals(29, thrown.code)
  }

  @Test
  fun invalidApiKeyExceptionParsed() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    val ex =
      HttpClientErrorException.create(
        HttpStatus.FORBIDDEN,
        "",
        HttpHeaders(),
        "{\"error\":10,\"message\":\"Invalid API key\"}".toByteArray(),
        null,
      )
    every { rest.getForObject(any<URI>(), Map::class.java) } throws ex

    val thrown =
      assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "u").first() }

    assertEquals(10, thrown.code)
  }

  @Test
  fun transientBackendFailuresAreRetried() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    val sleepCalls = mutableListOf<Long>()
    service.sleeper = LastFmSleeper { millis -> sleepCalls += millis }
    val ex =
      HttpServerErrorException.create(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "",
        HttpHeaders(),
        "{\"error\":8,\"message\":\"Operation failed - Most likely the backend service failed. Please try again.\"}"
          .toByteArray(),
        null,
      )
    every { rest.getForObject(any<URI>(), Map::class.java) } throws
      ex andThen
      recentTracksPage(1, Song("A", "T"))

    val songs = service.yearlyChartlist("cid", 2020, "login")

    assertEquals(listOf(Song("A", "T")), songs)
    assertEquals(listOf(LastFmService.LASTFM_RETRY_DELAY_MS), sleepCalls)
    verify(exactly = 2) { rest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun transientBackendFailuresStopAfterRetryBudget() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    val sleepCalls = mutableListOf<Long>()
    service.sleeper = LastFmSleeper { millis -> sleepCalls += millis }
    val ex =
      HttpServerErrorException.create(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "",
        HttpHeaders(),
        "{\"error\":8,\"message\":\"Operation failed - Most likely the backend service failed. Please try again.\"}"
          .toByteArray(),
        null,
      )
    every { rest.getForObject(any<URI>(), Map::class.java) } throws ex

    val thrown =
      assertThrows(LastFmException::class.java) { service.yearlyChartlist("cid", 2020, "login") }

    assertEquals(8, thrown.code)
    assertEquals(
      listOf(LastFmService.LASTFM_RETRY_DELAY_MS, LastFmService.LASTFM_RETRY_DELAY_MS * 2),
      sleepCalls,
    )
    verify(exactly = LastFmService.LASTFM_FETCH_ATTEMPTS) {
      rest.getForObject(any<URI>(), Map::class.java)
    }
  }

  @Test
  fun sessionKeyAddedToRequest() {
    val rest = mockk<RestTemplate>()
    val auth = mockk<LastFmAuthenticationService>()
    every { auth.getSessionKey("login") } returns "sess"
    val service = service(auth = auth)
    service.rest = rest
    val uriSlot = io.mockk.slot<URI>()
    every { rest.getForObject(capture(uriSlot), Map::class.java) } returns recentTracksPage(1)

    service.yearlyChartlist("c", 2020, "login")

    assertTrue(uriSlot.captured.query!!.contains("sk=sess"))
  }

  @Test
  fun globalChartlistForwardsPage() {
    val service = spyk(service())
    every { service.yearlyChartlist("", 1970, "login", startPage = 2) } returns
      listOf(Song("a", "b"))

    val result = service.globalChartlist("login", 2)

    assertEquals(listOf(Song("a", "b")), result)
  }

  @Test
  fun userExistsReturnsTrue() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns emptyMap<String, Any>()

    val result = service.userExists("login")

    assertEquals(true, result)
  }

  @Test
  fun userExistsReturnsFalseOnNotFound() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    val ex =
      HttpClientErrorException.create(
        HttpStatus.NOT_FOUND,
        "",
        HttpHeaders(),
        "{\"error\":6,\"message\":\"Not found\"}".toByteArray(),
        null,
      )
    every { rest.getForObject(any<URI>(), Map::class.java) } throws ex

    val result = service.userExists("login")

    assertEquals(false, result)
  }

  @Test
  fun userExistsThrowsOtherErrors() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    val ex =
      HttpClientErrorException.create(
        HttpStatus.UNAUTHORIZED,
        "",
        HttpHeaders(),
        "{\"error\":17,\"message\":\"Login\"}".toByteArray(),
        null,
      )
    every { rest.getForObject(any<URI>(), Map::class.java) } throws ex

    assertThrows(LastFmException::class.java) { service.userExists("login") }
  }

  @Test
  fun networkErrorsAreWrapped() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } throws ResourceAccessException("boom")

    val ex = assertThrows(LastFmException::class.java) { service.userExists("u") }

    assertEquals(503, ex.code)
  }

  private fun service(
    auth: LastFmAuthenticationService = mockk(relaxed = true),
    store: InMemoryLastFmRecentTracksCacheStore = InMemoryLastFmRecentTracksCacheStore(),
    clock: Clock = fixedClock(),
    recentTracksParallelism: Int = LastFmService.DEFAULT_RECENT_TRACKS_MAX_PARALLELISM,
    cacheTtl: Duration = LastFmService.DEFAULT_CACHE_TTL,
  ): LastFmService {
    return LastFmService(
      lastFmAuthService = auth,
      lastFmRecentTracksCacheStore = store,
      clock = clock,
      configuredRecentTracksParallelism = recentTracksParallelism,
      configuredCacheTtl = cacheTtl,
    )
  }

  private fun recentTracksPage(totalPages: Int, vararg songs: Song): Map<String, Any> {
    return mapOf(
      "recenttracks" to
        mapOf(
          "@attr" to mapOf("totalPages" to totalPages.toString()),
          "track" to
            songs.map { song ->
              mapOf("artist" to mapOf("#text" to song.artist), "name" to song.title)
            },
        )
    )
  }

  private fun page(uri: URI): Int {
    return uri.query
      ?.split("&")
      ?.firstOrNull { it.startsWith("page=") }
      ?.substringAfter("=")
      ?.toInt() ?: 1
  }

  private fun fixedClock(instant: String = "2026-04-08T10:00:00Z"): Clock {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
  }
}
