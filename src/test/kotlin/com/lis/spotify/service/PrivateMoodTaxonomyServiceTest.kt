package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivateMoodTaxonomyServiceTest {
  @Test
  fun buildPrivateMoodSongStatsCapturesNightAndRecencySignals() {
    val service =
      PrivateMoodTaxonomyService(
        mockk(relaxed = true),
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
  fun analyzePrivateMoodLyricsSeparatesHappyAndSadSignals() {
    val service =
      PrivateMoodTaxonomyService(
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
      )

    val happyProfile =
      service.analyzePrivateMoodLyrics(
        "We dance in the sunshine, smiling, feeling alive all night long"
      )
    val sadProfile =
      service.analyzePrivateMoodLyrics(
        "Lonely tears and a broken heart, I cry in the dark without you"
      )

    assertTrue(happyProfile.happyScore > happyProfile.sadScore)
    assertTrue(sadProfile.sadScore > sadProfile.happyScore)
  }

  @Test
  fun rerankPrivateMoodCandidatesByLyricsPrefersMatchingLyrics() {
    val service =
      PrivateMoodTaxonomyService(
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
      )

    val happySong = Song("Artist Happy", "Happy Song")
    val sadSong = Song("Artist Sad", "Sad Song")
    val candidates =
      listOf(
        PrivateMoodTaxonomyService.PrivateMoodCandidateSong(
          song = happySong,
          normalizedKey = "artist happy" to "happy song",
          score = 120.0,
        ),
        PrivateMoodTaxonomyService.PrivateMoodCandidateSong(
          song = sadSong,
          normalizedKey = "artist sad" to "sad song",
          score = 80.0,
        ),
      )

    val lyricProfiles =
      mapOf(
        happySong.artist.lowercase() to
          happySong.title.lowercase() to
          service.analyzePrivateMoodLyrics(
            "We dance in the sunshine, smiling, feeling alive and free"
          ),
        sadSong.artist.lowercase() to
          sadSong.title.lowercase() to
          service.analyzePrivateMoodLyrics(
            "Lonely tears and a broken heart, I cry in the dark without you"
          ),
      )

    val sadRanked =
      service.rerankPrivateMoodCandidatesByLyrics(
        PrivateMoodTaxonomyService.PrivateMoodPlaylistKind.SAD,
        candidates,
        lyricProfiles,
      )
    val happyRanked =
      service.rerankPrivateMoodCandidatesByLyrics(
        PrivateMoodTaxonomyService.PrivateMoodPlaylistKind.HAPPY,
        candidates,
        lyricProfiles,
      )

    assertEquals("Sad Song", sadRanked.first().song.title)
    assertEquals("Happy Song", happyRanked.first().song.title)
  }

  @Test
  fun updatePrivateMoodTaxonomyPlaylistsCreatesPrivatePlaylists() {
    val playlistService = mockk<SpotifyPlaylistService>()
    val trackService = mockk<SpotifyTopTrackService>()
    val lastFmService = mockk<LastFmService>()
    val searchService = mockk<SpotifySearchService>()
    val service =
      PrivateMoodTaxonomyService(
        playlistService,
        trackService,
        lastFmService,
        searchService,
        mockk(relaxed = true),
      )

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
      listOf(
        track("short-1", "Surge Song", "Artist Surge"),
        track("short-2", "Happy Song", "Artist Happy"),
      )
    every { trackService.getTopTracksMidTerm("cid") } returns
      listOf(
        track("mid-1", "Anchor Song", "Artist Anchor"),
        track("mid-2", "Sad Song", "Artist Sad"),
      )
    every { trackService.getTopTracksLongTerm("cid") } returns
      listOf(
        track("long-1", "Anchor Song", "Artist Anchor"),
        track("long-2", "Sad Song", "Artist Sad"),
      )
    every { lastFmService.yearlyChartlist("cid", 2024, "login", Int.MAX_VALUE) } returns
      listOf(
        Song("Artist Anchor", "Anchor Song", 1_720_000_000),
        Song("Artist Anchor", "Anchor Song", 1_728_000_000),
        Song("Artist Surge", "Surge Song", 1_730_300_000),
        Song("Artist Surge", "Surge Song", 1_730_320_000),
        Song("Artist Surge", "Surge Song", 1_730_340_000),
        Song("Artist Happy", "Happy Song", 1_728_554_400),
        Song("Artist Happy", "Happy Song", 1_728_741_600),
        Song("Artist Happy", "Happy Song", 1_728_835_200),
        Song("Artist Sad", "Sad Song", 1_728_689_400),
        Song("Artist Sad", "Sad Song", 1_728_693_000),
        Song("Artist Sad", "Sad Song", 1_728_696_600),
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
        Song("Artist Sad", "Sad Song", 1_697_067_900),
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
              "Happy Song" -> "happy-track"
              "Sad Song" -> "sad-track"
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

    assertEquals(6, result.playlists.size)
    assertEquals(
      listOf("Anchor", "Happy", "Sad", "Surge", "Night Drift", "Frontier"),
      result.playlists.map { it.label },
    )
    assertTrue(result.playlists.all { it.playlistId.isNotBlank() })
    assertTrue(result.playlists.associateBy { it.label }.getValue("Happy").trackCount > 0)
    assertTrue(result.playlists.associateBy { it.label }.getValue("Sad").trackCount > 0)
    verify(exactly = 6) {
      playlistService.createPlaylist(match { it.startsWith("Private Mood -") }, "cid", false)
    }
    verify(exactly = 6) { playlistService.modifyPlaylist(any(), any(), "cid") }
  }

  @Test
  fun updatePrivateMoodTaxonomyPlaylistsRequiresPrivateScopes() {
    val playlistService = mockk<SpotifyPlaylistService>()
    val service =
      PrivateMoodTaxonomyService(
        playlistService,
        mockk(relaxed = true),
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
    return Track(id, title, listOf(Artist("$id-artist", artist)), Album("a", "n", emptyList()))
  }
}
