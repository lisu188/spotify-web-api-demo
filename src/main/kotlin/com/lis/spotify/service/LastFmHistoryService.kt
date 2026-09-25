package com.lis.spotify.service

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.lis.spotify.AppEnvironment.LastFm
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder

@Service
class LastFmHistoryService(restTemplateBuilder: RestTemplateBuilder = RestTemplateBuilder()) {
  private val rest = restTemplateBuilder.withDefaultTimeouts().build()
  private val yearlyCache: Cache<String, LastFmYearSummary> =
    CacheBuilder.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).build()

  fun yearlyArtistHistory(
    user: String,
    fromYear: Int,
    toYear: Int,
    limit: Int = DEFAULT_TOP_ARTISTS_LIMIT,
  ): List<LastFmYearSummary> {
    val normalizedUser = user.trim()
    require(normalizedUser.isNotBlank()) { "user is required" }
    require(fromYear in MIN_YEAR..MAX_YEAR) { "fromYear is outside supported range" }
    require(toYear in MIN_YEAR..MAX_YEAR) { "toYear is outside supported range" }
    require(fromYear <= toYear) { "fromYear must be <= toYear" }
    require(toYear - fromYear + 1 <= MAX_YEAR_RANGE) { "year range is too large" }
    require(limit in 1..MAX_TOP_ARTISTS_LIMIT) {
      "limit must be between 1 and $MAX_TOP_ARTISTS_LIMIT"
    }

    return (fromYear..toYear).map { year -> yearlyArtistSummary(normalizedUser, year, limit) }
  }

  fun yearlyArtistSummary(
    user: String,
    year: Int,
    limit: Int = DEFAULT_TOP_ARTISTS_LIMIT,
  ): LastFmYearSummary {
    val normalizedUser = user.trim()
    val cacheKey = "${normalizedUser.lowercase()}|$year"
    val fullSummary =
      yearlyCache.getIfPresent(cacheKey)
        ?: fetchYear(normalizedUser, year).also { yearlyCache.put(cacheKey, it) }
    return fullSummary.copy(topArtists = fullSummary.topArtists.take(limit))
  }

  private fun fetchYear(user: String, year: Int): LastFmYearSummary {
    val from = LocalDate.of(year, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val to = LocalDate.of(year, 12, 31).atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC)
    val uri =
      UriComponentsBuilder.fromUriString(LastFm.API_URL)
        .queryParam("method", "user.getWeeklyArtistChart")
        .queryParam("api_key", LastFm.API_KEY)
        .queryParam("format", "json")
        .queryParam("user", user)
        .queryParam("from", from)
        .queryParam("to", to)
        .build()
        .toUri()

    val payload = rest.getForObject(uri, Map::class.java) as? Map<*, *> ?: emptyMap<Any, Any>()
    val chart = payload["weeklyartistchart"] as? Map<*, *>
    val artistItems =
      when (val artists = chart?.get("artist")) {
        is List<*> -> artists
        is Map<*, *> -> listOf(artists)
        else -> emptyList()
      }

    val artists =
      artistItems.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val name = map["name"] as? String ?: return@mapNotNull null
        val playcount =
          when (val value = map["playcount"]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
          }
        LastFmYearArtist(name = name, playcount = playcount)
      }

    return LastFmYearSummary(
      year = year,
      totalScrobbles = artists.sumOf { it.playcount },
      uniqueArtists = artists.size,
      topArtists = artists.sortedByDescending { it.playcount },
    )
  }

  companion object {
    const val DEFAULT_TOP_ARTISTS_LIMIT = 10
    const val MAX_TOP_ARTISTS_LIMIT = 50
    private const val MIN_YEAR = 2002
    private const val MAX_YEAR = 2100
    private const val MAX_YEAR_RANGE = 30
  }
}

data class LastFmYearArtist(val name: String, val playcount: Long)

data class LastFmYearSummary(
  val year: Int,
  val totalScrobbles: Long,
  val uniqueArtists: Int,
  val topArtists: List<LastFmYearArtist>,
)
