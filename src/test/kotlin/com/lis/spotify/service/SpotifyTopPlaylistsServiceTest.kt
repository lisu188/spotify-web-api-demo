package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.SearchResultInternal
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import com.lis.spotify.persistence.InMemorySpotifySearchCacheStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpotifyTopPlaylistsServiceTest {
  @Test
  fun serviceInstantiates() {
    val service =
      SpotifyTopPlaylistsService(
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
      )
    assertNotNull(service)
  }

  @Test
  fun updateTopPlaylistsReturnsIds() {
    val playlistService = mockk<SpotifyPlaylistService>()
    val trackService = mockk<SpotifyTopTrackService>()
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()

    every { trackService.getTopTracksShortTerm(any()) } returns
      listOf(Track("1", "t", emptyList(), Album("a", "n", emptyList())))
    every { trackService.getTopTracksMidTerm(any()) } returns
      listOf(Track("2", "t", emptyList(), Album("a", "n", emptyList())))
    every { trackService.getTopTracksLongTerm(any()) } returns
      listOf(Track("3", "t", emptyList(), Album("a", "n", emptyList())))
    every { playlistService.getOrCreatePlaylist(any(), any()) } returns Playlist("id", "n")
    every { playlistService.modifyPlaylist(any(), any(), any()) } returns emptyMap()

    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)
    val ids = service.updateTopPlaylists("cid")
    assertEquals(4, ids.size)
    verify(exactly = 4) { playlistService.modifyPlaylist(any(), any(), any()) }
  }

  @Test
  fun noTracksSkipsPlaylistCreation() {
    val playlistService = mockk<SpotifyPlaylistService>(relaxed = true)
    val trackService = mockk<SpotifyTopTrackService>()
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()

    every { trackService.getTopTracksShortTerm(any()) } returns emptyList()
    every { trackService.getTopTracksMidTerm(any()) } returns emptyList()
    every { trackService.getTopTracksLongTerm(any()) } returns emptyList()

    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)
    val ids = service.updateTopPlaylists("cid")
    assertEquals(emptyList<String>(), ids)
    verify(exactly = 0) { playlistService.getOrCreatePlaylist(any(), any()) }
    verify(exactly = 0) { playlistService.modifyPlaylist(any(), any(), any()) }
  }

  @Test
  fun updateYearlyPlaylistsReportsProgress() {
    val playlistService = mockk<SpotifyPlaylistService>(relaxed = true)
    val trackService = mockk<SpotifyTopTrackService>(relaxed = true)
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>(relaxed = true)
    val progressUpdates = Collections.synchronizedList(mutableListOf<Int>())

    every { lastFmService.yearlyChartlist(any(), any(), any(), any()) } returns emptyList()

    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)

    service.updateYearlyPlaylists("cid", "login") { progressPercent, _ ->
      progressUpdates += progressPercent
    }

    assertTrue(progressUpdates.isNotEmpty())
    assertEquals(0, progressUpdates.first())
    assertEquals(100, progressUpdates.last())
  }

  @Test
  fun updateYearlyPlaylistsSearchesTracksSequentiallyWithinYear() {
    val playlistService = mockk<SpotifyPlaylistService>(relaxed = true)
    val trackService = mockk<SpotifyTopTrackService>(relaxed = true)
    val lastFmService = mockk<LastFmService>()
    val restService = mockk<SpotifyRestService>()
    val activeSearches = AtomicInteger()
    val maxActiveSearches = AtomicInteger()
    val songs = (1..20).map { Song("Artist $it", "Title $it") }

    every { lastFmService.yearlyChartlist("cid", 2024, "login", any()) } returns songs
    every { playlistService.getCurrentUserPlaylists("cid") } returns mutableListOf()
    every { playlistService.createPlaylist("LAST.FM 2024", "cid", true) } returns
      Playlist("playlist-2024", "LAST.FM 2024")
    every { restService.doRequest(any<() -> Any>()) } answers
      {
        val active = activeSearches.incrementAndGet()
        maxActiveSearches.accumulateAndGet(active, ::maxOf)
        Thread.sleep(10)
        activeSearches.decrementAndGet()
        SearchResult(
          SearchResultInternal(
            listOf(
              Track(
                "track-$active",
                "Title",
                listOf(Artist("artist", "Artist")),
                Album("album", "Album", emptyList()),
              )
            )
          )
        )
      }

    val searchService =
      SpotifySearchService(restService, InMemorySpotifySearchCacheStore(), fixedClock())
    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)
    service.firstSupportedYear = 2024
    service.currentYearProvider = { 2024 }

    service.updateYearlyPlaylists("cid", "login")

    assertEquals(1, maxActiveSearches.get())
    verify(exactly = songs.size) { restService.doRequest(any<() -> Any>()) }
  }

  @Test
  fun updateYearlyPlaylistsRunsYearsInParallelWithoutStartingEveryYearAtOnce() {
    val playlistService = mockk<SpotifyPlaylistService>(relaxed = true)
    val trackService = mockk<SpotifyTopTrackService>(relaxed = true)
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>(relaxed = true)
    val progressUpdates = Collections.synchronizedList(mutableListOf<Int>())
    val activeYears = AtomicInteger()
    val maxActiveYears = AtomicInteger()
    val startedYears = CountDownLatch(2)
    val releaseYears = CountDownLatch(1)

    every { lastFmService.yearlyChartlist(any(), any(), any(), any()) } answers
      {
        val active = activeYears.incrementAndGet()
        maxActiveYears.accumulateAndGet(active, ::maxOf)
        startedYears.countDown()
        startedYears.await(2, TimeUnit.SECONDS)
        releaseYears.await(2, TimeUnit.SECONDS)
        activeYears.decrementAndGet()
        emptyList()
      }

    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)
    service.firstSupportedYear = 2005
    service.currentYearProvider = { 2008 }
    service.yearlyParallelism = 2

    val executor = Executors.newSingleThreadExecutor()
    try {
      val future =
        executor.submit<Unit> {
          service.updateYearlyPlaylists("cid", "login") { progressPercent, _ ->
            progressUpdates += progressPercent
          }
        }

      assertTrue(startedYears.await(2, TimeUnit.SECONDS))
      releaseYears.countDown()
      future.get(5, TimeUnit.SECONDS)
    } finally {
      releaseYears.countDown()
      executor.shutdownNow()
    }

    assertTrue(progressUpdates.isNotEmpty())
    assertEquals(0, progressUpdates.first())
    assertEquals(100, progressUpdates.last())
    assertTrue(
      progressUpdates.zipWithNext().all { (before, after) -> before <= after },
      "Expected progress updates to remain monotonic",
    )
    assertEquals(service.yearlyParallelism, maxActiveYears.get())
    verify(exactly = 4) { lastFmService.yearlyChartlist("cid", any(), "login", any()) }
    verify(exactly = 0) { playlistService.getCurrentUserPlaylists(any()) }
  }

  @Test
  fun selectForgottenObsessionsFiltersRecentSongsAndRanksDormantFavorites() {
    val service =
      SpotifyTopPlaylistsService(
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
      )

    val selected =
      service.selectForgottenObsessions(
        scrobbles =
          listOf(
            Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_680_000_000),
            Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_681_000_000),
            Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_682_000_000),
            Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_683_000_000),
            Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_684_000_000),
            Song("Artist B", "Dormant Anthem", playedAtEpochSecond = 1_500_000_000),
            Song("Artist B", "Dormant Anthem", playedAtEpochSecond = 1_500_100_000),
            Song("Artist B", "Dormant Anthem", playedAtEpochSecond = 1_500_200_000),
            Song("Artist B", "Dormant Anthem", playedAtEpochSecond = 1_500_300_000),
            Song("Artist B", "Dormant Anthem", playedAtEpochSecond = 1_500_400_000),
            Song("Artist B", "Dormant Anthem", playedAtEpochSecond = 1_500_500_000),
            Song("Artist C", "Still In Rotation", playedAtEpochSecond = 1_720_000_000),
            Song("Artist C", "Still In Rotation", playedAtEpochSecond = 1_720_010_000),
            Song("Artist C", "Still In Rotation", playedAtEpochSecond = 1_720_020_000),
            Song("Artist C", "Still In Rotation", playedAtEpochSecond = 1_720_030_000),
            Song("Artist C", "Still In Rotation", playedAtEpochSecond = 1_720_040_000),
          ),
        nowEpochSecond = 1_730_000_000,
      )

    assertEquals(
      listOf(Song("Artist B", "Dormant Anthem"), Song("Artist A", "Old Favorite")),
      selected,
    )
  }

  @Test
  fun updateForgottenObsessionsPlaylistCreatesPlaylistFromDormantFavorites() {
    val playlistService = mockk<SpotifyPlaylistService>(relaxed = true)
    val trackService = mockk<SpotifyTopTrackService>(relaxed = true)
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()
    val playlist = Playlist("forgotten-id", "Forgotten Obsessions")
    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)

    service.firstSupportedYear = 2024
    service.currentYearProvider = { 2025 }
    every {
      lastFmService.yearlyChartlist(
        "cid",
        any(),
        "login",
        SpotifyTopPlaylistsService.FORGOTTEN_OBSESSIONS_YEARLY_SCROBBLE_LIMIT,
      )
    } answers
      {
        when (secondArg<Int>()) {
          2025 ->
            listOf(
              Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_680_000_000),
              Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_681_000_000),
              Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_682_000_000),
            )
          else ->
            listOf(
              Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_600_000_000),
              Song("Artist A", "Old Favorite", playedAtEpochSecond = 1_601_000_000),
            )
        }
      }
    coEvery {
      searchService.searchTrackIds(listOf(Song("Artist A", "Old Favorite")), "cid")
    } returns listOf("track-1")
    every { playlistService.getCurrentUserPlaylists("cid") } returns mutableListOf()
    every { playlistService.createPlaylist("Forgotten Obsessions", "cid") } returns playlist
    every { playlistService.modifyPlaylist("forgotten-id", listOf("track-1"), "cid") } returns
      emptyMap()

    val result = service.updateForgottenObsessionsPlaylist("cid", "login")

    assertEquals("forgotten-id", result.playlistId)
    assertEquals(1, result.playlistTrackCount)
    assertEquals(1, result.spotifyMatchCount)
    assertEquals(1, result.candidateCount)
  }

  @Test
  fun updateForgottenObsessionsPlaylistStopsSearchingAfterPlaylistIsFull() {
    val playlistService = mockk<SpotifyPlaylistService>(relaxed = true)
    val trackService = mockk<SpotifyTopTrackService>(relaxed = true)
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()
    val playlist = Playlist("forgotten-id", "Forgotten Obsessions")
    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)
    val progressMessages = mutableListOf<String>()

    service.firstSupportedYear = 2024
    service.currentYearProvider = { 2024 }

    every {
      lastFmService.yearlyChartlist(
        "cid",
        2024,
        "login",
        SpotifyTopPlaylistsService.FORGOTTEN_OBSESSIONS_YEARLY_SCROBBLE_LIMIT,
      )
    } returns
      (1..110).flatMap { index ->
        (1..5).map { play ->
          Song(
            artist = "Artist $index",
            title = "Song $index",
            playedAtEpochSecond = 1_500_000_000L + play,
          )
        }
      }
    coEvery { searchService.searchTrackIds(any(), "cid") } returns (1..50).map { "track-$it" }
    every { playlistService.getCurrentUserPlaylists("cid") } returns mutableListOf()
    every { playlistService.createPlaylist("Forgotten Obsessions", "cid") } returns playlist
    every {
      playlistService.modifyPlaylist("forgotten-id", (1..50).map { "track-$it" }, "cid")
    } returns emptyMap()

    val result =
      service.updateForgottenObsessionsPlaylist("cid", "login") { _, message ->
        progressMessages += message
      }

    assertEquals("forgotten-id", result.playlistId)
    assertEquals(50, result.playlistTrackCount)
    assertEquals(50, result.spotifyMatchCount)
    assertEquals(110, result.candidateCount)
    assertTrue(progressMessages.contains("Matching forgotten obsessions on Spotify (1/2)"))
    coVerify(exactly = 1) { searchService.searchTrackIds(match { it.size == 100 }, "cid") }
  }

  @Test
  fun updateForgottenObsessionsPlaylistCapsHistoryAndSpotifyCandidateWork() {
    val playlistService = mockk<SpotifyPlaylistService>(relaxed = true)
    val trackService = mockk<SpotifyTopTrackService>(relaxed = true)
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()
    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)

    service.firstSupportedYear = 2024
    service.currentYearProvider = { 2024 }

    every {
      lastFmService.yearlyChartlist(
        "cid",
        2024,
        "login",
        SpotifyTopPlaylistsService.FORGOTTEN_OBSESSIONS_YEARLY_SCROBBLE_LIMIT,
      )
    } returns
      (1..600).flatMap { index ->
        (1..5).map { play ->
          Song(
            artist = "Artist $index",
            title = "Song $index",
            playedAtEpochSecond = 1_500_000_000L + play,
          )
        }
      }
    coEvery { searchService.searchTrackIds(any(), "cid") } returns emptyList()

    val result = service.updateForgottenObsessionsPlaylist("cid", "login")

    assertEquals(null, result.playlistId)
    assertEquals(0, result.playlistTrackCount)
    assertEquals(0, result.spotifyMatchCount)
    assertEquals(
      SpotifyTopPlaylistsService.FORGOTTEN_OBSESSIONS_CANDIDATE_LIMIT,
      result.candidateCount,
    )
    verify(exactly = 1) {
      lastFmService.yearlyChartlist(
        "cid",
        2024,
        "login",
        SpotifyTopPlaylistsService.FORGOTTEN_OBSESSIONS_YEARLY_SCROBBLE_LIMIT,
      )
    }
    coVerify(exactly = 5) { searchService.searchTrackIds(match { it.size == 100 }, "cid") }
    verify(exactly = 0) { playlistService.getCurrentUserPlaylists(any()) }
    verify(exactly = 0) { playlistService.modifyPlaylist(any(), any(), any()) }
  }

  @Test
  fun updateForgottenObsessionsPlaylistCountsUniqueSpotifyMatchesAcrossBatches() {
    val playlistService = mockk<SpotifyPlaylistService>(relaxed = true)
    val trackService = mockk<SpotifyTopTrackService>(relaxed = true)
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()
    val playlist = Playlist("forgotten-id", "Forgotten Obsessions")
    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)

    service.firstSupportedYear = 2024
    service.currentYearProvider = { 2024 }

    every {
      lastFmService.yearlyChartlist(
        "cid",
        2024,
        "login",
        SpotifyTopPlaylistsService.FORGOTTEN_OBSESSIONS_YEARLY_SCROBBLE_LIMIT,
      )
    } returns
      (1..150).flatMap { index ->
        (1..5).map { play ->
          Song(
            artist = "Artist $index",
            title = "Song $index",
            playedAtEpochSecond = 1_500_000_000L + play,
          )
        }
      }
    // Two search batches that overlap on track-21..30, so 30 + 30 raw matches are only 50 unique.
    coEvery { searchService.searchTrackIds(any(), "cid") } returnsMany
      listOf((1..30).map { "track-$it" }, (21..50).map { "track-$it" })
    every { playlistService.getCurrentUserPlaylists("cid") } returns mutableListOf()
    every { playlistService.createPlaylist("Forgotten Obsessions", "cid") } returns playlist
    every { playlistService.modifyPlaylist(any(), any(), "cid") } returns emptyMap()

    val result = service.updateForgottenObsessionsPlaylist("cid", "login")

    assertEquals("forgotten-id", result.playlistId)
    assertEquals(50, result.playlistTrackCount)
    assertEquals(50, result.spotifyMatchCount)
  }

  private fun fixedClock(instant: String = "2026-04-08T10:00:00Z"): Clock {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
  }
}
