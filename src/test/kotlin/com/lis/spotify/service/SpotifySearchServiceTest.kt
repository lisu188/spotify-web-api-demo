package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.SearchResultInternal
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import com.lis.spotify.persistence.InMemorySpotifySearchCacheStore
import com.lis.spotify.persistence.SpotifySearchCacheStore
import com.lis.spotify.persistence.StoredSpotifySearchCacheEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException

class SpotifySearchServiceTest {
  @Test
  fun serviceInstantiates() {
    val service =
      SpotifySearchService(mockk(relaxed = true), InMemorySpotifySearchCacheStore(), fixedClock())
    assertNotNull(service)
  }

  @Test
  fun searchListReturnsIds() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns result

    val ids = service.doSearch(listOf(Song("a", "t")), "cid")

    assertEquals(listOf("1"), ids)
  }

  @Test
  fun searchPrefersClosestTrackMatchOverFirstResult() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val result =
      SearchResult(
        SearchResultInternal(
          listOf(
            Track(
              "wrong",
              "Completely Different Song",
              listOf(Artist("wrong-artist", "Another Artist")),
              Album("wrong-album", "Wrong Album", emptyList()),
            ),
            Track(
              "best",
              "The Less I Know The Better",
              listOf(Artist("artist-1", "Tame Impala")),
              Album("album-1", "Currents", emptyList()),
            ),
          )
        )
      )

    val bestId =
      service.selectClosestTrackId(Song("Tame Impala", "The Less I Know The Better"), result)

    assertEquals("best", bestId)
  }

  @Test
  fun searchTreatsVersionedTrackNamesAsSameSong() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val result =
      SearchResult(
        SearchResultInternal(
          listOf(
            Track(
              "best",
              "Dreams - 2004 Remaster",
              listOf(Artist("artist-1", "Fleetwood Mac")),
              Album("album-1", "Rumours", emptyList()),
            ),
            Track(
              "worse",
              "Dreams",
              listOf(Artist("artist-2", "Different Artist")),
              Album("album-2", "Compilation", emptyList()),
            ),
          )
        )
      )

    val bestId = service.selectClosestTrackId(Song("Fleetwood Mac", "Dreams"), result)

    assertEquals("best", bestId)
  }

  @Test
  fun searchCacheIsScopedByClientId() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns result

    val song = Song("a", "t")
    service.doSearch(song, "cid1")
    service.doSearch(song, "cid2")

    coVerify(exactly = 2) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun searchUsesPersistentCacheAcrossServiceInstances() {
    val store = InMemorySpotifySearchCacheStore()
    val rest = restService()
    val firstService = SpotifySearchService(rest, store, fixedClock())
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns result

    val song = Song("artist", "title")
    val firstResult = firstService.doSearch(song, "cid")
    val secondRest = mockk<SpotifyRestService>(relaxed = true)
    val secondService = SpotifySearchService(secondRest, store, fixedClock())
    val secondResult = secondService.doSearch(song, "cid")

    assertEquals("1", firstResult?.tracks?.items?.firstOrNull()?.id)
    assertEquals("1", secondResult?.tracks?.items?.firstOrNull()?.id)
    coVerify(exactly = 1) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
    coVerify(exactly = 0) { secondRest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun expiredPersistentCacheIsRefreshed() {
    val store = InMemorySpotifySearchCacheStore()
    val firstClock = fixedClock("2026-04-08T10:00:00Z")
    val rest = restService()
    val firstService = SpotifySearchService(rest, store, firstClock)
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns result

    val song = Song("artist", "title")
    firstService.doSearch(song, "cid")

    val secondRest = restService()
    coEvery { secondRest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns result
    val expiredClock = fixedClock("2026-04-16T10:00:01Z")
    val secondService = SpotifySearchService(secondRest, store, expiredClock)
    secondService.doSearch(song, "cid")

    coVerify(exactly = 1) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
    coVerify(exactly = 1) { secondRest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun corruptPersistentCacheIsRefetchedAndStored() {
    val clock = fixedClock()
    val store = CorruptSpotifySearchCacheStore(clock.instant())
    val rest = restService()
    val service = SpotifySearchService(rest, store, clock)
    val result =
      SearchResult(
        SearchResultInternal(
          listOf(Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList())))
        )
      )
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns result

    val searchResult = service.doSearch(Song("artist", "title"), "cid")

    assertEquals("1", searchResult?.tracks?.items?.firstOrNull()?.id)
    assertNotNull(store.savedEntry)
    assertTrue(store.savedEntry?.payloadJson?.contains("\"tracks\"") == true)
    coVerify(exactly = 1) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun searchSurvivesPersistentCacheStoreFailures() {
    val rest = restService()
    val service = SpotifySearchService(rest, ThrowingSpotifySearchCacheStore(), fixedClock())
    val track = Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList()))
    val result = SearchResult(SearchResultInternal(listOf(track)))
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns result

    // A failing persistent cache must degrade gracefully, not abort the whole batch search.
    val ids = service.doSearch(listOf(Song("a", "t")), "cid")

    assertEquals(listOf("1"), ids)
  }

  @Test
  fun batchSearchUsesConfiguredParallelism() {
    val rest = restService()
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
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } coAnswers
      {
        val active = activeRequests.incrementAndGet()
        maxActiveRequests.accumulateAndGet(active, ::maxOf)
        startedRequests.countDown()
        startedRequests.await(2, TimeUnit.SECONDS)
        releaseRequests.await(2, TimeUnit.SECONDS)
        activeRequests.decrementAndGet()
        SearchResult(
          SearchResultInternal(
            (1..4).map { trackIndex ->
              Track(
                trackIndex.toString(),
                "title-$trackIndex",
                listOf(Artist("artist-$trackIndex", "artist-$trackIndex")),
                Album("album-$trackIndex", "album-$trackIndex", emptyList()),
              )
            }
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
    coVerify(exactly = 4) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun transientServerErrorsAreRetriedAndEventuallySucceed() {
    val rest = restService()
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
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } throws
      exception andThen
      result

    val searchResult = service.doSearch(Song("artist", "title"), "cid")

    assertEquals("1", searchResult?.tracks?.items?.firstOrNull()?.id)
    assertEquals(listOf(SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS), sleepCalls)
    coVerify(exactly = 2) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun transientNetworkErrorsAreRetriedAndEventuallySucceed() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val sleepCalls = mutableListOf<Long>()
    service.sleeper = SpotifySearchSleeper { millis -> sleepCalls += millis }
    val result =
      SearchResult(
        SearchResultInternal(
          listOf(Track("1", "t", listOf(Artist("2", "a")), Album("3", "al", emptyList())))
        )
      )
    val exception = ResourceAccessException("timeout")
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } throws
      exception andThen
      result

    val searchResult = service.doSearch(Song("artist", "title"), "cid")

    assertEquals("1", searchResult?.tracks?.items?.firstOrNull()?.id)
    assertEquals(listOf(SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS), sleepCalls)
    coVerify(exactly = 2) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun repeatedNetworkErrorsSkipFailingTrack() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    val sleepCalls = mutableListOf<Long>()
    service.sleeper = SpotifySearchSleeper { millis -> sleepCalls += millis }
    val exception = ResourceAccessException("timeout")
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } throws
      exception andThenThrows
      exception andThenThrows
      exception

    val searchResult = service.doSearch(Song("artist", "title"), "cid")

    assertNull(searchResult)
    assertEquals(
      listOf(
        SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS,
        SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS * 2,
      ),
      sleepCalls,
    )
    coVerify(exactly = 3) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun repeatedServerErrorsSkipOnlyFailingTrack() {
    val rest = restService()
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
          listOf(
            Track(
              "ok",
              "good track",
              listOf(Artist("2", "good artist")),
              Album("3", "al", emptyList()),
            )
          )
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
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } throws
      exception andThenThrows
      exception andThenThrows
      exception andThen
      result

    val ids =
      service.doSearch(
        listOf(Song("good artist", "good track"), Song("good artist", "good track")),
        "cid",
      )

    assertEquals(listOf("ok"), ids)
    assertEquals(
      listOf(
        SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS,
        SpotifySearchService.SPOTIFY_SEARCH_RETRY_DELAY_MS * 2,
      ),
      sleepCalls,
    )
    coVerify(exactly = 4) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  @Test
  fun searchLimitIsSharedAcrossConcurrentBatches() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock(), 2)
    val active = AtomicInteger()
    val maximum = AtomicInteger()
    val started = CountDownLatch(2)
    val release = CountDownLatch(1)
    val result = matchingResults(1..8)
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } coAnswers
      {
        firstArg<Semaphore>().withPermit {
          runInterruptible(Dispatchers.IO) {
            val count = active.incrementAndGet()
            maximum.accumulateAndGet(count, ::maxOf)
            started.countDown()
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS))
              result
            } finally {
              active.decrementAndGet()
            }
          }
        }
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val first =
        executor.submit<List<String>> {
          service.doSearch((1..4).map { Song("artist-$it", "title-$it") }, "cid")
        }
      val second =
        executor.submit<List<String>> {
          service.doSearch((5..8).map { Song("artist-$it", "title-$it") }, "cid")
        }
      assertTrue(started.await(5, TimeUnit.SECONDS))
      release.countDown()
      assertEquals((1..4).map(Int::toString), first.get(5, TimeUnit.SECONDS))
      assertEquals((5..8).map(Int::toString), second.get(5, TimeUnit.SECONDS))
      assertEquals(2, maximum.get())
      assertEquals(8L, service.metrics().lookupAttempts)
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun completionOrderDoesNotChangeCandidateOrderOrDeduplication() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock(), 2)
    val entered = AtomicInteger()
    val laterRequestCompleted = CountDownLatch(1)
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } coAnswers
      {
        runInterruptible(Dispatchers.IO) {
          if (entered.incrementAndGet() == 1) {
            assertTrue(laterRequestCompleted.await(5, TimeUnit.SECONDS))
          } else {
            laterRequestCompleted.countDown()
          }
          matchingResults(1..4)
        }
      }
    val progress = AtomicInteger()
    val ids =
      service.doSearch(listOf(3, 1, 4, 2, 3).map { Song("artist-$it", "title-$it") }, "cid") {
        progress.incrementAndGet()
      }
    assertEquals(listOf("3", "1", "4", "2"), ids)
    assertEquals(5, progress.get())
  }

  @Test
  fun concurrentDuplicatesSharePersistentLookupAndUpstreamRequest() = runBlocking {
    val rest = restService()
    val lookups = AtomicInteger()
    val saves = AtomicInteger()
    val lookupStarted = CountDownLatch(1)
    val releaseLookup = CountDownLatch(1)
    val store =
      object : SpotifySearchCacheStore {
        override fun findByKey(cacheKey: String): StoredSpotifySearchCacheEntry? {
          lookups.incrementAndGet()
          lookupStarted.countDown()
          assertTrue(releaseLookup.await(5, TimeUnit.SECONDS))
          return null
        }

        override fun save(entry: StoredSpotifySearchCacheEntry): StoredSpotifySearchCacheEntry {
          saves.incrementAndGet()
          return entry
        }
      }
    val service = SpotifySearchService(rest, store, fixedClock())
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns
      matchingResults(1..1)
    val requests =
      List(8) {
        async(Dispatchers.IO) {
          service.searchTrackIds(listOf(Song(" artist-1 ", "title-1  ")), "cid")
        }
      }
    try {
      assertTrue(lookupStarted.await(5, TimeUnit.SECONDS))
      withTimeout(5000) { while (service.metrics().combinedLookups != 7L) delay(1) }
      releaseLookup.countDown()
      assertEquals(List(8) { listOf("1") }, requests.awaitAll())
      assertEquals(1, lookups.get())
      assertEquals(1, saves.get())
      coVerify(exactly = 1) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
    } finally {
      releaseLookup.countDown()
    }
  }

  @Test
  fun memoryResultIsAvailableBeforePersistentSaveFinishes() {
    val rest = restService()
    val saveStarted = CountDownLatch(1)
    val releaseSave = CountDownLatch(1)
    val store =
      object : SpotifySearchCacheStore {
        override fun findByKey(cacheKey: String): StoredSpotifySearchCacheEntry? = null

        override fun save(entry: StoredSpotifySearchCacheEntry): StoredSpotifySearchCacheEntry {
          saveStarted.countDown()
          assertTrue(releaseSave.await(5, TimeUnit.SECONDS))
          throw IllegalStateException("store unavailable")
        }
      }
    val service = SpotifySearchService(rest, store, fixedClock())
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } returns
      matchingResults(1..1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val owner =
        executor.submit<SearchResult?> { service.doSearch(Song("artist-1", "title-1"), "cid") }
      assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
      val follower =
        executor.submit<SearchResult?> { service.doSearch(Song("artist-1", "title-1"), "cid") }
      assertEquals("1", follower.get(2, TimeUnit.SECONDS)?.tracks?.items?.first()?.id)
      releaseSave.countDown()
      assertEquals("1", owner.get(5, TimeUnit.SECONDS)?.tracks?.items?.first()?.id)
      coVerify(exactly = 1) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
    } finally {
      releaseSave.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun cancellationStopsQueuedTracksAndReleasesPermitAndPendingLookup() = runBlocking {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock(), 1)
    val started = CountDownLatch(1)
    val neverReleased = CountDownLatch(1)
    val calls = AtomicInteger()
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } coAnswers
      {
        firstArg<Semaphore>().withPermit {
          runInterruptible(Dispatchers.IO) {
            if (calls.incrementAndGet() == 1) {
              started.countDown()
              neverReleased.await()
            }
            matchingResults(1..2)
          }
        }
      }
    val cancelled =
      async(Dispatchers.IO) {
        service.searchTrackIds((1..2).map { Song("artist-$it", "title-$it") }, "cid")
      }
    assertTrue(started.await(5, TimeUnit.SECONDS))
    withTimeout(5000) { cancelled.cancelAndJoin() }
    assertEquals(1, calls.get())
    val retried =
      withTimeout(5000) { service.searchTrackIds(listOf(Song("artist-1", "title-1")), "cid") }
    assertEquals(listOf("1"), retried)
    assertEquals(2, calls.get())
  }

  @Test
  fun cancellationOfLookupOwnerDoesNotCancelIndependentFollower() = runBlocking {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock(), 2)
    val started = CountDownLatch(1)
    val neverReleased = CountDownLatch(1)
    val calls = AtomicInteger()
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } coAnswers
      {
        firstArg<Semaphore>().withPermit {
          runInterruptible(Dispatchers.IO) {
            if (calls.incrementAndGet() == 1) {
              started.countDown()
              neverReleased.await()
            }
            matchingResults(1..1)
          }
        }
      }
    val song = listOf(Song("artist-1", "title-1"))
    val owner = async(Dispatchers.IO) { service.searchTrackIds(song, "cid") }
    assertTrue(started.await(5, TimeUnit.SECONDS))
    val follower = async(Dispatchers.IO) { service.searchTrackIds(song, "cid") }
    withTimeout(5000) {
      while (service.metrics().combinedLookups == 0L) delay(1)
      owner.cancelAndJoin()
      assertEquals(listOf("1"), follower.await())
    }
    assertEquals(2, calls.get())
  }

  @Test
  fun concurrentClientsDoNotSharePendingResults() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock(), 2)
    val bothStarted = CountDownLatch(2)
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } coAnswers
      {
        runInterruptible(Dispatchers.IO) {
          bothStarted.countDown()
          assertTrue(bothStarted.await(5, TimeUnit.SECONDS))
          matchingResults(1..1)
        }
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val first =
        executor.submit<SearchResult?> { service.doSearch(Song("artist-1", "title-1"), "first") }
      val second =
        executor.submit<SearchResult?> { service.doSearch(Song("artist-1", "title-1"), "second") }
      assertEquals("1", first.get(5, TimeUnit.SECONDS)?.tracks?.items?.first()?.id)
      assertEquals("1", second.get(5, TimeUnit.SECONDS)?.tracks?.items?.first()?.id)
      assertEquals(0L, service.metrics().combinedLookups)
      coVerify(exactly = 2) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun cachedSearchDoesNotWaitForNetworkPermit() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock(), 1)
    val calls = AtomicInteger()
    val blocked = CountDownLatch(1)
    val release = CountDownLatch(1)
    coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } coAnswers
      {
        firstArg<Semaphore>().withPermit {
          runInterruptible(Dispatchers.IO) {
            if (calls.incrementAndGet() > 1) {
              blocked.countDown()
              assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            matchingResults(1..2)
          }
        }
      }
    service.doSearch(Song("artist-1", "title-1"), "cid")
    val executor = Executors.newFixedThreadPool(2)
    try {
      val network =
        executor.submit<SearchResult?> { service.doSearch(Song("artist-2", "title-2"), "cid") }
      assertTrue(blocked.await(5, TimeUnit.SECONDS))
      val cached =
        executor.submit<SearchResult?> { service.doSearch(Song("artist-1", "title-1"), "cid") }
      assertNotNull(cached.get(2, TimeUnit.SECONDS))
      release.countDown()
      assertNotNull(network.get(5, TimeUnit.SECONDS))
      assertEquals(1L, service.metrics().cacheHits)
      assertEquals(2, calls.get())
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun failedSharedLookupIsPropagatedAndLaterRetried() = runBlocking {
    supervisorScope {
      val rest = restService()
      val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock(), 2)
      val started = CountDownLatch(1)
      val releaseFailure = CountDownLatch(1)
      val attempts = AtomicInteger()
      val failure =
        HttpClientErrorException.create(
          HttpStatus.BAD_REQUEST,
          "invalid",
          HttpHeaders(),
          byteArrayOf(),
          null,
        )
      coEvery { rest.doRequestSuspending(any(), any(), any<() -> Any>()) } coAnswers
        {
          runInterruptible(Dispatchers.IO) {
            if (attempts.incrementAndGet() == 1) {
              started.countDown()
              assertTrue(releaseFailure.await(5, TimeUnit.SECONDS))
              throw failure
            }
            matchingResults(1..1)
          }
        }
      val songs = listOf(Song("artist-1", "title-1"))
      val owner = async(Dispatchers.IO) { service.searchTrackIds(songs, "cid") }
      assertTrue(started.await(5, TimeUnit.SECONDS))
      val follower = async(Dispatchers.IO) { service.searchTrackIds(songs, "cid") }
      try {
        withTimeout(5000) {
          while (service.metrics().combinedLookups == 0L) delay(1)
          releaseFailure.countDown()
          assertTrue(
            runCatching { owner.await() }.exceptionOrNull() is HttpClientErrorException.BadRequest
          )
          assertTrue(
            runCatching { follower.await() }.exceptionOrNull()
              is HttpClientErrorException.BadRequest
          )
          assertEquals(listOf("1"), service.searchTrackIds(songs, "cid"))
        }
        assertEquals(2, attempts.get())
      } finally {
        releaseFailure.countDown()
      }
    }
  }

  @Test
  fun emptyBatchMakesNoRequests() {
    val rest = restService()
    val service = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), fixedClock())
    assertEquals(emptyList<String>(), service.doSearch(emptyList(), "cid"))
    coVerify(exactly = 0) { rest.doRequestSuspending(any(), any(), any<() -> Any>()) }
  }

  private fun restService(): SpotifyRestService = mockk {
    every { requestMetrics() } returns SpotifyRequestMetrics(0L, 0L, 0L)
  }

  private fun matchingResults(ids: IntRange): SearchResult =
    SearchResult(
      SearchResultInternal(
        ids.map {
          Track(
            it.toString(),
            "title-$it",
            listOf(Artist("artist-$it", "artist-$it")),
            Album("album-$it", "album-$it", emptyList()),
          )
        }
      )
    )

  private fun fixedClock(instant: String = "2026-04-08T10:00:00Z"): Clock {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
  }

  private class ThrowingSpotifySearchCacheStore : SpotifySearchCacheStore {
    override fun save(entry: StoredSpotifySearchCacheEntry): StoredSpotifySearchCacheEntry {
      throw RuntimeException("persistent cache save unavailable")
    }

    override fun findByKey(cacheKey: String): StoredSpotifySearchCacheEntry? {
      throw RuntimeException("persistent cache read unavailable")
    }
  }

  private class CorruptSpotifySearchCacheStore(private val now: Instant) : SpotifySearchCacheStore {
    var savedEntry: StoredSpotifySearchCacheEntry? = null

    override fun save(entry: StoredSpotifySearchCacheEntry): StoredSpotifySearchCacheEntry {
      savedEntry = entry
      return entry
    }

    override fun findByKey(cacheKey: String): StoredSpotifySearchCacheEntry? {
      return savedEntry
        ?: StoredSpotifySearchCacheEntry(
          cacheKey = cacheKey,
          clientId = "cid",
          query = "track:title artist:artist",
          payloadJson = "{not-json",
          updatedAt = now,
          expiresAt = now.plus(Duration.ofDays(7)),
        )
    }
  }
}
