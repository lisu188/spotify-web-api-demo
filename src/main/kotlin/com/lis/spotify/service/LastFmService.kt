package com.lis.spotify.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.lis.spotify.AppEnvironment.LastFm
import com.lis.spotify.domain.Song
import com.lis.spotify.persistence.CachedLastFmRecentTracksPage
import com.lis.spotify.persistence.LastFmRecentTracksCacheStore
import com.lis.spotify.persistence.StoredLastFmRecentTracksPage
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
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
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

@Service
class LastFmService(
  private val lastFmAuthService: LastFmAuthenticationService,
  private val lastFmRecentTracksCacheStore: LastFmRecentTracksCacheStore,
  private val clock: Clock,
  @Value("\${lastfm.recent-tracks.max-parallelism:16}")
  configuredRecentTracksParallelism: Int = DEFAULT_RECENT_TRACKS_MAX_PARALLELISM,
  @Value("\${lastfm.recent-tracks.cache-ttl:PT168H}")
  configuredCacheTtl: Duration = DEFAULT_CACHE_TTL,
) {

  internal var rest = RestTemplate()
  internal var sleeper: LastFmSleeper = LastFmSleeper { millis -> Thread.sleep(millis) }
  internal var recentTracksParallelism = configuredRecentTracksParallelism.coerceAtLeast(1)
  internal var cacheTtl = configuredCacheTtl

  private val logger = LoggerFactory.getLogger(LastFmService::class.java)
  private val mapper = jacksonObjectMapper()
  private val recentTracksCache: Cache<String, StoredLastFmRecentTracksPage> =
    CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).build()

  private fun buildUri(method: String, params: Map<String, Any>, sessionKey: String?): URI {
    var builder =
      UriComponentsBuilder.fromUriString(LastFm.API_URL)
        .queryParam("method", method)
        .queryParam("api_key", LastFm.API_KEY)
        .queryParam("format", "json")
    if (!sessionKey.isNullOrBlank()) {
      builder = builder.queryParam("sk", sessionKey)
    }
    params.forEach { (key, value) -> builder = builder.queryParam(key, value) }
    return builder.build().toUri()
  }

  private fun fetchRecent(
    user: String,
    from: Long,
    to: Long,
    page: Int,
    sessionKey: String?,
  ): CachedLastFmRecentTracksPage {
    if (user.isBlank()) throw LastFmException(400, "user is required")
    require(from <= to) { "from must be <= to" }
    val cacheKey = cacheKey(user, from, to, page, sessionKey)
    val now = clock.instant()
    findFreshRecentTracksPage(cacheKey, now)?.let { cached ->
      readCachedRecentTracksPage(cacheKey, cached)?.let {
        logger.debug("Last.fm recent tracks cache hit {}", cacheKey)
        return it
      }
    }

    val uri =
      buildUri(
        "user.getRecentTracks",
        mapOf(
          "user" to user,
          "from" to from,
          "to" to to,
          "page" to page,
          "limit" to RECENT_TRACKS_PAGE_SIZE,
        ),
        sessionKey,
      )
    var attempt = 1
    while (true) {
      try {
        val data =
          (rest.getForObject(uri, Map::class.java) as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()
        val recentTracksPage = parseRecentTracksPage(data, page)
        saveRecentTracksPage(
          cacheKey = cacheKey,
          user = user,
          from = from,
          to = to,
          page = page,
          sessionKey = sessionKey,
          recentTracksPage = recentTracksPage,
          now = now,
        )
        return recentTracksPage
      } catch (ex: HttpStatusCodeException) {
        val err = parseError(ex)
        if (err.code == AUTHENTICATION_REQUIRED_CODE) {
          logger.warn("Last.fm authentication expired for user {}", user)
          throw AuthenticationRequiredException("LASTFM")
        }
        if (shouldRetry(err, ex.statusCode.value(), attempt)) {
          val delayMs = retryDelayMs(attempt)
          logger.warn(
            "Last.fm transient error {} {} on attempt {}/{} for user {}, retrying in {}ms",
            err.code,
            err.message,
            attempt,
            LASTFM_FETCH_ATTEMPTS,
            user,
            delayMs,
          )
          sleeper.sleep(delayMs)
          attempt++
          continue
        }
        logger.error("Last.fm error {} {}", err.code, err.message)
        throw err
      } catch (ex: ResourceAccessException) {
        if (attempt < LASTFM_FETCH_ATTEMPTS) {
          val delayMs = retryDelayMs(attempt)
          logger.warn(
            "Last.fm network error on attempt {}/{} for user {}, retrying in {}ms",
            attempt,
            LASTFM_FETCH_ATTEMPTS,
            user,
            delayMs,
            ex,
          )
          sleeper.sleep(delayMs)
          attempt++
          continue
        }
        logger.error("Last.fm network error", ex)
        throw LastFmException(503, ex.message ?: "I/O error")
      }
    }
  }

  private fun parseError(ex: HttpStatusCodeException): LastFmException {
    return try {
      val body = mapper.readValue(ex.responseBodyAsString, Map::class.java) as Map<*, *>
      val code = (body["error"] as? Int) ?: ex.statusCode.value()
      val msg = body["message"] as? String ?: ex.message
      LastFmException(code, msg ?: "error")
    } catch (_: Exception) {
      LastFmException(ex.statusCode.value(), ex.message ?: "error")
    }
  }

  fun userExists(lastFmLogin: String): Boolean {
    logger.info("Checking Last.fm user {}", lastFmLogin)
    logger.debug("userExists {}", lastFmLogin)
    if (lastFmLogin.isBlank()) throw LastFmException(400, "user is required")
    val uri = buildUri("user.getInfo", mapOf("user" to lastFmLogin), null)
    return try {
      rest.getForObject(uri, Map::class.java)
      true
    } catch (ex: HttpStatusCodeException) {
      val err = parseError(ex)
      return if (err.code == 6) {
        logger.info("User {} not found", lastFmLogin)
        false
      } else {
        logger.error("Last.fm error {} {}", err.code, err.message)
        throw err
      }
    } catch (ex: ResourceAccessException) {
      logger.error("Last.fm network error", ex)
      throw LastFmException(503, ex.message ?: "I/O error")
    }
  }

  fun yearlyChartlist(
    spotifyClientId: String,
    year: Int,
    lastFmLogin: String,
    limit: Int = Int.MAX_VALUE,
    startPage: Int = 1,
  ): List<Song> {
    logger.info("Fetching yearly chartlist for user {} year {}", lastFmLogin, year)
    logger.debug("yearlyChartlist {} {} {}", spotifyClientId, year, lastFmLogin)
    if (lastFmLogin.isBlank()) throw LastFmException(400, "user is required")
    val from = LocalDate.of(year, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val to = LocalDate.of(year, 12, 31).atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC)
    val sessionKey = lastFmAuthService.getSessionKey(lastFmLogin)

    return runBlocking(Dispatchers.IO) {
      val firstPage = fetchRecent(lastFmLogin, from, to, startPage, sessionKey)
      if (firstPage.songs.isEmpty()) {
        return@runBlocking emptyList()
      }

      val pagesToFetch = pagesToFetch(firstPage.totalPages, startPage, limit)
      val pageResults = mutableListOf(startPage to firstPage)
      if (pagesToFetch.isNotEmpty()) {
        val semaphore = Semaphore(recentTracksParallelism.coerceAtLeast(1))
        pageResults += coroutineScope {
          pagesToFetch
            .map { page ->
              async(Dispatchers.IO) {
                semaphore.withPermit {
                  page to fetchRecent(lastFmLogin, from, to, page, sessionKey)
                }
              }
            }
            .awaitAll()
        }
      }

      val songs = pageResults.sortedBy { it.first }.flatMap { it.second.songs }
      val result = if (limit == Int.MAX_VALUE) songs else songs.take(limit)
      logger.debug("yearlyChartlist {} {} fetched {} pages", lastFmLogin, year, pageResults.size)
      result
    }
  }

  fun globalChartlist(lastFmLogin: String, page: Int = 1): List<Song> {
    logger.info("Fetching global chartlist for user {}", lastFmLogin)
    logger.debug("globalChartlist {} {}", lastFmLogin, page)
    return yearlyChartlist("", 1970, lastFmLogin, startPage = page)
  }

  private fun parseRecentTracksPage(
    payload: Map<String, Any?>,
    currentPage: Int,
  ): CachedLastFmRecentTracksPage {
    val recentTracks =
      payload["recenttracks"] as? Map<*, *> ?: return CachedLastFmRecentTracksPage(1, emptyList())
    val attr = recentTracks["@attr"] as? Map<*, *>
    val totalPages =
      when (val value = attr?.get("totalPages")) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
      } ?: currentPage

    val trackItems =
      when (val tracks = recentTracks["track"]) {
        is List<*> -> tracks
        is Map<*, *> -> listOf(tracks)
        else -> emptyList()
      }

    val songs =
      trackItems.mapNotNull { track ->
        val map = track as? Map<*, *> ?: return@mapNotNull null
        val artist =
          (map["artist"] as? Map<*, *>)?.get("#text") as? String ?: return@mapNotNull null
        val title = map["name"] as? String ?: return@mapNotNull null
        Song(
          artist = artist,
          title = title,
          playedAtEpochSecond = extractPlayedAtEpochSecond(map["date"]),
        )
      }
    return CachedLastFmRecentTracksPage(
      totalPages = totalPages.coerceAtLeast(currentPage),
      songs = songs,
    )
  }

  private fun extractPlayedAtEpochSecond(value: Any?): Long? {
    val date = value as? Map<*, *> ?: return null
    return when (val uts = date["uts"]) {
      is Number -> uts.toLong()
      is String -> uts.toLongOrNull()
      else -> null
    }
  }

  private fun pagesToFetch(totalPages: Int, startPage: Int, limit: Int): List<Int> {
    val availableLastPage = totalPages.coerceAtLeast(startPage)
    val maxPagesForLimit =
      if (limit == Int.MAX_VALUE) {
        Int.MAX_VALUE
      } else {
        ((limit - 1) / RECENT_TRACKS_PAGE_SIZE) + 1
      }
    val lastPage =
      if (maxPagesForLimit == Int.MAX_VALUE) {
        availableLastPage
      } else {
        minOf(availableLastPage, startPage + maxPagesForLimit - 1)
      }
    if (lastPage <= startPage) {
      return emptyList()
    }
    return (startPage + 1..lastPage).toList()
  }

  private fun saveRecentTracksPage(
    cacheKey: String,
    user: String,
    from: Long,
    to: Long,
    page: Int,
    sessionKey: String?,
    recentTracksPage: CachedLastFmRecentTracksPage,
    now: Instant,
  ) {
    val entry =
      StoredLastFmRecentTracksPage(
        cacheKey = cacheKey,
        login = user,
        from = from,
        to = to,
        page = page,
        sessionKey = sessionKey,
        payloadJson = mapper.writeValueAsString(recentTracksPage),
        updatedAt = now,
        expiresAt = now.plus(cacheTtl),
      )
    lastFmRecentTracksCacheStore.save(entry)
    recentTracksCache.put(cacheKey, entry)
  }

  private fun findFreshRecentTracksPage(
    cacheKey: String,
    now: Instant,
  ): StoredLastFmRecentTracksPage? {
    val memoryEntry = recentTracksCache.getIfPresent(cacheKey)
    if (memoryEntry != null) {
      return memoryEntry.takeIf { it.isFresh(now) }
        ?: run {
          recentTracksCache.invalidate(cacheKey)
          null
        }
    }

    val storedEntry = lastFmRecentTracksCacheStore.findByKey(cacheKey) ?: return null
    if (!storedEntry.isFresh(now)) {
      return null
    }
    recentTracksCache.put(cacheKey, storedEntry)
    return storedEntry
  }

  private fun readCachedRecentTracksPage(
    cacheKey: String,
    entry: StoredLastFmRecentTracksPage,
  ): CachedLastFmRecentTracksPage? {
    return runCatching {
        mapper.readValue(entry.payloadJson, CachedLastFmRecentTracksPage::class.java)
      }
      .map { page ->
        val fallbackPlayedAtEpochSecond = entry.to.takeIf { it > 0 }
        if (fallbackPlayedAtEpochSecond == null) {
          page
        } else {
          page.copy(
            songs =
              page.songs.map { song ->
                if (song.playedAtEpochSecond != null) {
                  song
                } else {
                  song.copy(playedAtEpochSecond = fallbackPlayedAtEpochSecond)
                }
              }
          )
        }
      }
      .onFailure {
        logger.warn("Failed to deserialize cached Last.fm recent-tracks page {}", cacheKey, it)
        recentTracksCache.invalidate(cacheKey)
      }
      .getOrNull()
  }

  private fun cacheKey(user: String, from: Long, to: Long, page: Int, sessionKey: String?): String {
    return sha256("$user|$from|$to|$page|${sessionKey.orEmpty()}")
  }

  private fun sha256(value: String): String {
    val digest =
      MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private fun shouldRetry(error: LastFmException, statusCode: Int, attempt: Int): Boolean {
    if (attempt >= LASTFM_FETCH_ATTEMPTS) {
      return false
    }
    return statusCode >= 500 || error.code == TRANSIENT_BACKEND_ERROR_CODE
  }

  private fun retryDelayMs(attempt: Int): Long = LASTFM_RETRY_DELAY_MS * attempt

  companion object {
    internal const val LASTFM_FETCH_ATTEMPTS = 3
    internal const val LASTFM_RETRY_DELAY_MS = 250L
    internal const val DEFAULT_RECENT_TRACKS_MAX_PARALLELISM = 16
    internal val DEFAULT_CACHE_TTL: Duration = Duration.ofDays(7)
    private const val AUTHENTICATION_REQUIRED_CODE = 17
    private const val TRANSIENT_BACKEND_ERROR_CODE = 8
    private const val RECENT_TRACKS_PAGE_SIZE = 200
  }
}

fun interface LastFmSleeper {
  fun sleep(millis: Long)
}

class LastFmException(val code: Int, override val message: String) : RuntimeException(message)
