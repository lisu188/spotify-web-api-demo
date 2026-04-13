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
import com.lis.spotify.domain.Song
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
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

data class ForgottenObsessionsPlaylistResult(
  val playlistId: String?,
  val playlistTrackCount: Int,
  val spotifyMatchCount: Int,
  val candidateCount: Int,
)

@Service
class SpotifyTopPlaylistsService(
  var spotifyPlaylistService: SpotifyPlaylistService,
  var spotifyTopTrackService: SpotifyTopTrackService,
  var lastFmService: LastFmService,
  var spotifySearchService: SpotifySearchService,
  @Value("\${lastfm.jobs.max-parallelism:16}")
  configuredYearlyParallelism: Int = DEFAULT_YEARLY_PARALLELISM,
) {

  private val yearlyLimit = 250
  private val playlistCreationLocks = ConcurrentHashMap<String, Any>()
  internal var yearlyParallelism = configuredYearlyParallelism.coerceAtLeast(1)
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
                spotifySearchService.searchTrackIds(
                  lastFmService
                    .yearlyChartlist(clientId, year, lastFmLogin, yearlyLimit)
                    .take(yearlyLimit),
                  clientId,
                )

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

  fun updateForgottenObsessionsPlaylist(
    clientId: String,
    lastFmLogin: String,
    progress: (Int, String) -> Unit = { _, _ -> },
  ): ForgottenObsessionsPlaylistResult {
    val normalizedLastFmLogin = lastFmLogin.trim()
    require(normalizedLastFmLogin.isNotBlank()) { "lastFmLogin must not be blank" }

    logger.debug("updateForgottenObsessionsPlaylist {} {}", clientId, normalizedLastFmLogin)
    logger.info("updateForgottenObsessionsPlaylist: {}", clientId)

    val years = (firstSupportedYear..getYear()).toList().sortedDescending()
    val totalSteps = (years.size + 2).coerceAtLeast(1)
    val completedSteps = AtomicInteger(0)
    val progressLock = Any()
    val yearSemaphore = Semaphore(yearlyParallelism.coerceAtLeast(1))
    val forgottenObsessionStats =
      ConcurrentHashMap<Pair<String, String>, ForgottenObsessionAggregate>()
    val existingPlaylists by
      lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConcurrentHashMap(
          spotifyPlaylistService.getCurrentUserPlaylists(clientId).associateBy { it.name }
        )
      }
    progress(0, "Scanning Last.fm history")

    runBlocking(Dispatchers.IO) {
      years
        .map { year ->
          async(Dispatchers.IO) {
            yearSemaphore.withPermit {
              logger.info("Scanning forgotten obsessions candidates for year {}", year)
              val yearScrobbles =
                lastFmService.yearlyChartlist(clientId, year, normalizedLastFmLogin, Int.MAX_VALUE)
              accumulateForgottenObsessionStats(yearScrobbles, forgottenObsessionStats)
              val completedPercent: Int
              synchronized(progressLock) {
                val completed = completedSteps.incrementAndGet()
                completedPercent = (completed * 100) / totalSteps
                progress(completedPercent, "Scanned $year ($completed/${years.size})")
              }
              logger.info(
                "Forgotten obsessions scan for year {} completed: {}%",
                year,
                completedPercent,
              )
            }
          }
        }
        .awaitAll()
    }

    val candidates = selectForgottenObsessions(forgottenObsessionStats.values)
    if (candidates.isEmpty()) {
      progress(100, "No forgotten obsessions found yet")
      logger.info("No forgotten obsessions found for {}", clientId)
      return ForgottenObsessionsPlaylistResult(
        playlistId = null,
        playlistTrackCount = 0,
        spotifyMatchCount = 0,
        candidateCount = 0,
      )
    }

    progress(((years.size + 1) * 100) / totalSteps, "Matching forgotten obsessions on Spotify")
    val trackIds = mutableListOf<String>()
    var spotifyMatchCount = 0
    var candidateOffset = 0
    while (candidateOffset < candidates.size) {
      val batch = candidates.drop(candidateOffset).take(FORGOTTEN_OBSESSIONS_SEARCH_BATCH_SIZE)
      val matchedBatch =
        runBlocking(Dispatchers.IO) { spotifySearchService.searchTrackIds(batch, clientId) }
      spotifyMatchCount += matchedBatch.size
      if (trackIds.size < FORGOTTEN_OBSESSIONS_TARGET_TRACK_COUNT) {
        val remainingCapacity = FORGOTTEN_OBSESSIONS_TARGET_TRACK_COUNT - trackIds.size
        trackIds += matchedBatch.filterNot { it in trackIds }.take(remainingCapacity)
      }
      candidateOffset += batch.size
    }

    if (trackIds.isEmpty()) {
      progress(100, "No Spotify matches found for forgotten obsessions")
      logger.info("Forgotten obsessions candidates found but no Spotify matches for {}", clientId)
      return ForgottenObsessionsPlaylistResult(
        playlistId = null,
        playlistTrackCount = 0,
        spotifyMatchCount = 0,
        candidateCount = candidates.size,
      )
    }

    val playlistId =
      getOrCreatePlaylistId(FORGOTTEN_OBSESSIONS_PLAYLIST_NAME, clientId, existingPlaylists).id
    try {
      spotifyPlaylistService.modifyPlaylist(playlistId, trackIds, clientId)
    } catch (ex: Exception) {
      throw PlaylistUpdateException(listOf(playlistId), ex)
    }
    progress(100, "Forgotten obsessions playlist refreshed")
    logger.info(
      "Forgotten obsessions playlist {} refreshed with {} playlist tracks from {} Spotify matches",
      playlistId,
      trackIds.size,
      spotifyMatchCount,
    )
    return ForgottenObsessionsPlaylistResult(
      playlistId = playlistId,
      playlistTrackCount = trackIds.size,
      spotifyMatchCount = spotifyMatchCount,
      candidateCount = candidates.size,
    )
  }

  internal fun selectForgottenObsessions(
    scrobbles: List<Song>,
    nowEpochSecond: Long = System.currentTimeMillis() / 1000,
    minimumPlayCount: Int = FORGOTTEN_OBSESSIONS_MIN_PLAY_COUNT,
    minimumDormantDays: Long = FORGOTTEN_OBSESSIONS_MIN_DORMANT_DAYS,
    limit: Int = Int.MAX_VALUE,
  ): List<Song> {
    return selectForgottenObsessions(
      buildForgottenObsessionStats(scrobbles).values,
      nowEpochSecond,
      minimumPlayCount,
      minimumDormantDays,
      limit,
    )
  }

  private fun selectForgottenObsessions(
    stats: Collection<ForgottenObsessionAggregate>,
    nowEpochSecond: Long = System.currentTimeMillis() / 1000,
    minimumPlayCount: Int = FORGOTTEN_OBSESSIONS_MIN_PLAY_COUNT,
    minimumDormantDays: Long = FORGOTTEN_OBSESSIONS_MIN_DORMANT_DAYS,
    limit: Int = Int.MAX_VALUE,
  ): List<Song> {
    return stats
      .asSequence()
      .mapNotNull { stat ->
        val dormantDays = ((nowEpochSecond - stat.lastPlayedAtEpochSecond) / SECONDS_PER_DAY)
        if (stat.playCount < minimumPlayCount || dormantDays < minimumDormantDays) {
          return@mapNotNull null
        }

        ForgottenObsessionCandidate(
          song = stat.song,
          playCount = stat.playCount,
          lastPlayedAtEpochSecond = stat.lastPlayedAtEpochSecond,
          score = stat.playCount.toLong() * dormantDays.coerceAtLeast(1),
        )
      }
      .sortedWith(
        compareByDescending<ForgottenObsessionCandidate> { it.score }
          .thenByDescending { it.playCount }
          .thenBy { it.lastPlayedAtEpochSecond }
          .thenBy { it.song.artist.lowercase() }
          .thenBy { it.song.title.lowercase() }
      )
      .take(limit)
      .map { it.song }
      .toList()
  }

  private fun buildForgottenObsessionStats(
    scrobbles: List<Song>
  ): ConcurrentHashMap<Pair<String, String>, ForgottenObsessionAggregate> {
    val stats = ConcurrentHashMap<Pair<String, String>, ForgottenObsessionAggregate>()
    accumulateForgottenObsessionStats(scrobbles, stats)
    return stats
  }

  private fun accumulateForgottenObsessionStats(
    scrobbles: List<Song>,
    stats: ConcurrentHashMap<Pair<String, String>, ForgottenObsessionAggregate>,
  ) {
    scrobbles.forEach { song ->
      val playedAtEpochSecond = song.playedAtEpochSecond ?: return@forEach
      val key = song.normalizedKey()
      stats.compute(key) { _, existing ->
        if (existing == null) {
          ForgottenObsessionAggregate(
            song = Song(song.artist, song.title),
            playCount = 1,
            lastPlayedAtEpochSecond = playedAtEpochSecond,
          )
        } else {
          existing.copy(
            playCount = existing.playCount + 1,
            lastPlayedAtEpochSecond = maxOf(existing.lastPlayedAtEpochSecond, playedAtEpochSecond),
          )
        }
      }
    }
  }

  private fun getOrCreatePlaylistId(
    playlistName: String,
    clientId: String,
    existingPlaylists: ConcurrentHashMap<String, Playlist>,
  ): Playlist {
    val lock = playlistCreationLocks.computeIfAbsent("$clientId|$playlistName") { Any() }
    synchronized(lock) {
      val existing = existingPlaylists[playlistName]
      if (existing != null) {
        return existing
      }

      val remoteExisting =
        spotifyPlaylistService.getCurrentUserPlaylists(clientId).firstOrNull {
          it.name == playlistName
        }
      if (remoteExisting != null) {
        existingPlaylists.putIfAbsent(playlistName, remoteExisting)
        return remoteExisting
      }

      val created = spotifyPlaylistService.createPlaylist(playlistName, clientId)
      val previous = existingPlaylists.putIfAbsent(playlistName, created)
      return previous ?: created
    }
  }

  private fun getYear() = currentYearProvider()

  private fun Song.normalizedKey(): Pair<String, String> {
    return artist.trim().lowercase() to title.trim().lowercase()
  }

  companion object {
    internal const val DEFAULT_YEARLY_PARALLELISM = 16
    private const val FIRST_SUPPORTED_YEAR = 2005
    private const val FORGOTTEN_OBSESSIONS_PLAYLIST_NAME = "Forgotten Obsessions"
    private const val FORGOTTEN_OBSESSIONS_MIN_PLAY_COUNT = 5
    private const val FORGOTTEN_OBSESSIONS_MIN_DORMANT_DAYS = 180L
    private const val FORGOTTEN_OBSESSIONS_SEARCH_BATCH_SIZE = 100
    private const val FORGOTTEN_OBSESSIONS_TARGET_TRACK_COUNT = 50
    private const val SECONDS_PER_DAY = 86_400L
  }

  private data class ForgottenObsessionCandidate(
    val song: Song,
    val playCount: Int,
    val lastPlayedAtEpochSecond: Long,
    val score: Long,
  )

  private data class ForgottenObsessionAggregate(
    val song: Song,
    val playCount: Int,
    val lastPlayedAtEpochSecond: Long,
  )
}

class PlaylistUpdateException(val playlistIds: List<String>, cause: Throwable) :
  RuntimeException(cause)
