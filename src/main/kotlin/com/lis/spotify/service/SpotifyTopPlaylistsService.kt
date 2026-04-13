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
import com.lis.spotify.domain.Track
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.LinkedHashMap
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

data class PrivateMoodTaxonomyResult(val playlists: List<PrivateMoodPlaylistResult>) {
  val playlistIds: List<String>
    get() = playlists.map { it.playlistId }
}

data class PrivateMoodPlaylistResult(
  val label: String,
  val playlistName: String,
  val playlistId: String,
  val trackCount: Int,
  val candidateCount: Int,
)

@Service
class SpotifyTopPlaylistsService(
  var spotifyPlaylistService: SpotifyPlaylistService,
  var spotifyTopTrackService: SpotifyTopTrackService,
  var lastFmService: LastFmService,
  var spotifySearchService: SpotifySearchService,
  @Value("\${lastfm.jobs.max-parallelism:4}")
  configuredYearlyParallelism: Int = DEFAULT_YEARLY_PARALLELISM,
) {

  private val yearlyLimit = 250
  private val playlistCreationLocks = ConcurrentHashMap<String, Any>()
  internal var yearlyParallelism = configuredYearlyParallelism.coerceAtLeast(1)
  internal var firstSupportedYear = FIRST_SUPPORTED_YEAR
  internal var currentYearProvider: () -> Int = { Calendar.getInstance().get(Calendar.YEAR) }
  internal var nowEpochSecondProvider: () -> Long = { System.currentTimeMillis() / 1000 }
  internal var analysisZoneIdProvider: () -> ZoneId = { ZoneOffset.UTC }

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

    val matchingProgressStart = ((years.size + 1) * 100) / totalSteps
    val totalMatchingBatches =
      ((candidates.size + FORGOTTEN_OBSESSIONS_SEARCH_BATCH_SIZE - 1) /
          FORGOTTEN_OBSESSIONS_SEARCH_BATCH_SIZE)
        .coerceAtLeast(1)
    progress(matchingProgressStart, "Matching forgotten obsessions on Spotify")
    logger.info(
      "Matching {} forgotten obsessions candidates on Spotify across {} batches",
      candidates.size,
      totalMatchingBatches,
    )
    val trackIds = mutableListOf<String>()
    var spotifyMatchCount = 0
    var candidateOffset = 0
    var batchIndex = 0
    while (
      candidateOffset < candidates.size && trackIds.size < FORGOTTEN_OBSESSIONS_TARGET_TRACK_COUNT
    ) {
      val batch = candidates.drop(candidateOffset).take(FORGOTTEN_OBSESSIONS_SEARCH_BATCH_SIZE)
      val matchedBatch =
        runBlocking(Dispatchers.IO) { spotifySearchService.searchTrackIds(batch, clientId) }
      spotifyMatchCount += matchedBatch.size
      val remainingCapacity = FORGOTTEN_OBSESSIONS_TARGET_TRACK_COUNT - trackIds.size
      trackIds += matchedBatch.filterNot { it in trackIds }.take(remainingCapacity)
      candidateOffset += batch.size
      batchIndex++
      val matchingProgress =
        if (
          candidateOffset >= candidates.size ||
            trackIds.size >= FORGOTTEN_OBSESSIONS_TARGET_TRACK_COUNT
        ) {
          99
        } else {
          val matchingRange = (99 - matchingProgressStart).coerceAtLeast(1)
          matchingProgressStart + ((batchIndex * matchingRange) / totalMatchingBatches)
        }
      progress(
        matchingProgress.coerceIn(matchingProgressStart, 99),
        "Matching forgotten obsessions on Spotify ($batchIndex/$totalMatchingBatches)",
      )
      logger.info(
        "Forgotten obsessions matching batch {}/{} complete: {} playlist tracks collected from {} Spotify matches",
        batchIndex,
        totalMatchingBatches,
        trackIds.size,
        spotifyMatchCount,
      )
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

  fun updatePrivateMoodTaxonomyPlaylists(
    clientId: String,
    lastFmLogin: String,
    playlistSize: Int = PRIVATE_MOOD_DEFAULT_PLAYLIST_SIZE,
    progress: (Int, String) -> Unit = { _, _ -> },
  ): PrivateMoodTaxonomyResult {
    val normalizedLastFmLogin = lastFmLogin.trim()
    require(normalizedLastFmLogin.isNotBlank()) { "lastFmLogin must not be blank" }
    val normalizedPlaylistSize = playlistSize.coerceIn(1, PRIVATE_MOOD_MAX_PLAYLIST_SIZE)
    requirePrivateMoodScopes(clientId)

    logger.debug(
      "updatePrivateMoodTaxonomyPlaylists {} {} {}",
      clientId,
      normalizedLastFmLogin,
      normalizedPlaylistSize,
    )
    logger.info("updatePrivateMoodTaxonomyPlaylists: {}", clientId)

    val years = (firstSupportedYear..getYear()).toList().sortedDescending()
    val yearSemaphore = Semaphore(yearlyParallelism.coerceAtLeast(1))
    val completedYears = AtomicInteger(0)
    val progressLock = Any()
    val scanProgressEnd = 60
    val scrobbles = mutableListOf<Song>()
    val scrobbleLock = Any()
    val existingPlaylists by
      lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConcurrentHashMap(
          spotifyPlaylistService.getCurrentUserPlaylists(clientId).associateBy { it.name }
        )
      }

    progress(0, "Scanning listening history for private moods")
    runBlocking(Dispatchers.IO) {
      years
        .map { year ->
          async(Dispatchers.IO) {
            yearSemaphore.withPermit {
              logger.info("Scanning private mood history for year {}", year)
              val yearScrobbles =
                lastFmService.yearlyChartlist(clientId, year, normalizedLastFmLogin, Int.MAX_VALUE)
              synchronized(scrobbleLock) { scrobbles += yearScrobbles }
              val completedPercent: Int
              synchronized(progressLock) {
                val completed = completedYears.incrementAndGet()
                completedPercent = (completed * scanProgressEnd) / years.size.coerceAtLeast(1)
                progress(completedPercent, "Scanned $year ($completed/${years.size})")
              }
              logger.info("Private mood scan for year {} completed: {}%", year, completedPercent)
            }
          }
        }
        .awaitAll()
    }

    progress(65, "Loading Spotify listening signals")
    val shortTermTracks = spotifyTopTrackService.getTopTracksShortTerm(clientId)
    val midTermTracks = spotifyTopTrackService.getTopTracksMidTerm(clientId)
    val longTermTracks = spotifyTopTrackService.getTopTracksLongTerm(clientId)
    val spotifySignals =
      buildPrivateMoodSpotifySignals(shortTermTracks, midTermTracks, longTermTracks)

    progress(72, "Analyzing listening patterns")
    val stats =
      buildPrivateMoodSongStats(
        scrobbles = scrobbles,
        nowEpochSecond = nowEpochSecondProvider(),
        zoneId = analysisZoneIdProvider(),
      )
    val statsByKey = stats.associateBy { it.normalizedKey }
    val anchorCandidates = selectAnchorCandidates(stats, spotifySignals)
    val surgeCandidates = selectSurgeCandidates(stats, spotifySignals)
    val happyCandidates = selectHappyCandidates(stats, spotifySignals)
    val sadCandidates = selectSadCandidates(stats, spotifySignals)
    val nightDriftCandidates = selectNightDriftCandidates(stats, spotifySignals)

    progress(80, "Exploring frontier candidates")
    val frontierCandidates =
      buildFrontierCandidates(
        statsByKey = statsByKey,
        spotifySignals = spotifySignals,
        anchorCandidates = anchorCandidates,
        playlistSize = normalizedPlaylistSize,
      )

    val playlistCandidates =
      linkedMapOf(
        PrivateMoodPlaylistKind.ANCHOR to anchorCandidates,
        PrivateMoodPlaylistKind.HAPPY to happyCandidates,
        PrivateMoodPlaylistKind.SAD to sadCandidates,
        PrivateMoodPlaylistKind.SURGE to surgeCandidates,
        PrivateMoodPlaylistKind.NIGHT_DRIFT to nightDriftCandidates,
        PrivateMoodPlaylistKind.FRONTIER to frontierCandidates,
      )
    val playlistProcessingOrder =
      listOf(
        PrivateMoodPlaylistKind.HAPPY,
        PrivateMoodPlaylistKind.SAD,
        PrivateMoodPlaylistKind.NIGHT_DRIFT,
        PrivateMoodPlaylistKind.SURGE,
        PrivateMoodPlaylistKind.ANCHOR,
        PrivateMoodPlaylistKind.FRONTIER,
      )

    val updatedPlaylistIds = mutableListOf<String>()
    val reservedCandidateKeys = mutableSetOf<Pair<String, String>>()
    val usedTrackIds = mutableSetOf<String>()
    val playlistResultsByKind = linkedMapOf<PrivateMoodPlaylistKind, PrivateMoodPlaylistResult>()
    val playlistProgressStart = 85
    val playlistProgressRange = 14

    playlistProcessingOrder.forEachIndexed { index, kind ->
      val candidates = playlistCandidates.getValue(kind)
      val filteredCandidates = candidates.filterNot { it.normalizedKey in reservedCandidateKeys }
      progress(
        playlistProgressStart + ((index * playlistProgressRange) / playlistProcessingOrder.size),
        "Matching ${kind.label} on Spotify",
      )
      val matchedTracks =
        matchPrivateMoodTrackIds(
          candidates = filteredCandidates,
          clientId = clientId,
          playlistSize = normalizedPlaylistSize,
          excludedTrackIds = usedTrackIds,
        )
      reservedCandidateKeys += matchedTracks.attemptedKeys

      val playlist =
        getOrCreatePlaylistId(
          playlistName = kind.playlistName(),
          clientId = clientId,
          existingPlaylists = existingPlaylists,
          public = false,
        )
      try {
        spotifyPlaylistService.modifyPlaylist(playlist.id, matchedTracks.trackIds, clientId)
      } catch (ex: Exception) {
        throw PlaylistUpdateException(updatedPlaylistIds + playlist.id, ex)
      }

      updatedPlaylistIds += playlist.id
      usedTrackIds += matchedTracks.trackIds
      playlistResultsByKind[kind] =
        PrivateMoodPlaylistResult(
          label = kind.label,
          playlistName = kind.playlistName(),
          playlistId = playlist.id,
          trackCount = matchedTracks.trackIds.size,
          candidateCount = filteredCandidates.size,
        )
      progress(
        playlistProgressStart +
          (((index + 1) * playlistProgressRange) / playlistProcessingOrder.size).coerceAtMost(99),
        "${kind.label} playlist refreshed (${matchedTracks.trackIds.size} tracks)",
      )
    }

    progress(100, "Private mood taxonomy refreshed")
    val result =
      PrivateMoodTaxonomyResult(
        PrivateMoodPlaylistKind.entries.mapNotNull { playlistResultsByKind[it] }
      )
    logger.info(
      "Private mood taxonomy refreshed for {} -> {}",
      clientId,
      result.playlists.associate { it.label to it.trackCount },
    )
    return result
  }

  private fun requirePrivateMoodScopes(clientId: String) {
    if (!spotifyPlaylistService.hasRequiredScopes(clientId, PRIVATE_MOOD_REQUIRED_SCOPES)) {
      logger.warn(
        "Missing Spotify scopes {} for private mood taxonomy clientId={}",
        PRIVATE_MOOD_REQUIRED_SCOPES,
        clientId,
      )
      throw AuthenticationRequiredException("SPOTIFY")
    }
  }

  private fun buildPrivateMoodSpotifySignals(
    shortTermTracks: List<Track>,
    midTermTracks: List<Track>,
    longTermTracks: List<Track>,
  ): PrivateMoodSpotifySignals {
    val shortTermSongs = shortTermTracks.map { it.toSong() }
    val midTermSongs = midTermTracks.map { it.toSong() }
    val longTermSongs = longTermTracks.map { it.toSong() }
    return PrivateMoodSpotifySignals(
      shortTermSongs = shortTermSongs,
      midTermSongs = midTermSongs,
      longTermSongs = longTermSongs,
      shortTermKeys = shortTermSongs.map { it.normalizedKey() }.toSet(),
      midTermKeys = midTermSongs.map { it.normalizedKey() }.toSet(),
      longTermKeys = longTermSongs.map { it.normalizedKey() }.toSet(),
    )
  }

  internal fun buildPrivateMoodSongStats(
    scrobbles: List<Song>,
    nowEpochSecond: Long = nowEpochSecondProvider(),
    zoneId: ZoneId = analysisZoneIdProvider(),
  ): List<PrivateMoodSongStats> {
    val timestampedScrobbles =
      scrobbles.filter { it.playedAtEpochSecond != null }.sortedBy { it.playedAtEpochSecond }
    val stats = LinkedHashMap<Pair<String, String>, MutablePrivateMoodSongStats>()
    val recent30Cutoff = nowEpochSecond - (30 * SECONDS_PER_DAY)
    val recent90Cutoff = nowEpochSecond - (90 * SECONDS_PER_DAY)

    timestampedScrobbles.forEach { song ->
      val playedAtEpochSecond = song.playedAtEpochSecond ?: return@forEach
      val key = song.normalizedKey()
      val playedAt = Instant.ofEpochSecond(playedAtEpochSecond).atZone(zoneId)
      val stat =
        stats.getOrPut(key) {
          MutablePrivateMoodSongStats(
            song = Song(song.artist, song.title),
            normalizedKey = key,
            firstPlayedAtEpochSecond = playedAtEpochSecond,
            lastPlayedAtEpochSecond = playedAtEpochSecond,
          )
        }
      stat.totalPlays += 1
      stat.firstPlayedAtEpochSecond = minOf(stat.firstPlayedAtEpochSecond, playedAtEpochSecond)
      stat.lastPlayedAtEpochSecond = maxOf(stat.lastPlayedAtEpochSecond, playedAtEpochSecond)
      stat.activeYears += playedAt.year
      stat.hourHistogram[playedAt.hour] += 1
      if (playedAt.dayOfWeek == DayOfWeek.SATURDAY || playedAt.dayOfWeek == DayOfWeek.SUNDAY) {
        stat.weekendPlays += 1
      } else {
        stat.weekdayPlays += 1
      }
      if (playedAt.hour >= 22 || playedAt.hour < 5) {
        stat.nightPlays += 1
      }
      if (playedAtEpochSecond >= recent30Cutoff) {
        stat.recentPlays30d += 1
      }
      if (playedAtEpochSecond >= recent90Cutoff) {
        stat.recentPlays90d += 1
      }
    }

    applyLateSessionMetrics(timestampedScrobbles, stats)

    return stats.values.map { stat ->
      val ageDays =
        (((nowEpochSecond - stat.firstPlayedAtEpochSecond).coerceAtLeast(0)) / SECONDS_PER_DAY)
          .coerceAtLeast(1)
      val historicalWindowDays = (ageDays - 30).coerceAtLeast(30)
      val historicalPlays = (stat.totalPlays - stat.recentPlays30d).coerceAtLeast(0)
      val expectedRecentPlays = historicalPlays.toDouble() * 30.0 / historicalWindowDays
      val recencySpikeRatio = (stat.recentPlays30d + 1.0) / (expectedRecentPlays + 1.0)
      val stabilityScore =
        (stat.activeYears.size * 10.0 +
            minOf(stat.totalPlays.toDouble(), 60.0) +
            minOf(stat.recentPlays90d.toDouble(), 20.0) * 2.0 +
            (if (stat.recentPlays30d > 0) 8.0 else 0.0) -
            kotlin.math.abs(recencySpikeRatio - 1.0) * 6.0)
          .coerceAtLeast(0.0)
      val noveltyScore =
        ((32.0 - (stat.totalPlays * 4.0)).coerceAtLeast(0.0) +
            minOf(recencySpikeRatio, 5.0) * 4.0 +
            when {
              ageDays <= 180 -> 18.0
              ageDays <= 365 -> 10.0
              else -> 0.0
            } +
            if (historicalPlays == 0 && stat.recentPlays90d > 0) 12.0 else 0.0)
          .coerceAtLeast(0.0)
      val totalPlays = stat.totalPlays.toDouble().coerceAtLeast(1.0)

      PrivateMoodSongStats(
        song = stat.song,
        normalizedKey = stat.normalizedKey,
        totalPlays = stat.totalPlays,
        firstPlayedAtEpochSecond = stat.firstPlayedAtEpochSecond,
        lastPlayedAtEpochSecond = stat.lastPlayedAtEpochSecond,
        activeYears = stat.activeYears.size,
        recentPlays30d = stat.recentPlays30d,
        recentPlays90d = stat.recentPlays90d,
        hourHistogram = stat.hourHistogram.toList(),
        weekendToWeekdayRatio = (stat.weekendPlays + 1.0) / (stat.weekdayPlays + 1.0),
        recencySpikeRatio = recencySpikeRatio,
        stabilityScore = stabilityScore,
        noveltyScore = noveltyScore,
        daytimePlayRatio = daytimePlayRatio(stat.hourHistogram, totalPlays),
        nightPlayRatio = stat.nightPlays / totalPlays,
        lateSessionRatio = stat.lateSessionPlays / totalPlays,
        nightPlays = stat.nightPlays,
        lateSessionPlays = stat.lateSessionPlays,
      )
    }
  }

  private fun applyLateSessionMetrics(
    scrobbles: List<Song>,
    stats: Map<Pair<String, String>, MutablePrivateMoodSongStats>,
  ) {
    if (scrobbles.isEmpty()) {
      return
    }

    val currentSession = mutableListOf<Song>()
    var previousPlayedAtEpochSecond: Long? = null
    scrobbles.forEach { song ->
      val playedAtEpochSecond = song.playedAtEpochSecond ?: return@forEach
      if (
        previousPlayedAtEpochSecond != null &&
          playedAtEpochSecond - previousPlayedAtEpochSecond!! > PRIVATE_MOOD_SESSION_GAP_SECONDS
      ) {
        markLateSessionPlays(currentSession, stats)
        currentSession.clear()
      }
      currentSession += song
      previousPlayedAtEpochSecond = playedAtEpochSecond
    }
    markLateSessionPlays(currentSession, stats)
  }

  private fun markLateSessionPlays(
    session: List<Song>,
    stats: Map<Pair<String, String>, MutablePrivateMoodSongStats>,
  ) {
    if (session.isEmpty()) {
      return
    }

    val lateStartIndex =
      when {
        session.size <= 1 -> session.size
        session.size <= 3 -> session.size - 1
        else -> maxOf(1, (session.size * 2) / 3)
      }
    session.drop(lateStartIndex).forEach { song ->
      stats[song.normalizedKey()]?.lateSessionPlays =
        (stats[song.normalizedKey()]?.lateSessionPlays ?: 0) + 1
    }
  }

  private fun selectAnchorCandidates(
    stats: List<PrivateMoodSongStats>,
    spotifySignals: PrivateMoodSpotifySignals,
    limit: Int = Int.MAX_VALUE,
  ): List<PrivateMoodCandidateSong> {
    return stats
      .asSequence()
      .filter {
        it.totalPlays >= PRIVATE_MOOD_ANCHOR_MIN_TOTAL_PLAYS &&
          (it.activeYears >= 2 || it.normalizedKey in spotifySignals.longTermKeys) &&
          (it.recentPlays90d > 0 || it.normalizedKey in spotifySignals.longTermKeys)
      }
      .map { stat ->
        val score =
          stat.stabilityScore * 4.0 +
            stat.totalPlays * 1.5 +
            stat.activeYears * 12.0 +
            stat.recentPlays90d * 2.0 +
            (if (stat.normalizedKey in spotifySignals.longTermKeys) 40.0 else 0.0) +
            (if (stat.normalizedKey in spotifySignals.midTermKeys) 18.0 else 0.0) +
            (if (stat.normalizedKey in spotifySignals.shortTermKeys) 10.0 else 0.0) -
            maxOf(0.0, stat.recencySpikeRatio - 2.5) * 10.0
        PrivateMoodCandidateSong(stat.song, stat.normalizedKey, score)
      }
      .sortedWith(privateMoodCandidateComparator())
      .take(limit)
      .toList()
  }

  private fun selectSurgeCandidates(
    stats: List<PrivateMoodSongStats>,
    spotifySignals: PrivateMoodSpotifySignals,
    limit: Int = Int.MAX_VALUE,
  ): List<PrivateMoodCandidateSong> {
    return stats
      .asSequence()
      .filter {
        it.recentPlays30d > 0 &&
          (it.recencySpikeRatio >= PRIVATE_MOOD_SURGE_MIN_SPIKE_RATIO ||
            it.normalizedKey in spotifySignals.shortTermKeys)
      }
      .map { stat ->
        val score =
          stat.recencySpikeRatio * 35.0 +
            stat.recentPlays30d * 8.0 +
            stat.recentPlays90d * 3.0 +
            stat.noveltyScore * 1.2 +
            (if (stat.normalizedKey in spotifySignals.shortTermKeys) 35.0 else 0.0) +
            (if (stat.normalizedKey in spotifySignals.midTermKeys) 12.0 else 0.0) -
            (if (stat.normalizedKey in spotifySignals.longTermKeys) 15.0 else 0.0)
        PrivateMoodCandidateSong(stat.song, stat.normalizedKey, score)
      }
      .sortedWith(privateMoodCandidateComparator())
      .take(limit)
      .toList()
  }

  private fun selectNightDriftCandidates(
    stats: List<PrivateMoodSongStats>,
    spotifySignals: PrivateMoodSpotifySignals,
    limit: Int = Int.MAX_VALUE,
  ): List<PrivateMoodCandidateSong> {
    return stats
      .asSequence()
      .filter {
        it.totalPlays >= PRIVATE_MOOD_NIGHT_DRIFT_MIN_TOTAL_PLAYS &&
          (it.nightPlayRatio >= PRIVATE_MOOD_NIGHT_RATIO_THRESHOLD ||
            it.lateSessionRatio >= PRIVATE_MOOD_LATE_SESSION_RATIO_THRESHOLD)
      }
      .map { stat ->
        val score =
          stat.nightPlayRatio * 70.0 +
            stat.lateSessionRatio * 55.0 +
            stat.nightPlays * 3.0 +
            stat.lateSessionPlays * 2.5 +
            stat.recentPlays90d * 1.5 +
            stat.stabilityScore * 0.5 +
            if (stat.normalizedKey in spotifySignals.shortTermKeys) 4.0 else 0.0
        PrivateMoodCandidateSong(stat.song, stat.normalizedKey, score)
      }
      .sortedWith(privateMoodCandidateComparator())
      .take(limit)
      .toList()
  }

  private fun selectHappyCandidates(
    stats: List<PrivateMoodSongStats>,
    spotifySignals: PrivateMoodSpotifySignals,
    limit: Int = Int.MAX_VALUE,
  ): List<PrivateMoodCandidateSong> {
    return stats
      .asSequence()
      .filter {
        it.recentPlays90d > 0 &&
          it.daytimePlayRatio >= PRIVATE_MOOD_HAPPY_MIN_DAYTIME_RATIO &&
          it.nightPlayRatio <= PRIVATE_MOOD_HAPPY_MAX_NIGHT_RATIO
      }
      .map { stat ->
        val score =
          stat.daytimePlayRatio * 70.0 +
            minOf(stat.weekendToWeekdayRatio, 3.0) * 18.0 +
            stat.recencySpikeRatio * 14.0 +
            stat.recentPlays30d * 5.0 +
            stat.recentPlays90d * 2.0 +
            stat.stabilityScore * 0.45 +
            (if (stat.normalizedKey in spotifySignals.shortTermKeys) 30.0 else 0.0) +
            (if (stat.normalizedKey in spotifySignals.midTermKeys) 14.0 else 0.0) -
            stat.nightPlayRatio * 25.0
        PrivateMoodCandidateSong(stat.song, stat.normalizedKey, score)
      }
      .sortedWith(privateMoodCandidateComparator())
      .take(limit)
      .toList()
  }

  private fun selectSadCandidates(
    stats: List<PrivateMoodSongStats>,
    spotifySignals: PrivateMoodSpotifySignals,
    limit: Int = Int.MAX_VALUE,
  ): List<PrivateMoodCandidateSong> {
    return stats
      .asSequence()
      .filter {
        it.totalPlays >= PRIVATE_MOOD_SAD_MIN_TOTAL_PLAYS &&
          (it.activeYears >= 2 || it.normalizedKey in spotifySignals.longTermKeys) &&
          (it.nightPlayRatio >= PRIVATE_MOOD_SAD_MIN_NIGHT_RATIO ||
            it.lateSessionRatio >= PRIVATE_MOOD_SAD_MIN_LATE_SESSION_RATIO)
      }
      .map { stat ->
        val score =
          stat.stabilityScore * 2.6 +
            stat.totalPlays * 1.5 +
            stat.activeYears * 10.0 +
            stat.nightPlayRatio * 42.0 +
            stat.lateSessionRatio * 24.0 +
            stat.recentPlays90d * 1.5 +
            (if (stat.normalizedKey in spotifySignals.longTermKeys) 28.0 else 0.0) +
            (if (stat.normalizedKey in spotifySignals.midTermKeys) 10.0 else 0.0) -
            stat.daytimePlayRatio * 18.0 -
            minOf(stat.weekendToWeekdayRatio, 3.0) * 5.0
        PrivateMoodCandidateSong(stat.song, stat.normalizedKey, score)
      }
      .sortedWith(privateMoodCandidateComparator())
      .take(limit)
      .toList()
  }

  private fun buildFrontierCandidates(
    statsByKey: Map<Pair<String, String>, PrivateMoodSongStats>,
    spotifySignals: PrivateMoodSpotifySignals,
    anchorCandidates: List<PrivateMoodCandidateSong>,
    playlistSize: Int,
  ): List<PrivateMoodCandidateSong> {
    val coreSongs =
      buildList {
          addAll(spotifySignals.longTermSongs)
          addAll(anchorCandidates.map { it.song })
          addAll(spotifySignals.midTermSongs)
        }
        .distinctBy { it.normalizedKey() }
        .take(PRIVATE_MOOD_FRONTIER_TRACK_SEED_LIMIT)
    val coreArtists =
      buildList {
          addAll(coreSongs.map { it.artist })
          addAll(spotifySignals.longTermSongs.map { it.artist })
        }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .take(PRIVATE_MOOD_FRONTIER_ARTIST_SEED_LIMIT)

    val similarArtistScores = LinkedHashMap<String, Double>()
    coreArtists.forEach { artist ->
      lastFmService.artistSimilar(artist, PRIVATE_MOOD_FRONTIER_SIMILAR_ARTIST_LIMIT).forEach {
        similarArtist ->
        val normalizedArtist = similarArtist.name.normalizeToken()
        if (normalizedArtist != artist.normalizeToken()) {
          similarArtistScores[normalizedArtist] =
            maxOf(similarArtistScores[normalizedArtist] ?: 0.0, similarArtist.match)
        }
      }
    }

    val frontierCandidates = LinkedHashMap<Pair<String, String>, PrivateMoodFrontierAccumulator>()
    coreSongs.forEach { song ->
      lastFmService
        .trackSimilar(song.artist, song.title, PRIVATE_MOOD_FRONTIER_SIMILAR_TRACK_LIMIT)
        .forEach { similarTrack ->
          val key = similarTrack.song.normalizedKey()
          if (key == song.normalizedKey()) {
            return@forEach
          }
          val accumulator =
            frontierCandidates.getOrPut(key) { PrivateMoodFrontierAccumulator(similarTrack.song) }
          accumulator.trackMatch += similarTrack.match.coerceAtLeast(0.0)
          accumulator.seedCount += 1
        }
    }

    statsByKey.values
      .asSequence()
      .filter {
        it.totalPlays in 1..PRIVATE_MOOD_FRONTIER_MAX_USER_PLAYS &&
          it.recentPlays90d > 0 &&
          it.song.artist.normalizeToken() in similarArtistScores.keys
      }
      .forEach { stat ->
        val accumulator =
          frontierCandidates.getOrPut(stat.normalizedKey) {
            PrivateMoodFrontierAccumulator(stat.song)
          }
        accumulator.artistMatch += similarArtistScores[stat.song.artist.normalizeToken()] ?: 0.0
        accumulator.seedCount += 1
      }

    return frontierCandidates.values
      .asSequence()
      .mapNotNull { candidate ->
        val stats = statsByKey[candidate.song.normalizedKey()]
        val historyPlayCount = stats?.totalPlays ?: 0
        if (historyPlayCount > PRIVATE_MOOD_FRONTIER_MAX_USER_PLAYS) {
          return@mapNotNull null
        }
        if (candidate.song.normalizedKey() in spotifySignals.allKeys) {
          return@mapNotNull null
        }
        val artistSimilarity = similarArtistScores[candidate.song.artist.normalizeToken()] ?: 0.0
        val noveltyScore = stats?.noveltyScore ?: 45.0
        val score =
          candidate.trackMatch * 40.0 +
            (candidate.artistMatch + artistSimilarity) * 25.0 +
            noveltyScore * 1.5 +
            (stats?.recentPlays90d ?: 0) * 3.0 -
            historyPlayCount * 6.0 -
            if ((stats?.recentPlays30d ?: 0) > 6) 15.0 else 0.0
        if (score <= 0.0) {
          return@mapNotNull null
        }
        PrivateMoodCandidateSong(candidate.song, candidate.song.normalizedKey(), score)
      }
      .sortedWith(privateMoodCandidateComparator())
      .take(maxOf(normalizedFrontierCandidateLimit(playlistSize), playlistSize))
      .toList()
  }

  private fun normalizedFrontierCandidateLimit(playlistSize: Int): Int {
    return maxOf(playlistSize * 4, PRIVATE_MOOD_FRONTIER_MIN_CANDIDATE_COUNT)
  }

  private fun daytimePlayRatio(hourHistogram: IntArray, totalPlays: Double): Double {
    val daytimePlays =
      (PRIVATE_MOOD_DAYTIME_START_HOUR until PRIVATE_MOOD_DAYTIME_END_HOUR).sumOf {
        hourHistogram[it]
      }
    return daytimePlays / totalPlays
  }

  private fun matchPrivateMoodTrackIds(
    candidates: List<PrivateMoodCandidateSong>,
    clientId: String,
    playlistSize: Int,
    excludedTrackIds: Set<String>,
  ): PrivateMoodMatchResult {
    val matchedTrackIds = mutableListOf<String>()
    val attemptedKeys = mutableSetOf<Pair<String, String>>()
    var candidateOffset = 0
    while (candidateOffset < candidates.size && matchedTrackIds.size < playlistSize) {
      val batch =
        candidates.drop(candidateOffset).take(PRIVATE_MOOD_SEARCH_BATCH_SIZE).also {
          attemptedKeys += it.map { candidate -> candidate.normalizedKey }
        }
      if (batch.isEmpty()) {
        break
      }
      val matchedBatch =
        runBlocking(Dispatchers.IO) {
          spotifySearchService.searchTrackIds(batch.map { it.song }, clientId)
        }
      matchedTrackIds +=
        matchedBatch
          .filterNot { it in excludedTrackIds || it in matchedTrackIds }
          .take(playlistSize - matchedTrackIds.size)
      candidateOffset += batch.size
    }
    return PrivateMoodMatchResult(matchedTrackIds, attemptedKeys)
  }

  private fun privateMoodCandidateComparator(): Comparator<PrivateMoodCandidateSong> {
    return compareByDescending<PrivateMoodCandidateSong> { it.score }
      .thenBy { it.song.artist.lowercase() }
      .thenBy { it.song.title.lowercase() }
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
    public: Boolean = true,
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

      val created = spotifyPlaylistService.createPlaylist(playlistName, clientId, public)
      val previous = existingPlaylists.putIfAbsent(playlistName, created)
      return previous ?: created
    }
  }

  private fun getYear() = currentYearProvider()

  private fun Track.toSong(): Song {
    return Song(artist = artists.firstOrNull()?.name.orEmpty(), title = name)
  }

  private fun String.normalizeToken(): String {
    return trim().lowercase().replace("\\s+".toRegex(), " ")
  }

  private fun Song.normalizedKey(): Pair<String, String> {
    return artist.normalizeToken() to title.normalizeToken()
  }

  companion object {
    internal const val DEFAULT_YEARLY_PARALLELISM = 4
    private const val FIRST_SUPPORTED_YEAR = 2005
    private const val FORGOTTEN_OBSESSIONS_PLAYLIST_NAME = "Forgotten Obsessions"
    private const val FORGOTTEN_OBSESSIONS_MIN_PLAY_COUNT = 5
    private const val FORGOTTEN_OBSESSIONS_MIN_DORMANT_DAYS = 180L
    private const val FORGOTTEN_OBSESSIONS_SEARCH_BATCH_SIZE = 100
    private const val FORGOTTEN_OBSESSIONS_TARGET_TRACK_COUNT = 50
    private const val PRIVATE_MOOD_PLAYLIST_PREFIX = "Private Mood - "
    private const val PRIVATE_MOOD_DEFAULT_PLAYLIST_SIZE = 50
    private const val PRIVATE_MOOD_MAX_PLAYLIST_SIZE = 100
    private const val PRIVATE_MOOD_SEARCH_BATCH_SIZE = 100
    private const val PRIVATE_MOOD_ANCHOR_MIN_TOTAL_PLAYS = 4
    private const val PRIVATE_MOOD_SURGE_MIN_SPIKE_RATIO = 1.35
    private const val PRIVATE_MOOD_HAPPY_MIN_DAYTIME_RATIO = 0.45
    private const val PRIVATE_MOOD_HAPPY_MAX_NIGHT_RATIO = 0.35
    private const val PRIVATE_MOOD_SAD_MIN_TOTAL_PLAYS = 4
    private const val PRIVATE_MOOD_SAD_MIN_NIGHT_RATIO = 0.20
    private const val PRIVATE_MOOD_SAD_MIN_LATE_SESSION_RATIO = 0.20
    private const val PRIVATE_MOOD_NIGHT_DRIFT_MIN_TOTAL_PLAYS = 3
    private const val PRIVATE_MOOD_NIGHT_RATIO_THRESHOLD = 0.35
    private const val PRIVATE_MOOD_LATE_SESSION_RATIO_THRESHOLD = 0.35
    private const val PRIVATE_MOOD_DAYTIME_START_HOUR = 6
    private const val PRIVATE_MOOD_DAYTIME_END_HOUR = 20
    private const val PRIVATE_MOOD_FRONTIER_MAX_USER_PLAYS = 5
    private const val PRIVATE_MOOD_FRONTIER_TRACK_SEED_LIMIT = 12
    private const val PRIVATE_MOOD_FRONTIER_ARTIST_SEED_LIMIT = 12
    private const val PRIVATE_MOOD_FRONTIER_SIMILAR_TRACK_LIMIT = 8
    private const val PRIVATE_MOOD_FRONTIER_SIMILAR_ARTIST_LIMIT = 8
    private const val PRIVATE_MOOD_FRONTIER_MIN_CANDIDATE_COUNT = 100
    private const val PRIVATE_MOOD_SESSION_GAP_SECONDS = 7_200L
    private const val SECONDS_PER_DAY = 86_400L
    private val PRIVATE_MOOD_REQUIRED_SCOPES =
      setOf("playlist-modify-private", "playlist-read-private")
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

  private data class MutablePrivateMoodSongStats(
    val song: Song,
    val normalizedKey: Pair<String, String>,
    var totalPlays: Int = 0,
    var firstPlayedAtEpochSecond: Long,
    var lastPlayedAtEpochSecond: Long,
    val activeYears: MutableSet<Int> = mutableSetOf(),
    var recentPlays30d: Int = 0,
    var recentPlays90d: Int = 0,
    val hourHistogram: IntArray = IntArray(24),
    var weekdayPlays: Int = 0,
    var weekendPlays: Int = 0,
    var nightPlays: Int = 0,
    var lateSessionPlays: Int = 0,
  )

  internal data class PrivateMoodSongStats(
    val song: Song,
    val normalizedKey: Pair<String, String>,
    val totalPlays: Int,
    val firstPlayedAtEpochSecond: Long,
    val lastPlayedAtEpochSecond: Long,
    val activeYears: Int,
    val recentPlays30d: Int,
    val recentPlays90d: Int,
    val hourHistogram: List<Int>,
    val weekendToWeekdayRatio: Double,
    val recencySpikeRatio: Double,
    val stabilityScore: Double,
    val noveltyScore: Double,
    val daytimePlayRatio: Double,
    val nightPlayRatio: Double,
    val lateSessionRatio: Double,
    val nightPlays: Int,
    val lateSessionPlays: Int,
  )

  internal data class PrivateMoodCandidateSong(
    val song: Song,
    val normalizedKey: Pair<String, String>,
    val score: Double,
  )

  private data class PrivateMoodSpotifySignals(
    val shortTermSongs: List<Song>,
    val midTermSongs: List<Song>,
    val longTermSongs: List<Song>,
    val shortTermKeys: Set<Pair<String, String>>,
    val midTermKeys: Set<Pair<String, String>>,
    val longTermKeys: Set<Pair<String, String>>,
  ) {
    val allKeys: Set<Pair<String, String>> = shortTermKeys + midTermKeys + longTermKeys
  }

  private data class PrivateMoodFrontierAccumulator(
    val song: Song,
    var trackMatch: Double = 0.0,
    var artistMatch: Double = 0.0,
    var seedCount: Int = 0,
  )

  private data class PrivateMoodMatchResult(
    val trackIds: List<String>,
    val attemptedKeys: Set<Pair<String, String>>,
  )

  private enum class PrivateMoodPlaylistKind(val label: String) {
    ANCHOR("Anchor"),
    HAPPY("Happy"),
    SAD("Sad"),
    SURGE("Surge"),
    NIGHT_DRIFT("Night Drift"),
    FRONTIER("Frontier");

    fun playlistName(): String {
      return "$PRIVATE_MOOD_PLAYLIST_PREFIX$label"
    }
  }
}

class PlaylistUpdateException(val playlistIds: List<String>, cause: Throwable) :
  RuntimeException(cause)
