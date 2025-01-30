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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
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

  fun updateTopPlaylists(clientId: String): List<String> {
    LoggerFactory.getLogger(javaClass).info("updateTopPlaylists: {}", clientId)

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

      val shortTermId = spotifyPlaylistService.getOrCreatePlaylist("Short Term", clientId).id

      val midTermId = spotifyPlaylistService.getOrCreatePlaylist("Mid Term", clientId).id

      val longTermId = spotifyPlaylistService.getOrCreatePlaylist("Long Term", clientId).id

      val mixedTermId = spotifyPlaylistService.getOrCreatePlaylist("Mixed Term", clientId).id

      spotifyPlaylistService.modifyPlaylist(shortTermId, shortTerm, clientId)

      spotifyPlaylistService.modifyPlaylist(midTermId, midTerm, clientId)

      spotifyPlaylistService.modifyPlaylist(longTermId, longTerm, clientId)

      val trackList1: List<String> =
        (shortTerm.asIterable() + midTerm.asIterable() + longTerm.asIterable())
          .toCollection(arrayListOf())
          .distinct()

      spotifyPlaylistService.modifyPlaylist(mixedTermId, trackList1, clientId)

      listOf(shortTermId, midTermId, longTermId, mixedTermId)
    }
  }

  suspend fun updateYearlyPlaylists(
    clientId: String,
    progressUpdater: (Pair<Int, Int>) -> Unit = {},
    lastFmLogin: String,
  ) {
    LoggerFactory.getLogger(javaClass).info("updateYearlyPlaylists: {}", clientId)
    GlobalScope.async {
        (2005..getYear()).map { year: Int ->
          var progress = AtomicInteger()
          progressUpdater(Pair(year, progress.get()))
          val chartlist = lastFmService.yearlyChartlist(clientId, year, lastFmLogin)
          var trackList =
            spotifySearchService.doSearch(chartlist, clientId) {
              progressUpdater(Pair(year, progress.incrementAndGet() * 100 / chartlist.size))
            }
          progressUpdater(Pair(year, 100))
          if (trackList.isNotEmpty()) {
            val id = spotifyPlaylistService.getOrCreatePlaylist("LAST.FM $year", clientId).id
            spotifyPlaylistService.modifyPlaylist(id, trackList, clientId)
          }
        }
      }
      .await()
  }

  private fun getYear() = Calendar.getInstance().get(Calendar.YEAR)
}
