package com.lis.spotify.service

import com.fasterxml.jackson.core.JsonToken
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
  @Value("\${lastfm.recent-tracks.max-parallelism:4}")
  configuredRecentTracksParallelism: Int = DEFAULT_RECENT_TRACKS_MAX_PARALLELISM,
  @Value("\${lastfm.recent-tracks.cache-ttl:PT168H}")
  configuredCacheTtl: Duration = DEFAULT_CACHE_TTL,
  @Value("\${lastfm.recent-tracks.persistent-cache.enabled:false}")
  configuredPersistentRecentTracksCacheEnabled: Boolean =
    DEFAULT_PERSISTENT_RECENT_TRACKS_CACHE_ENABLED,
) {

  internal var rest = RestTemplate()
  internal var sleeper: LastFmSleeper = LastFmSleeper { millis -> Thread.sleep(millis) }
  internal var recentTracksParallelism = configuredRecentTracksParallelism.coerceAtLeast(1)
  internal var cacheTtl = configuredCacheTtl
  internal var persistentRecentTracksCacheEnabled = configuredPersistentRecentTracksCacheEnabled

  private val logger = LoggerFactory.getLogger(LastFmService::class.java)
  private val mapper = jacksonObjectMapper()
  private val recentTracksCache: Cache<String, StoredLastFmRecentTracksPage> =
    CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).build()
  private val similarTracksCache: Cache<String, List<LastFmSimilarTrack>> =
    CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).build()
  private val similarArtistsCache: Cache<String, List<LastFmSimilarArtist>> =
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
    val recentTracksPage = fetchRecentTracksPage(uri, "recent tracks for user $user", page)
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
  }

  private fun fetchRecentTracksPage(
    uri: URI,
    context: String,
    currentPage: Int,
  ): CachedLastFmRecentTracksPage {
    return executeWithRetries(context) {
      parseRecentTracksPage(rest.getForObject(uri, String::class.java).orEmpty(), currentPage)
    }
  }

  private fun fetchPayload(uri: URI, context: String): Map<String, Any?> {
    return executeWithRetries(context) {
      (rest.getForObject(uri, Map::class.java) as? Map<*, *>)
        ?.entries
        ?.associate { (key, value) -> key.toString() to value }
        .orEmpty()
    }
  }

  private fun <T> executeWithRetries(context: String, request: () -> T): T {
    var attempt = 1
    while (true) {
      try {
        return request()
      } catch (ex: HttpStatusCodeException) {
        val err = parseError(ex)
        if (err.code == AUTHENTICATION_REQUIRED_CODE) {
          logger.warn("Last.fm authentication expired during {}", context)
          throw AuthenticationRequiredException("LASTFM")
        }
        if (shouldRetry(err, ex.statusCode.value(), attempt)) {
          val delayMs = retryDelayMs(attempt)
          logger.warn(
            "Last.fm transient error {} {} on attempt {}/{} for {}, retrying in {}ms",
            err.code,
            err.message,
            attempt,
            LASTFM_FETCH_ATTEMPTS,
            context,
            delayMs,
          )
          sleeper.sleep(delayMs)
          attempt++
          continue
        }
        logger.error("Last.fm error during {}: {} {}", context, err.code, err.message)
        throw err
      } catch (ex: ResourceAccessException) {
        if (attempt < LASTFM_FETCH_ATTEMPTS) {
          val delayMs = retryDelayMs(attempt)
          logger.warn(
            "Last.fm network error on attempt {}/{} for {}, retrying in {}ms",
            attempt,
            LASTFM_FETCH_ATTEMPTS,
            context,
            delayMs,
            ex,
          )
          sleeper.sleep(delayMs)
          attempt++
          continue
        }
        logger.error("Last.fm network error during {}", context, ex)
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
      val songs = mutableListOf<Song>()
      appendSongsWithinLimit(songs, firstPage.songs, limit)
      var fetchedPages = 1
      val pageBatches = pagesToFetch.chunked(recentTracksParallelism.coerceAtLeast(1))

      if (songs.size < limit && pageBatches.isNotEmpty()) {
        var limitReached = false
        for ((index, pageBatch) in pageBatches.withIndex()) {
          val batchResults = coroutineScope {
            pageBatch
              .map { page ->
                async(Dispatchers.IO) {
                  page to fetchRecent(lastFmLogin, from, to, page, sessionKey)
                }
              }
              .awaitAll()
              .sortedBy { it.first }
          }
          fetchedPages += batchResults.size
          for ((_, pageResult) in batchResults) {
            if (appendSongsWithinLimit(songs, pageResult.songs, limit)) {
              limitReached = true
              break
            }
          }
          logger.debug(
            "yearlyChartlist {} {} completed batch {}/{} ({} pages fetched, {} songs accumulated)",
            lastFmLogin,
            year,
            index + 1,
            pageBatches.size,
            fetchedPages,
            songs.size,
          )
          if (limitReached) {
            break
          }
        }
      }

      logger.debug(
        "yearlyChartlist {} {} fetched {} pages and returned {} songs",
        lastFmLogin,
        year,
        fetchedPages,
        songs.size,
      )
      songs
    }
  }

  fun globalChartlist(lastFmLogin: String, page: Int = 1): List<Song> {
    logger.info("Fetching global chartlist for user {}", lastFmLogin)
    logger.debug("globalChartlist {} {}", lastFmLogin, page)
    return yearlyChartlist("", 1970, lastFmLogin, startPage = page)
  }

  fun trackSimilar(artist: String, track: String, limit: Int = 20): List<LastFmSimilarTrack> {
    require(artist.isNotBlank()) { "artist is required" }
    require(track.isNotBlank()) { "track is required" }
    val normalizedLimit = limit.coerceIn(1, 100)
    val cacheKey = sha256("track.getSimilar|${artist.trim()}|${track.trim()}|$normalizedLimit")
    similarTracksCache.getIfPresent(cacheKey)?.let { cached ->
      logger.debug("Last.fm similar track cache hit {}", cacheKey)
      return cached
    }

    val payload =
      fetchPayload(
        buildUri(
          "track.getSimilar",
          mapOf(
            "artist" to artist.trim(),
            "track" to track.trim(),
            "limit" to normalizedLimit,
            "autocorrect" to 1,
          ),
          null,
        ),
        "similar tracks for $artist - $track",
      )
    val similarTracks = parseSimilarTracks(payload, normalizedLimit)
    similarTracksCache.put(cacheKey, similarTracks)
    return similarTracks
  }

  fun artistSimilar(artist: String, limit: Int = 20): List<LastFmSimilarArtist> {
    require(artist.isNotBlank()) { "artist is required" }
    val normalizedLimit = limit.coerceIn(1, 100)
    val cacheKey = sha256("artist.getSimilar|${artist.trim()}|$normalizedLimit")
    similarArtistsCache.getIfPresent(cacheKey)?.let { cached ->
      logger.debug("Last.fm similar artist cache hit {}", cacheKey)
      return cached
    }

    val payload =
      fetchPayload(
        buildUri(
          "artist.getSimilar",
          mapOf("artist" to artist.trim(), "limit" to normalizedLimit, "autocorrect" to 1),
          null,
        ),
        "similar artists for $artist",
      )
    val similarArtists = parseSimilarArtists(payload, normalizedLimit)
    similarArtistsCache.put(cacheKey, similarArtists)
    return similarArtists
  }

  private fun parseRecentTracksPage(
    payload: String,
    currentPage: Int,
  ): CachedLastFmRecentTracksPage {
    if (payload.isBlank()) {
      return CachedLastFmRecentTracksPage(currentPage, emptyList())
    }

    mapper.factory.createParser(payload).use { parser ->
      var totalPages = currentPage
      val songs = mutableListOf<Song>()

      while (parser.nextToken() != null) {
        if (parser.currentToken == JsonToken.FIELD_NAME && parser.currentName() == "recenttracks") {
          parser.nextToken()
          totalPages = parseRecentTracksObject(parser, currentPage, songs)
        }
      }

      return CachedLastFmRecentTracksPage(
        totalPages = totalPages.coerceAtLeast(currentPage),
        songs = songs,
      )
    }
  }

  private fun parseRecentTracksObject(
    parser: com.fasterxml.jackson.core.JsonParser,
    currentPage: Int,
    songs: MutableList<Song>,
  ): Int {
    if (parser.currentToken != JsonToken.START_OBJECT) {
      parser.skipChildren()
      return currentPage
    }

    var totalPages = currentPage
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken != JsonToken.FIELD_NAME) {
        continue
      }

      val fieldName = parser.currentName()
      parser.nextToken()
      when (fieldName) {
        "@attr" -> totalPages = parseRecentTracksAttributes(parser, currentPage)
        "track" -> parseRecentTrackItems(parser, songs)
        else -> parser.skipChildren()
      }
    }

    return totalPages
  }

  private fun parseRecentTracksAttributes(
    parser: com.fasterxml.jackson.core.JsonParser,
    currentPage: Int,
  ): Int {
    if (parser.currentToken != JsonToken.START_OBJECT) {
      parser.skipChildren()
      return currentPage
    }

    var totalPages = currentPage
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken != JsonToken.FIELD_NAME) {
        continue
      }

      val fieldName = parser.currentName()
      parser.nextToken()
      when (fieldName) {
        "totalPages" -> totalPages = parser.valueAsString?.toIntOrNull() ?: currentPage
        else -> parser.skipChildren()
      }
    }

    return totalPages
  }

  private fun parseRecentTrackItems(
    parser: com.fasterxml.jackson.core.JsonParser,
    songs: MutableList<Song>,
  ) {
    when (parser.currentToken) {
      JsonToken.START_ARRAY -> {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          parseRecentTrackItem(parser)?.let(songs::add)
        }
      }
      JsonToken.START_OBJECT -> {
        parseRecentTrackItem(parser)?.let(songs::add)
      }
      else -> parser.skipChildren()
    }
  }

  private fun parseRecentTrackItem(parser: com.fasterxml.jackson.core.JsonParser): Song? {
    if (parser.currentToken != JsonToken.START_OBJECT) {
      parser.skipChildren()
      return null
    }

    var artist: String? = null
    var title: String? = null
    var playedAtEpochSecond: Long? = null

    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken != JsonToken.FIELD_NAME) {
        continue
      }

      val fieldName = parser.currentName()
      parser.nextToken()
      when (fieldName) {
        "artist" -> artist = parseRecentTrackArtist(parser)
        "name" -> title = parser.valueAsString
        "date" -> playedAtEpochSecond = parseRecentTrackPlayedAt(parser)
        else -> parser.skipChildren()
      }
    }

    if (artist.isNullOrBlank() || title.isNullOrBlank()) {
      return null
    }

    return Song(artist = artist, title = title, playedAtEpochSecond = playedAtEpochSecond)
  }

  private fun parseRecentTrackArtist(parser: com.fasterxml.jackson.core.JsonParser): String? {
    return when (parser.currentToken) {
      JsonToken.START_OBJECT -> {
        var artist: String? = null
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          if (parser.currentToken != JsonToken.FIELD_NAME) {
            continue
          }

          val fieldName = parser.currentName()
          parser.nextToken()
          when (fieldName) {
            "#text",
            "name" -> artist = parser.valueAsString
            else -> parser.skipChildren()
          }
        }
        artist
      }
      JsonToken.VALUE_STRING -> parser.valueAsString
      else -> {
        parser.skipChildren()
        null
      }
    }
  }

  private fun parseRecentTrackPlayedAt(parser: com.fasterxml.jackson.core.JsonParser): Long? {
    if (parser.currentToken != JsonToken.START_OBJECT) {
      parser.skipChildren()
      return null
    }

    var playedAtEpochSecond: Long? = null
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      if (parser.currentToken != JsonToken.FIELD_NAME) {
        continue
      }

      val fieldName = parser.currentName()
      parser.nextToken()
      when (fieldName) {
        "uts" -> playedAtEpochSecond = parser.valueAsString?.toLongOrNull()
        else -> parser.skipChildren()
      }
    }

    return playedAtEpochSecond
  }

  private fun parseSimilarTracks(payload: Map<String, Any?>, limit: Int): List<LastFmSimilarTrack> {
    val similarTracks = payload["similartracks"] as? Map<*, *> ?: return emptyList()
    val trackItems =
      when (val tracks = similarTracks["track"]) {
        is List<*> -> tracks
        is Map<*, *> -> listOf(tracks)
        else -> emptyList()
      }

    return trackItems
      .mapNotNull { track ->
        val map = track as? Map<*, *> ?: return@mapNotNull null
        val artist =
          when (val artistValue = map["artist"]) {
            is Map<*, *> -> artistValue["name"] as? String
            is String -> artistValue
            else -> null
          } ?: return@mapNotNull null
        val title = map["name"] as? String ?: return@mapNotNull null
        val match = parseMatchValue(map["match"])
        LastFmSimilarTrack(song = Song(artist = artist, title = title), match = match)
      }
      .filter { it.song.artist.isNotBlank() && it.song.title.isNotBlank() }
      .distinctBy { it.song.artist.trim().lowercase() to it.song.title.trim().lowercase() }
      .take(limit)
  }

  private fun parseSimilarArtists(
    payload: Map<String, Any?>,
    limit: Int,
  ): List<LastFmSimilarArtist> {
    val similarArtists = payload["similarartists"] as? Map<*, *> ?: return emptyList()
    val artistItems =
      when (val artists = similarArtists["artist"]) {
        is List<*> -> artists
        is Map<*, *> -> listOf(artists)
        else -> emptyList()
      }

    return artistItems
      .mapNotNull { artist ->
        val map = artist as? Map<*, *> ?: return@mapNotNull null
        val name = map["name"] as? String ?: return@mapNotNull null
        LastFmSimilarArtist(name = name, match = parseMatchValue(map["match"]))
      }
      .filter { it.name.isNotBlank() }
      .distinctBy { it.name.trim().lowercase() }
      .take(limit)
  }

  private fun parseMatchValue(value: Any?): Double {
    return when (value) {
      is Number -> value.toDouble()
      is String -> value.toDoubleOrNull()
      else -> null
    } ?: 0.0
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

  private fun appendSongsWithinLimit(
    accumulatedSongs: MutableList<Song>,
    songs: List<Song>,
    limit: Int,
  ): Boolean {
    if (songs.isEmpty()) {
      return limit != Int.MAX_VALUE && accumulatedSongs.size >= limit
    }
    if (limit == Int.MAX_VALUE) {
      accumulatedSongs += songs
      return false
    }

    val remaining = (limit - accumulatedSongs.size).coerceAtLeast(0)
    if (remaining == 0) {
      return true
    }

    accumulatedSongs += songs.take(remaining)
    return accumulatedSongs.size >= limit
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
    recentTracksCache.put(cacheKey, entry)
    if (!persistentRecentTracksCacheEnabled) {
      return
    }

    try {
      lastFmRecentTracksCacheStore.save(entry)
    } catch (ex: Exception) {
      logger.warn("Failed to persist Last.fm recent-tracks cache {}", cacheKey, ex)
    }
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

    if (!persistentRecentTracksCacheEnabled) {
      return null
    }

    val storedEntry =
      try {
        lastFmRecentTracksCacheStore.findByKey(cacheKey)
      } catch (ex: Exception) {
        logger.warn("Failed to load cached Last.fm recent-tracks page {}", cacheKey, ex)
        null
      } ?: return null
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
    internal const val DEFAULT_RECENT_TRACKS_MAX_PARALLELISM = 4
    internal val DEFAULT_CACHE_TTL: Duration = Duration.ofDays(7)
    internal const val DEFAULT_PERSISTENT_RECENT_TRACKS_CACHE_ENABLED = false
    private const val AUTHENTICATION_REQUIRED_CODE = 17
    private const val TRANSIENT_BACKEND_ERROR_CODE = 8
    private const val RECENT_TRACKS_PAGE_SIZE = 200
  }
}

fun interface LastFmSleeper {
  fun sleep(millis: Long)
}

class LastFmException(val code: Int, override val message: String) : RuntimeException(message)

data class LastFmSimilarTrack(val song: Song, val match: Double)

data class LastFmSimilarArtist(val name: String, val match: Double)
