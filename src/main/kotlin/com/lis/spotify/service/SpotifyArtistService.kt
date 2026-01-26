/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.lis.spotify.service

import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.ArtistSearchResult
import com.lis.spotify.domain.ArtistTopTracks
import com.lis.spotify.domain.Track
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyArtistService(private val spotifyRestService: SpotifyRestService) {
  private val logger = LoggerFactory.getLogger(SpotifyArtistService::class.java)

  fun searchArtist(name: String, clientId: String): Artist? {
    logger.debug("searchArtist {} {}", name, clientId)
    val result =
      spotifyRestService.doGet<ArtistSearchResult>(
        SEARCH_URL,
        params = mapOf("q" to "artist:$name", "type" to "artist", "limit" to 1),
        clientId = clientId,
      )
    val artist = result.artists.items.firstOrNull()
    logger.debug("searchArtist {} {} -> {}", name, clientId, artist)
    return artist
  }

  fun getArtistTopTracks(artistId: String, clientId: String): List<Track> {
    logger.debug("getArtistTopTracks {} {}", artistId, clientId)
    val result =
      spotifyRestService.doGet<ArtistTopTracks>(
        TOP_TRACKS_URL,
        params = mapOf("id" to artistId, "market" to MARKET),
        clientId = clientId,
      )
    logger.debug("getArtistTopTracks {} {} -> {}", artistId, clientId, result.tracks.size)
    return result.tracks
  }

  companion object {
    private const val SEARCH_URL =
      "https://api.spotify.com/v1/search?q={q}&type={type}&limit={limit}"
    private const val TOP_TRACKS_URL =
      "https://api.spotify.com/v1/artists/{id}/top-tracks?market={market}"
    private const val MARKET = "from_token"
  }
}
