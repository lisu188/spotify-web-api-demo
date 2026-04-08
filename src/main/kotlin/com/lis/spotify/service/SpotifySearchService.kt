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
import com.lis.spotify.persistence.SpotifySearchCacheStore
import com.lis.spotify.persistence.StoredSpotifySearchCacheEntry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    private val WHITESPACE_REGEX = "\\s+".toRegex()
  }

  internal var maxParallelism = configuredMaxParallelism.coerceAtLeast(1)
  internal var cacheTtl = configuredCacheTtl

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

    val result =
      spotifyRestService.doGet<SearchResult>(
        SEARCH_URL,
        params = mapOf("q" to query, "type" to "track"),
        clientId = clientId,
      )
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
              result?.tracks?.items?.firstOrNull()?.id
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

  internal fun clearCache() {
    searchCache.invalidateAll()
  }
}
