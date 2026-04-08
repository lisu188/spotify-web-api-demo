package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
}
