package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
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
    every { lastFmService.yearlyChartlist("cid", any(), "login", Int.MAX_VALUE) } answers
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

    every { lastFmService.yearlyChartlist("cid", 2024, "login", Int.MAX_VALUE) } returns
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
  fun buildPrivateMoodSongStatsCapturesNightAndRecencySignals() {
    val service =
      SpotifyTopPlaylistsService(
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
      )

    val stats =
      service
        .buildPrivateMoodSongStats(
          scrobbles =
            listOf(
              Song("Artist A", "Night Song", playedAtEpochSecond = 1_730_242_800),
              Song("Artist A", "Night Song", playedAtEpochSecond = 1_730_248_200),
              Song("Artist A", "Night Song", playedAtEpochSecond = 1_731_626_100),
              Song("Artist A", "Night Song", playedAtEpochSecond = 1_731_632_400),
            ),
          nowEpochSecond = 1_730_500_000,
        )
        .single()

    assertEquals(4, stats.totalPlays)
    assertEquals(4, stats.recentPlays30d)
    assertTrue(stats.recencySpikeRatio > 1.0)
    assertTrue(stats.nightPlayRatio >= 0.5)
    assertFalse(stats.hourHistogram.all { it == 0 })
  }

  @Test
  fun updatePrivateMoodTaxonomyPlaylistsCreatesPrivatePlaylists() {
    val playlistService = mockk<SpotifyPlaylistService>()
    val trackService = mockk<SpotifyTopTrackService>()
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()
    val service =
      SpotifyTopPlaylistsService(playlistService, trackService, lastFmService, searchService)

    service.firstSupportedYear = 2023
    service.currentYearProvider = { 2024 }
    service.nowEpochSecondProvider = { 1_730_500_000 }

    every {
      playlistService.hasRequiredScopes(
        "cid",
        setOf("playlist-modify-private", "playlist-read-private"),
      )
    } returns true
    every { trackService.getTopTracksShortTerm("cid") } returns
      listOf(track("short-1", "Surge Song", "Artist Surge"))
    every { trackService.getTopTracksMidTerm("cid") } returns
      listOf(track("mid-1", "Anchor Song", "Artist Anchor"))
    every { trackService.getTopTracksLongTerm("cid") } returns
      listOf(track("long-1", "Anchor Song", "Artist Anchor"))
    every { lastFmService.yearlyChartlist("cid", 2024, "login", Int.MAX_VALUE) } returns
      listOf(
        Song("Artist Anchor", "Anchor Song", 1_720_000_000),
        Song("Artist Anchor", "Anchor Song", 1_728_000_000),
        Song("Artist Surge", "Surge Song", 1_730_300_000),
        Song("Artist Surge", "Surge Song", 1_730_320_000),
        Song("Artist Surge", "Surge Song", 1_730_340_000),
        Song("Artist Night", "Night Song", 1_730_347_200),
        Song("Artist Night", "Night Song", 1_730_350_800),
        Song("Artist Night", "Night Song", 1_730_354_400),
        Song("Artist Fresh", "Fresh Cut", 1_730_280_000),
      )
    every { lastFmService.yearlyChartlist("cid", 2023, "login", Int.MAX_VALUE) } returns
      listOf(
        Song("Artist Anchor", "Anchor Song", 1_690_000_000),
        Song("Artist Anchor", "Anchor Song", 1_690_100_000),
        Song("Artist Surge", "Surge Song", 1_690_200_000),
      )
    every { lastFmService.artistSimilar(any(), any()) } returns
      listOf(LastFmSimilarArtist("Artist Fresh", 0.7))
    every { lastFmService.trackSimilar(any(), any(), any()) } returns
      listOf(LastFmSimilarTrack(Song("Artist Frontier", "Outer Edge"), 0.8))
    coEvery { searchService.searchTrackIds(any(), "cid") } answers
      {
        firstArg<List<Song>>()
          .mapNotNull { song ->
            when (song.title) {
              "Anchor Song" -> "anchor-track"
              "Surge Song" -> "surge-track"
              "Night Song" -> "night-track"
              "Outer Edge" -> "frontier-track"
              "Fresh Cut" -> "fresh-track"
              else -> null
            }
          }
          .distinct()
      }
    every { playlistService.getCurrentUserPlaylists("cid") } returns mutableListOf()
    every { playlistService.createPlaylist(any(), "cid", false) } answers
      {
        Playlist(id = thirdArg<Boolean>().toString() + ":" + firstArg<String>(), name = firstArg())
      }
    every { playlistService.modifyPlaylist(any(), any(), "cid") } returns emptyMap()

    val result = service.updatePrivateMoodTaxonomyPlaylists("cid", "login")

    assertEquals(4, result.playlists.size)
    assertEquals(
      listOf("Anchor", "Surge", "Night Drift", "Frontier"),
      result.playlists.map { it.label },
    )
    assertTrue(result.playlists.all { it.playlistId.isNotBlank() })
    verify(exactly = 4) {
      playlistService.createPlaylist(match { it.startsWith("Private Mood -") }, "cid", false)
    }
    verify(exactly = 4) { playlistService.modifyPlaylist(any(), any(), "cid") }
  }

  @Test
  fun updatePrivateMoodTaxonomyPlaylistsRequiresPrivateScopes() {
    val playlistService = mockk<SpotifyPlaylistService>()
    val service =
      SpotifyTopPlaylistsService(
        playlistService,
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
      )

    every { playlistService.hasRequiredScopes("cid", any()) } returns false

    assertThrows(AuthenticationRequiredException::class.java) {
      service.updatePrivateMoodTaxonomyPlaylists("cid", "login")
    }
  }

  private fun track(id: String, title: String, artist: String): Track {
    return Track(
      id,
      title,
      listOf(com.lis.spotify.domain.Artist("$id-artist", artist)),
      Album("a", "n", emptyList()),
    )
  }
}
