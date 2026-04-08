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

import com.lis.spotify.domain.Playlist
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyTopPlaylistsService(
  var spotifyPlaylistService: SpotifyPlaylistService,
  var spotifyTopTrackService: SpotifyTopTrackService,
  var lastFmService: LastFmService,
  var spotifySearchService: SpotifySearchService,
) {

  private val yearlyLimit = 250
  internal var yearlyParallelism = DEFAULT_YEARLY_PARALLELISM
  internal var searchParallelism = DEFAULT_SEARCH_PARALLELISM
  internal var firstSupportedYear = FIRST_SUPPORTED_YEAR
  internal var currentYearProvider: () -> Int = { Calendar.getInstance().get(Calendar.YEAR) }

  private val logger = LoggerFactory.getLogger(SpotifyTopPlaylistsService::class.java)

  fun updateTopPlaylists(clientId: String): List<String> {
    logger.debug("updateTopPlaylists {}", clientId)
    logger.info("updateTopPlaylists: {}", clientId)

    return runBlocking(Dispatchers.IO) {
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

      val mixedTrackIds: List<String> =
        (shortTerm.asIterable() + midTerm.asIterable() + longTerm.asIterable())
          .toCollection(arrayListOf())
          .distinct()

      if (mixedTrackIds.isNotEmpty()) {
        val mixedTermId = spotifyPlaylistService.getOrCreatePlaylist("Mixed Term", clientId).id
        spotifyPlaylistService.modifyPlaylist(mixedTermId, mixedTrackIds, clientId)
        ids += mixedTermId
      }

      val result = ids.toList()
      logger.debug("updateTopPlaylists {} -> {}", clientId, result)
      logger.info("Updated top playlists for {} -> {}", clientId, result)
      result
    }
  }

  fun updateYearlyPlaylists(
    clientId: String,
    lastFmLogin: String,
    progress: (Int, String) -> Unit = { _, _ -> },
  ) {
    logger.debug("updateYearlyPlaylists {} {}", clientId, lastFmLogin)
    logger.info("updateYearlyPlaylists: {}", clientId)
    runBlocking(Dispatchers.IO) {
      val years = (firstSupportedYear..getYear()).toList().sortedDescending()
      val total = years.size.coerceAtLeast(1)
      val completedYears = AtomicInteger(0)
      val progressLock = Any()
      val yearSemaphore = Semaphore(yearlyParallelism.coerceAtLeast(1))
      val searchSemaphore = Semaphore(searchParallelism.coerceAtLeast(1))
      val existingPlaylists by
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
          ConcurrentHashMap(
            spotifyPlaylistService.getCurrentUserPlaylists(clientId).associateBy { it.name }
          )
        }
      progress(0, "Starting yearly playlist refresh")

      years
        .map { year ->
          async(Dispatchers.IO) {
            yearSemaphore.withPermit {
              logger.info("Processing year {}", year)
              val trackList =
                lastFmService
                  .yearlyChartlist(clientId, year, lastFmLogin, yearlyLimit)
                  .take(yearlyLimit)
                  .map { song ->
                    async(Dispatchers.IO) {
                      searchSemaphore.withPermit {
                        spotifySearchService
                          .doSearch(song, clientId)
                          ?.tracks
                          ?.items
                          ?.stream()
                          ?.findFirst()
                          ?.orElse(null)
                          ?.id
                      }
                    }
                  }
                  .awaitAll()
                  .filterNotNull()
                  .distinct()

              if (trackList.isNotEmpty()) {
                val playlistId =
                  getOrCreatePlaylistId("LAST.FM $year", clientId, existingPlaylists).id
                spotifyPlaylistService.modifyPlaylist(playlistId, trackList, clientId)
                spotifyPlaylistService.deduplicatePlaylist(playlistId, clientId)
              }
              val completedPercent: Int
              synchronized(progressLock) {
                val completed = completedYears.incrementAndGet()
                completedPercent = (completed * 100) / total
                progress(completedPercent, "Finished $year ($completed/$total)")
              }
              logger.info("Year {} completed: {}%", year, completedPercent)
            }
          }
        }
        .awaitAll()
    }
    progress(100, "Yearly playlists refreshed")
    logger.info("updateYearlyPlaylists {} completed", clientId)
  }

  private fun getOrCreatePlaylistId(
    playlistName: String,
    clientId: String,
    existingPlaylists: ConcurrentHashMap<String, Playlist>,
  ): Playlist {
    val existing = existingPlaylists[playlistName]
    if (existing != null) {
      return existing
    }

    val created = spotifyPlaylistService.createPlaylist(playlistName, clientId)
    val previous = existingPlaylists.putIfAbsent(playlistName, created)
    return previous ?: created
  }

  private fun getYear() = currentYearProvider()

  companion object {
    internal const val DEFAULT_YEARLY_PARALLELISM = 4
    internal const val DEFAULT_SEARCH_PARALLELISM = 24
    private const val FIRST_SUPPORTED_YEAR = 2005
  }
}
