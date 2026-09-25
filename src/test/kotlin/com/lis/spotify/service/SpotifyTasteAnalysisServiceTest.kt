package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpotifyTasteAnalysisServiceTest {
  @Test
  fun snapshotCombinesAllSpotifyTimeRanges() {
    val artists = mockk<SpotifyTopArtistService>()
    val tracks = mockk<SpotifyTopTrackService>()
    val service = SpotifyTasteAnalysisService(artists, tracks)
    val artist = Artist("artist-1", "Artist", listOf("metal", "industrial"))
    val track =
      Track(
        "track-1",
        "Track",
        listOf(Artist("artist-1", "Artist")),
        Album("album-1", "Album", listOf(Artist("artist-1", "Artist"))),
      )

    listOf("short_term", "medium_term", "long_term").forEach { range ->
      every { artists.getTopArtists("session_test", range, 50) } returns listOf(artist)
      every { tracks.getTopTracks("session_test", range, 50) } returns listOf(track)
    }

    val result = service.snapshot("session_test")

    assertEquals(
      SpotifyTasteArtist("artist-1", "Artist", listOf("metal", "industrial")),
      result.shortTerm.artists.single(),
    )
    assertEquals(
      SpotifyTasteTrack(
        id = "track-1",
        name = "Track",
        artists = listOf(SpotifyTasteArtistRef("artist-1", "Artist")),
        albumId = "album-1",
        albumName = "Album",
      ),
      result.longTerm.tracks.single(),
    )
    verify(exactly = 1) { artists.getTopArtists("session_test", "short_term", 50) }
    verify(exactly = 1) { artists.getTopArtists("session_test", "medium_term", 50) }
    verify(exactly = 1) { artists.getTopArtists("session_test", "long_term", 50) }
    verify(exactly = 1) { tracks.getTopTracks("session_test", "short_term", 50) }
    verify(exactly = 1) { tracks.getTopTracks("session_test", "medium_term", 50) }
    verify(exactly = 1) { tracks.getTopTracks("session_test", "long_term", 50) }
  }
}
