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
import com.lis.spotify.domain.Artists
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyTopArtistService(var spotifyRestService: SpotifyRestService) {
  companion object {
    private val URL =
      "https://api.spotify.com/v1/me/top/artists?limit={limit}&time_range={time_range}"
    private val SHORT_TERM = "short_term"
    private val MID_TERM = "medium_term"
    private val LONG_TERM = "long_term"
    val logger = LoggerFactory.getLogger(SpotifyTopArtistService::class.java)
  }

  private fun getTopArtists(term: String, clientId: String): Artists {
    logger.debug("getTopArtists {} {}", term, clientId)
    val artists =
      spotifyRestService.doGet<Artists>(
        URL,
        params = mapOf("limit" to 10, "time_range" to term),
        clientId = clientId,
      )
    logger.debug("getTopArtists {} {} -> {} items", term, clientId, artists.items.size)
    return artists
  }

  fun getTopArtistsLongTerm(clientId: String): List<Artist> {
    logger.debug("getTopArtistsLongTerm {}", clientId)
    val items = getTopArtists(LONG_TERM, clientId).items
    logger.debug("getTopArtistsLongTerm {} -> {} items", clientId, items.size)
    return items
  }

  fun getTopArtistsMidTerm(clientId: String): List<Artist> {
    logger.debug("getTopArtistsMidTerm {}", clientId)
    val items = getTopArtists(MID_TERM, clientId).items
    logger.debug("getTopArtistsMidTerm {} -> {} items", clientId, items.size)
    return items
  }

  fun getTopArtistsShortTerm(clientId: String): List<Artist> {
    logger.debug("getTopArtistsShortTerm {}", clientId)
    val items = getTopArtists(SHORT_TERM, clientId).items
    logger.debug("getTopArtistsShortTerm {} -> {} items", clientId, items.size)
    return items
  }
}
