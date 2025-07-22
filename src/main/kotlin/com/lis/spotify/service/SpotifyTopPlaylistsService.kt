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

import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyTopPlaylistsService(
  var spotifyPlaylistService: SpotifyPlaylistService,
  var spotifyTopTrackService: SpotifyTopTrackService,
  var lastFmService: LastFmService,
  var spotifySearchService: SpotifySearchService,
) {

  private val YEARLY_LIMIT = 250

  private val logger = LoggerFactory.getLogger(SpotifyTopPlaylistsService::class.java)

  fun updateTopPlaylists(clientId: String): List<String> {
    logger.debug("updateTopPlaylists {}", clientId)
    logger.info("updateTopPlaylists: {}", clientId)

    return runBlocking {
      val shortTerm =
        spotifyTopTrackService
          .getTopTracksShortTerm(clientId)
          .map { it.id }
          .toCollection(arrayListOf())

      val midTerm =
        spotifyTopTrackService
          .getTopTracksMidTerm(clientId)
          .map { it.id }
          .toCollection(arrayListOf())

      val longTerm =
        spotifyTopTrackService
          .getTopTracksLongTerm(clientId)
          .map { it.id }
          .toCollection(arrayListOf())

      val ids = mutableListOf<String>()

      if (shortTerm.isNotEmpty()) {
        val shortTermId = spotifyPlaylistService.getOrCreatePlaylist("Short Term", clientId).id
        spotifyPlaylistService.modifyPlaylist(shortTermId, shortTerm, clientId)
        ids += shortTermId
      }

      if (midTerm.isNotEmpty()) {
        val midTermId = spotifyPlaylistService.getOrCreatePlaylist("Mid Term", clientId).id
        spotifyPlaylistService.modifyPlaylist(midTermId, midTerm, clientId)
        ids += midTermId
      }

      if (longTerm.isNotEmpty()) {
        val longTermId = spotifyPlaylistService.getOrCreatePlaylist("Long Term", clientId).id
        spotifyPlaylistService.modifyPlaylist(longTermId, longTerm, clientId)
        ids += longTermId
      }

      val trackList1: List<String> =
        (shortTerm.asIterable() + midTerm.asIterable() + longTerm.asIterable())
          .toCollection(arrayListOf())
          .distinct()

      if (trackList1.isNotEmpty()) {
        val mixedTermId = spotifyPlaylistService.getOrCreatePlaylist("Mixed Term", clientId).id
        spotifyPlaylistService.modifyPlaylist(mixedTermId, trackList1, clientId)
        ids += mixedTermId
      }

      val result = ids.toList()
      logger.debug("updateTopPlaylists {} -> {}", clientId, result)
      result
    }
  }

  fun updateYearlyPlaylists(
    clientId: String,
    progressUpdater: (Pair<Int, Int>) -> Unit = {},
    lastFmLogin: String,
  ) {
    logger.debug("updateYearlyPlaylists {} {}", clientId, lastFmLogin)
    logger.info("updateYearlyPlaylists: {}", clientId)
    runBlocking {
      val years = (2005..getYear()).toList()

      val chartlists =
        years
          .map { year ->
            async(Dispatchers.IO) {
              year to lastFmService.yearlyChartlist(clientId, year, lastFmLogin, YEARLY_LIMIT)
            }
          }
          .awaitAll()
          .toMap()

      years
        .map { year: Int ->
          async(Dispatchers.IO) {
            progressUpdater(Pair(year, 0))
            val chartlist = chartlists[year].orEmpty()

            val trackList = mutableListOf<String>()
            for ((idx, song) in chartlist.withIndex()) {
              val result = spotifySearchService.doSearch(song, clientId)
              result?.tracks?.items?.stream()?.findFirst()?.orElse(null)?.let { trackList += it.id }
              progressUpdater(Pair(year, (idx + 1) * 100 / YEARLY_LIMIT))
              if (idx + 1 >= YEARLY_LIMIT) break
            }
            if (trackList.isNotEmpty()) {
              val playlistId =
                spotifyPlaylistService.getOrCreatePlaylist("LAST.FM $year", clientId).id
              spotifyPlaylistService.modifyPlaylist(playlistId, trackList.distinct(), clientId)
              spotifyPlaylistService.deduplicatePlaylist(playlistId, clientId)
            }
            progressUpdater(Pair(year, 100))
          }
        }
        .awaitAll()
    }
    logger.debug("updateYearlyPlaylists {} completed", clientId)
  }

  private fun getYear() = Calendar.getInstance().get(Calendar.YEAR)
}
