/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.lis.spotify.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import com.lis.spotify.persistence.SpotifySearchCacheStore
import com.lis.spotify.persistence.StoredSpotifySearchCacheEntry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException

@Service
class SpotifySearchService(
  val spotifyRestService: SpotifyRestService,
  private val spotifySearchCacheStore: SpotifySearchCacheStore,
  private val clock: Clock,
  @Value("\${spotify.search.max-parallelism:64}")
  configuredMaxParallelism: Int = DEFAULT_SEARCH_MAX_PARALLELISM,
  @Value("\${spotify.search.cache-ttl:PT168H}") configuredCacheTtl: Duration = DEFAULT_CACHE_TTL,
) {
  companion object {
    val SEARCH_URL = "https://api.spotify.com/v1/search?q={q}&type={type}"
    internal const val DEFAULT_SEARCH_MAX_PARALLELISM = 64
    internal val DEFAULT_CACHE_TTL: Duration = Duration.ofDays(7)
    internal const val SPOTIFY_SEARCH_ATTEMPTS = 3
    internal const val SPOTIFY_SEARCH_RETRY_DELAY_MS = 250L
    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val DIACRITICS_REGEX = "\\p{M}+".toRegex()
    private val NON_ALPHANUMERIC_REGEX = "[^a-z0-9 ]+".toRegex()
    private val BRACKETED_SUFFIX_REGEX = "\\s*[\\[(].*?[\\])]\\s*".toRegex()
    private val VERSION_SUFFIX_REGEX =
      "\\s+-\\s+((\\d{2,4}\\s+)?)?(remaster(ed)?|live|mono|stereo|acoustic|demo|radio edit|edit|mix|version).*$"
        .toRegex()
  }

  internal var maxParallelism = configuredMaxParallelism.coerceAtLeast(1)
  internal var cacheTtl = configuredCacheTtl
  internal var sleeper: SpotifySearchSleeper = SpotifySearchSleeper { millis ->
    Thread.sleep(millis)
  }

  private val logger = LoggerFactory.getLogger(SpotifySearchService::class.java)
  private val mapper = jacksonObjectMapper()
  private val searchCache: Cache<String, StoredSpotifySearchCacheEntry> =
    CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).build()

  fun doSearch(song: Song, clientId: String): SearchResult? {
    logger.debug("doSearch single {} {}", song, clientId)
    val query = buildQuery(song)
    val cacheKey = cacheKey(clientId, query)
    val now = clock.instant()
    val cached = findFreshCacheEntry(cacheKey, now)
    if (cached != null) {
      logger.debug("doSearch persistent cache hit {}", cacheKey)
      return readCachedResult(cacheKey, cached)
    }

    val result = fetchSearchResult(query, clientId, cacheKey) ?: return null
    saveCacheEntry(cacheKey, clientId, query, result, now)
    logger.debug("doSearch single result stored for {}", cacheKey)
    return result
  }

  fun doSearch(values: List<Song>, clientId: String, progress: () -> Unit = {}): List<String> {
    logger.debug("doSearch batch {} {}", clientId, values.size)
    logger.info("doSearch: {} {}", clientId, values.size)
    lateinit var retVal: List<String>
    val time = measureTimeMillis {
      retVal = runBlocking(Dispatchers.IO) { searchTrackIds(values, clientId, progress) }
    }
    logger.debug("doSearch batch result {} items", retVal.size)
    logger.info("doSearch: {} {} took: {}", clientId, values.size, time)
    return retVal
  }

  internal suspend fun searchTrackIds(
    values: List<Song>,
    clientId: String,
    progress: () -> Unit = {},
  ): List<String> {
    val semaphore = Semaphore(maxParallelism.coerceAtLeast(1))
    return coroutineScope {
      values
        .map { song ->
          async(Dispatchers.IO) {
            semaphore.withPermit {
              val result = doSearch(song, clientId)
              progress()
              selectClosestTrackId(song, result)
            }
          }
        }
        .awaitAll()
        .filterNotNull()
        .distinct()
    }
  }

  private fun findFreshCacheEntry(cacheKey: String, now: Instant): StoredSpotifySearchCacheEntry? {
    val memoryEntry = searchCache.getIfPresent(cacheKey)
    if (memoryEntry != null) {
      return memoryEntry.takeIf { it.isFresh(now) }
        ?: run {
          searchCache.invalidate(cacheKey)
          null
        }
    }

    val storedEntry = spotifySearchCacheStore.findByKey(cacheKey) ?: return null
    if (!storedEntry.isFresh(now)) {
      return null
    }
    searchCache.put(cacheKey, storedEntry)
    return storedEntry
  }

  private fun saveCacheEntry(
    cacheKey: String,
    clientId: String,
    query: String,
    result: SearchResult,
    now: Instant,
  ) {
    val entry =
      StoredSpotifySearchCacheEntry(
        cacheKey = cacheKey,
        clientId = clientId,
        query = query,
        payloadJson = mapper.writeValueAsString(result),
        updatedAt = now,
        expiresAt = now.plus(cacheTtl),
      )
    spotifySearchCacheStore.save(entry)
    searchCache.put(cacheKey, entry)
  }

  private fun fetchSearchResult(query: String, clientId: String, cacheKey: String): SearchResult? {
    var attempt = 1
    while (true) {
      try {
        return spotifyRestService.doGet<SearchResult>(
          SEARCH_URL,
          params = mapOf("q" to query, "type" to "track"),
          clientId = clientId,
        )
      } catch (ex: HttpStatusCodeException) {
        val statusCode = ex.statusCode.value()
        if (statusCode >= 500 && attempt < SPOTIFY_SEARCH_ATTEMPTS) {
          val delayMs = retryDelayMs(attempt)
          logger.warn(
            "Spotify search transient error {} on attempt {}/{} for cache key {}, retrying in {}ms",
            statusCode,
            attempt,
            SPOTIFY_SEARCH_ATTEMPTS,
            cacheKey,
            delayMs,
          )
          sleeper.sleep(delayMs)
          attempt++
          continue
        }
        if (statusCode >= 500) {
          logger.warn(
            "Spotify search failed with status {} after {}/{} attempts for cache key {}, skipping track",
            statusCode,
            attempt,
            SPOTIFY_SEARCH_ATTEMPTS,
            cacheKey,
            ex,
          )
          return null
        }
        throw ex
      } catch (ex: ResourceAccessException) {
        if (attempt < SPOTIFY_SEARCH_ATTEMPTS) {
          val delayMs = retryDelayMs(attempt)
          logger.warn(
            "Spotify search network error on attempt {}/{} for cache key {}, retrying in {}ms",
            attempt,
            SPOTIFY_SEARCH_ATTEMPTS,
            cacheKey,
            delayMs,
            ex,
          )
          sleeper.sleep(delayMs)
          attempt++
          continue
        }
        logger.warn(
          "Spotify search network error after {}/{} attempts for cache key {}, skipping track",
          attempt,
          SPOTIFY_SEARCH_ATTEMPTS,
          cacheKey,
          ex,
        )
        return null
      }
    }
  }

  private fun readCachedResult(
    cacheKey: String,
    entry: StoredSpotifySearchCacheEntry,
  ): SearchResult? {
    return runCatching { mapper.readValue(entry.payloadJson, SearchResult::class.java) }
      .onFailure {
        logger.warn("Failed to deserialize cached Spotify search result {}", cacheKey, it)
        searchCache.invalidate(cacheKey)
      }
      .getOrNull()
  }

  private fun buildQuery(song: Song): String {
    return "track:${song.title.normalizeForQuery()} artist:${song.artist.normalizeForQuery()}"
  }

  internal fun selectClosestTrackId(song: Song, result: SearchResult?): String? {
    val bestMatch =
      result
        ?.tracks
        ?.items
        ?.map { track -> track to matchScore(song, track) }
        ?.filter { (_, score) -> score > 0 }
        ?.maxByOrNull { it.second } ?: return null
    return bestMatch.first.id
  }

  private fun matchScore(song: Song, track: Track): Int {
    val normalizedSongTitle = song.title.normalizeForMatch()
    val normalizedTrackTitle = track.name.normalizeForMatch()
    val simplifiedSongTitle = song.title.normalizeTitleForMatch()
    val simplifiedTrackTitle = track.name.normalizeTitleForMatch()

    val titleScore =
      when {
        normalizedSongTitle == normalizedTrackTitle -> 1000
        simplifiedSongTitle.isNotBlank() && simplifiedSongTitle == simplifiedTrackTitle -> 900
        else -> (tokenSimilarity(normalizedSongTitle, normalizedTrackTitle) * 600).toInt()
      }

    val normalizedSongArtist = song.artist.normalizeForMatch()
    val artistScores =
      track.artists.map { artist ->
        val normalizedTrackArtist = artist.name.normalizeForMatch()
        when {
          normalizedSongArtist == normalizedTrackArtist -> 1000
          else -> (tokenSimilarity(normalizedSongArtist, normalizedTrackArtist) * 700).toInt()
        }
      }
    val artistScore = artistScores.maxOrNull() ?: 0

    if (titleScore < 250 || artistScore < 250) {
      return 0
    }

    return titleScore + artistScore
  }

  private fun cacheKey(clientId: String, query: String): String {
    return sha256("$clientId|$query")
  }

  private fun sha256(value: String): String {
    val digest =
      MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private fun String.normalizeForQuery(): String {
    return trim().replace(WHITESPACE_REGEX, " ")
  }

  private fun String.normalizeForMatch(): String {
    val decomposed = Normalizer.normalize(trim().lowercase(), Normalizer.Form.NFD)
    return decomposed
      .replace(DIACRITICS_REGEX, "")
      .replace('&', ' ')
      .replace(NON_ALPHANUMERIC_REGEX, " ")
      .replace(WHITESPACE_REGEX, " ")
      .trim()
  }

  private fun String.normalizeTitleForMatch(): String {
    val simplified =
      trim().lowercase().replace(BRACKETED_SUFFIX_REGEX, " ").replace(VERSION_SUFFIX_REGEX, " ")
    return simplified.normalizeForMatch()
  }

  private fun tokenSimilarity(left: String, right: String): Double {
    if (left.isBlank() || right.isBlank()) {
      return 0.0
    }

    val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
    val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
      return 0.0
    }

    val intersectionSize = leftTokens.intersect(rightTokens).size
    val unionSize = leftTokens.union(rightTokens).size.coerceAtLeast(1)
    return intersectionSize.toDouble() / unionSize.toDouble()
  }

  internal fun clearCache() {
    searchCache.invalidateAll()
  }

  private fun retryDelayMs(attempt: Int): Long = SPOTIFY_SEARCH_RETRY_DELAY_MS * attempt
}

fun interface SpotifySearchSleeper {
  fun sleep(millis: Long)
}
