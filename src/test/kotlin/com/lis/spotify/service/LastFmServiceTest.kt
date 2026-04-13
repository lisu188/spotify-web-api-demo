package com.lis.spotify.service

import com.lis.spotify.domain.Song
import com.lis.spotify.persistence.InMemoryLastFmRecentTracksCacheStore
import com.lis.spotify.persistence.LastFmRecentTracksCacheStore
import com.lis.spotify.persistence.StoredLastFmRecentTracksPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
  fun yearlyChartlistParsesPlayedAtEpochSecond() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T", playedAtEpochSecond = 1_700_000_000))

    val songs = service.yearlyChartlist("cid", 2020, "login")

    assertEquals(listOf(Song("A", "T", playedAtEpochSecond = 1_700_000_000)), songs)
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
    val firstService = service(store = store, persistentRecentTracksCacheEnabled = true)
    firstService.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))

    val firstSongs = firstService.yearlyChartlist("cid", 2020, "login")

    val secondRest = mockk<RestTemplate>(relaxed = true)
    val secondService = service(store = store, persistentRecentTracksCacheEnabled = true)
    secondService.rest = secondRest
    val cachedSongs = secondService.yearlyChartlist("cid", 2020, "login")

    assertEquals(
      firstSongs.map { it.copy(playedAtEpochSecond = null) },
      cachedSongs.map { it.copy(playedAtEpochSecond = null) },
    )
    verify(exactly = 1) { rest.getForObject(any<URI>(), Map::class.java) }
    verify(exactly = 0) { secondRest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun yearlyChartlistSkipsPersistentCacheAcrossServiceInstancesWhenDisabled() {
    val store = InMemoryLastFmRecentTracksCacheStore()
    val firstRest = mockk<RestTemplate>()
    val secondRest = mockk<RestTemplate>()
    val firstService = service(store = store)
    val secondService = service(store = store)
    firstService.rest = firstRest
    secondService.rest = secondRest
    every { firstRest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))
    every { secondRest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))

    val firstSongs = firstService.yearlyChartlist("cid", 2020, "login")
    val secondSongs = secondService.yearlyChartlist("cid", 2020, "login")

    assertEquals(firstSongs, secondSongs)
    verify(exactly = 1) { firstRest.getForObject(any<URI>(), Map::class.java) }
    verify(exactly = 1) { secondRest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun yearlyChartlistBackfillsPlayedAtFromLegacyCacheEntries() {
    val store = InMemoryLastFmRecentTracksCacheStore()
    store.save(
      StoredLastFmRecentTracksPage(
        cacheKey = legacyCacheKey("login", 2020),
        login = "login",
        from = 1_577_836_800,
        to = 1_609_459_199,
        page = 1,
        sessionKey = "",
        payloadJson = """{"totalPages":1,"songs":[{"artist":"A","title":"T1"}]}""",
        updatedAt = Instant.parse("2026-04-08T10:00:00Z"),
        expiresAt = Instant.parse("2026-04-15T10:00:00Z"),
      )
    )
    val service =
      service(
        store = store,
        clock = fixedClock("2026-04-08T10:00:00Z"),
        persistentRecentTracksCacheEnabled = true,
      )

    val songs = service.yearlyChartlist("cid", 2020, "login")

    assertEquals(listOf(Song("A", "T1", playedAtEpochSecond = 1_609_459_199)), songs)
  }

  @Test
  fun yearlyChartlistRefreshesExpiredPersistentCache() {
    val store = InMemoryLastFmRecentTracksCacheStore()
    val rest = mockk<RestTemplate>()
    val firstService =
      service(
        store = store,
        clock = fixedClock("2026-04-08T10:00:00Z"),
        persistentRecentTracksCacheEnabled = true,
      )
    firstService.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))

    firstService.yearlyChartlist("cid", 2020, "login")

    val expiredRest = mockk<RestTemplate>()
    every { expiredRest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))
    val secondService =
      service(
        store = store,
        clock = fixedClock("2026-04-16T10:00:01Z"),
        persistentRecentTracksCacheEnabled = true,
      )
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
  fun yearlyChartlistFallsBackToNetworkWhenPersistentCacheReadFails() {
    val store = mockk<LastFmRecentTracksCacheStore>()
    val rest = mockk<RestTemplate>()
    val service = service(store = store, persistentRecentTracksCacheEnabled = true)
    service.rest = rest
    every { store.findByKey(any()) } throws IllegalStateException("boom")
    every { store.save(any()) } answers { firstArg() }
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      recentTracksPage(1, Song("A", "T1"))

    val songs = service.yearlyChartlist("cid", 2020, "login")

    assertEquals(listOf(Song("A", "T1")), songs)
    verify(exactly = 1) { rest.getForObject(any<URI>(), Map::class.java) }
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
  fun trackSimilarParsesSongsAndUsesCache() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf(
        "similartracks" to
          mapOf(
            "track" to
              listOf(
                mapOf(
                  "name" to "Signal",
                  "match" to "0.81",
                  "artist" to mapOf("name" to "Artist B"),
                )
              )
          )
      )

    val first = service.trackSimilar("Artist A", "Seed Song", 5)
    val second = service.trackSimilar("Artist A", "Seed Song", 5)

    assertEquals(listOf(LastFmSimilarTrack(Song("Artist B", "Signal"), 0.81)), first)
    assertEquals(first, second)
    verify(exactly = 1) { rest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun artistSimilarParsesArtists() {
    val rest = mockk<RestTemplate>()
    val service = service()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf(
        "similarartists" to
          mapOf(
            "artist" to
              listOf(
                mapOf("name" to "Night Swim", "match" to "0.55"),
                mapOf("name" to "Afterglow", "match" to 0.42),
              )
          )
      )

    val result = service.artistSimilar("Artist A", 5)

    assertEquals(
      listOf(LastFmSimilarArtist("Night Swim", 0.55), LastFmSimilarArtist("Afterglow", 0.42)),
      result,
    )
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
    store: LastFmRecentTracksCacheStore = InMemoryLastFmRecentTracksCacheStore(),
    clock: Clock = fixedClock(),
    recentTracksParallelism: Int = LastFmService.DEFAULT_RECENT_TRACKS_MAX_PARALLELISM,
    cacheTtl: Duration = LastFmService.DEFAULT_CACHE_TTL,
    persistentRecentTracksCacheEnabled: Boolean =
      LastFmService.DEFAULT_PERSISTENT_RECENT_TRACKS_CACHE_ENABLED,
  ): LastFmService {
    return LastFmService(
      lastFmAuthService = auth,
      lastFmRecentTracksCacheStore = store,
      clock = clock,
      configuredRecentTracksParallelism = recentTracksParallelism,
      configuredCacheTtl = cacheTtl,
      configuredPersistentRecentTracksCacheEnabled = persistentRecentTracksCacheEnabled,
    )
  }

  private fun recentTracksPage(totalPages: Int, vararg songs: Song): Map<String, Any> {
    return mapOf(
      "recenttracks" to
        mapOf(
          "@attr" to mapOf("totalPages" to totalPages.toString()),
          "track" to
            songs.map { song ->
              buildMap<String, Any> {
                put("artist", mapOf("#text" to song.artist))
                put("name", song.title)
                song.playedAtEpochSecond?.let { put("date", mapOf("uts" to it.toString())) }
              }
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

  private fun legacyCacheKey(user: String, year: Int): String {
    val from = LocalDate.of(year, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val to = LocalDate.of(year, 12, 31).atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC)
    val digest =
      MessageDigest.getInstance("SHA-256")
        .digest("$user|$from|$to|1|".toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }
}
