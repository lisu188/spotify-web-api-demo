package com.lis.spotify.service

import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.Track
import org.springframework.stereotype.Service

@Service
class SpotifyTasteAnalysisService(
  private val spotifyTopArtistService: SpotifyTopArtistService,
  private val spotifyTopTrackService: SpotifyTopTrackService,
) {
  fun snapshot(clientId: String, limit: Int = SpotifyTopArtistService.MAX_LIMIT): SpotifyTasteSnapshot {
    require(limit in 1..SpotifyTopArtistService.MAX_LIMIT) {
      "limit must be between 1 and ${SpotifyTopArtistService.MAX_LIMIT}"
    }
    return SpotifyTasteSnapshot(
      shortTerm = window(clientId, SHORT_TERM, limit),
      mediumTerm = window(clientId, MEDIUM_TERM, limit),
      longTerm = window(clientId, LONG_TERM, limit),
    )
  }

  private fun window(clientId: String, timeRange: String, limit: Int): SpotifyTasteWindow {
    return SpotifyTasteWindow(
      artists =
        spotifyTopArtistService.getTopArtists(clientId, timeRange, limit).map { it.toTasteArtist() },
      tracks =
        spotifyTopTrackService.getTopTracks(clientId, timeRange, limit).map { it.toTasteTrack() },
    )
  }

  private fun Artist.toTasteArtist(): SpotifyTasteArtist {
    return SpotifyTasteArtist(id = id, name = name, genres = genres)
  }

  private fun Track.toTasteTrack(): SpotifyTasteTrack {
    return SpotifyTasteTrack(
      id = id,
      name = name,
      artists = artists.map { SpotifyTasteArtistRef(it.id, it.name) },
      albumId = album.id,
      albumName = album.name,
    )
  }

  companion object {
    private const val SHORT_TERM = "short_term"
    private const val MEDIUM_TERM = "medium_term"
    private const val LONG_TERM = "long_term"
  }
}

data class SpotifyTasteSnapshot(
  val shortTerm: SpotifyTasteWindow,
  val mediumTerm: SpotifyTasteWindow,
  val longTerm: SpotifyTasteWindow,
)

data class SpotifyTasteWindow(
  val artists: List<SpotifyTasteArtist>,
  val tracks: List<SpotifyTasteTrack>,
)

data class SpotifyTasteArtist(
  val id: String,
  val name: String,
  val genres: List<String>,
)

data class SpotifyTasteArtistRef(
  val id: String,
  val name: String,
)

data class SpotifyTasteTrack(
  val id: String,
  val name: String,
  val artists: List<SpotifyTasteArtistRef>,
  val albumId: String,
  val albumName: String,
)
