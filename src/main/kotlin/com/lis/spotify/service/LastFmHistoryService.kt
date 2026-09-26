package com.lis.spotify.service

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.lis.spotify.AppEnvironment.LastFm
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder

@Service
class LastFmHistoryService(restTemplateBuilder: RestTemplateBuilder = RestTemplateBuilder()) {
  private val rest = restTemplateBuilder.withDefaultTimeouts().build()
  private val yearlyCache: Cache<String, LastFmYearSummary> =
    CacheBuilder.newBuilder().expireAfterWrite(24, TimeUnit.HOURS).build()
  private val monthlyCache: Cache<String, LastFmMonthSummary> =
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

  fun monthlyArtistHistory(
    user: String,
    fromMonth: YearMonth,
    toMonth: YearMonth,
    limit: Int = MAX_TOP_ARTISTS_LIMIT,
  ): List<LastFmMonthSummary> {
    val normalizedUser = user.trim()
    require(normalizedUser.isNotBlank()) { "user is required" }
    require(!fromMonth.isBefore(YearMonth.of(MIN_YEAR, 1))) { "fromMonth is outside supported range" }
    require(!toMonth.isAfter(YearMonth.of(MAX_YEAR, 12))) { "toMonth is outside supported range" }
    require(!fromMonth.isAfter(toMonth)) { "fromMonth must be <= toMonth" }
    val monthCount = fromMonth.until(toMonth, java.time.temporal.ChronoUnit.MONTHS) + 1
    require(monthCount <= MAX_MONTH_RANGE) { "month range is too large" }
    require(limit in 1..MAX_TOP_ARTISTS_LIMIT) {
      "limit must be between 1 and $MAX_TOP_ARTISTS_LIMIT"
    }

    val months =
      generateSequence(fromMonth) { current ->
          current.plusMonths(1).takeUnless { it.isAfter(toMonth) }
        }
        .toList()

    return runBlocking(Dispatchers.IO) {
      months
        .chunked(MONTHLY_PARALLELISM)
        .flatMap { batch ->
          coroutineScope {
              batch.map { month ->
                async(Dispatchers.IO) { monthlyArtistSummary(normalizedUser, month, limit) }
              }
            }
            .awaitAll()
        }
    }
  }

  fun monthlyArtistSummary(
    user: String,
    month: YearMonth,
    limit: Int = MAX_TOP_ARTISTS_LIMIT,
  ): LastFmMonthSummary {
    val normalizedUser = user.trim()
    val cacheKey = "${normalizedUser.lowercase()}|$month"
    val fullSummary =
      monthlyCache.getIfPresent(cacheKey)
        ?: fetchMonth(normalizedUser, month).also { monthlyCache.put(cacheKey, it) }
    return fullSummary.copy(topArtists = fullSummary.topArtists.take(limit))
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

  private fun fetchMonth(user: String, month: YearMonth): LastFmMonthSummary {
    val from = month.atDay(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val to = month.atEndOfMonth().atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC)
    val artists = fetchArtists(user, from, to)
    return LastFmMonthSummary(
      year = month.year,
      month = month.monthValue,
      totalScrobbles = artists.sumOf { it.playcount },
      uniqueArtists = artists.size,
      topArtists = artists,
    )
  }

  private fun fetchYear(user: String, year: Int): LastFmYearSummary {
    val from = LocalDate.of(year, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val to = LocalDate.of(year, 12, 31).atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC)
    val artists = fetchArtists(user, from, to)
    return LastFmYearSummary(
      year = year,
      totalScrobbles = artists.sumOf { it.playcount },
      uniqueArtists = artists.size,
      topArtists = artists,
    )
  }

  private fun fetchArtists(user: String, from: Long, to: Long): List<LastFmYearArtist> {
    val uri =
      UriComponentsBuilder.fromUriString(LastFm.API_URL)
        .queryParam("method", "user.getWeeklyArtistChart")
        .queryParam("api_key", LastFm.API_KEY)
        .queryParam("format", "json")
        .queryParam("user", user)
        .queryParam("from", from)
        .queryParam("to", to)
        .queryParam("limit", LASTFM_ARTIST_LIMIT)
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

    return artistItems
      .mapNotNull { item ->
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
      .sortedByDescending { it.playcount }
  }

  companion object {
    const val DEFAULT_TOP_ARTISTS_LIMIT = 10
    const val MAX_TOP_ARTISTS_LIMIT = 50
    private const val LASTFM_ARTIST_LIMIT = 1000
    private const val MONTHLY_PARALLELISM = 3
    private const val MAX_MONTH_RANGE = 240L
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

data class LastFmMonthSummary(
  val year: Int,
  val month: Int,
  val totalScrobbles: Long,
  val uniqueArtists: Int,
  val topArtists: List<LastFmYearArtist>,
)
