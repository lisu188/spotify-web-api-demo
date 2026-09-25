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

import com.lis.spotify.domain.Track
import com.lis.spotify.domain.Tracks
import com.lis.spotify.logging.asSafeClientIdForLogs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyTopTrackService(var spotifyRestService: SpotifyRestService) {
  companion object {
    private val URL =
      "https://api.spotify.com/v1/me/top/tracks?limit={limit}&time_range={time_range}"
    private val SHORT_TERM = "short_term"
    private val MID_TERM = "medium_term"
    private val LONG_TERM = "long_term"
    private val SUPPORTED_TIME_RANGES = setOf(SHORT_TERM, MID_TERM, LONG_TERM)
    const val MAX_LIMIT = 50
    val logger = LoggerFactory.getLogger(SpotifyTopTrackService::class.java)
  }

  private fun fetchTopTracks(term: String, clientId: String, limit: Int = MAX_LIMIT): Tracks {
    logger.debug("getTopTracks {} {}", term, clientId.asSafeClientIdForLogs())
    val tracks =
      spotifyRestService.doGet<Tracks>(
        URL,
        params = mapOf("limit" to limit, "time_range" to term),
        clientId = clientId,
      )
    logger.debug(
      "getTopTracks {} {} -> {} items",
      term,
      clientId.asSafeClientIdForLogs(),
      tracks.items.size,
    )
    return tracks
  }

  fun getTopTracks(clientId: String, timeRange: String, limit: Int = MAX_LIMIT): List<Track> {
    require(timeRange in SUPPORTED_TIME_RANGES) { "Unsupported Spotify time range" }
    require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
    return fetchTopTracks(timeRange, clientId, limit).items
  }

  fun getTopTracksLongTerm(clientId: String): List<Track> {
    logger.info("Fetching long-term top tracks for {}", clientId.asSafeClientIdForLogs())
    logger.debug("getTopTracksLongTerm {}", clientId.asSafeClientIdForLogs())
    val items = fetchTopTracks(LONG_TERM, clientId).items
    logger.debug(
      "getTopTracksLongTerm {} -> {} items",
      clientId.asSafeClientIdForLogs(),
      items.size,
    )
    logger.info("Fetched {} long-term tracks for {}", items.size, clientId.asSafeClientIdForLogs())
    return items
  }

  fun getTopTracksMidTerm(clientId: String): List<Track> {
    logger.info("Fetching mid-term top tracks for {}", clientId.asSafeClientIdForLogs())
    logger.debug("getTopTracksMidTerm {}", clientId.asSafeClientIdForLogs())
    val items = fetchTopTracks(MID_TERM, clientId).items
    logger.debug("getTopTracksMidTerm {} -> {} items", clientId.asSafeClientIdForLogs(), items.size)
    logger.info("Fetched {} mid-term tracks for {}", items.size, clientId.asSafeClientIdForLogs())
    return items
  }

  fun getTopTracksShortTerm(clientId: String): List<Track> {
    logger.info("Fetching short-term top tracks for {}", clientId.asSafeClientIdForLogs())
    logger.debug("getTopTracksShortTerm {}", clientId.asSafeClientIdForLogs())
    val items = fetchTopTracks(SHORT_TERM, clientId).items
    logger.debug(
      "getTopTracksShortTerm {} -> {} items",
      clientId.asSafeClientIdForLogs(),
      items.size,
    )
    logger.info("Fetched {} short-term tracks for {}", items.size, clientId.asSafeClientIdForLogs())
    return items
  }
}
