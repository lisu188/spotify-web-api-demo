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
import com.lis.spotify.logging.asSafeClientIdForLogs
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
    private val SUPPORTED_TIME_RANGES = setOf(SHORT_TERM, MID_TERM, LONG_TERM)
    private const val DEFAULT_LIMIT = 10
    const val MAX_LIMIT = 50
    val logger = LoggerFactory.getLogger(SpotifyTopArtistService::class.java)
  }

  private fun getTopArtists(term: String, clientId: String, limit: Int = DEFAULT_LIMIT): Artists {
    logger.debug("getTopArtists {} {}", term, clientId.asSafeClientIdForLogs())
    val artists =
      spotifyRestService.doGet<Artists>(
        URL,
        params = mapOf("limit" to limit, "time_range" to term),
        clientId = clientId,
      )
    logger.debug(
      "getTopArtists {} {} -> {} items",
      term,
      clientId.asSafeClientIdForLogs(),
      artists.items.size,
    )
    return artists
  }

  fun getTopArtists(clientId: String, timeRange: String, limit: Int = MAX_LIMIT): List<Artist> {
    require(timeRange in SUPPORTED_TIME_RANGES) { "Unsupported Spotify time range" }
    require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
    return getTopArtists(timeRange, clientId, limit).items
  }

  fun getTopArtistsLongTerm(clientId: String): List<Artist> {
    logger.debug("getTopArtistsLongTerm {}", clientId.asSafeClientIdForLogs())
    val items = getTopArtists(LONG_TERM, clientId).items
    logger.debug(
      "getTopArtistsLongTerm {} -> {} items",
      clientId.asSafeClientIdForLogs(),
      items.size,
    )
    return items
  }

  fun getTopArtistsMidTerm(clientId: String): List<Artist> {
    logger.debug("getTopArtistsMidTerm {}", clientId.asSafeClientIdForLogs())
    val items = getTopArtists(MID_TERM, clientId).items
    logger.debug(
      "getTopArtistsMidTerm {} -> {} items",
      clientId.asSafeClientIdForLogs(),
      items.size,
    )
    return items
  }

  fun getTopArtistsShortTerm(clientId: String): List<Artist> {
    logger.debug("getTopArtistsShortTerm {}", clientId.asSafeClientIdForLogs())
    val items = getTopArtists(SHORT_TERM, clientId).items
    logger.debug(
      "getTopArtistsShortTerm {} -> {} items",
      clientId.asSafeClientIdForLogs(),
      items.size,
    )
    return items
  }
}
