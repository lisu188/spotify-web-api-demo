package com.lis.spotify.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lis.spotify.AppEnvironment.LastFm
import com.lis.spotify.domain.Song
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

@Service
class LastFmService(private val lastFmAuthService: LastFmAuthenticationService) {

  internal var rest = RestTemplate()
  internal var sleeper: LastFmSleeper = LastFmSleeper { millis -> Thread.sleep(millis) }
  private val logger = LoggerFactory.getLogger(LastFmService::class.java)
  private val mapper = jacksonObjectMapper()

  private fun buildUri(method: String, params: Map<String, Any>, sessionKey: String?): URI {
    var b =
      UriComponentsBuilder.fromHttpUrl(LastFm.API_URL)
        .queryParam("method", method)
        .queryParam("api_key", LastFm.API_KEY)
        .queryParam("format", "json")
    if (!sessionKey.isNullOrBlank()) {
      b = b.queryParam("sk", sessionKey)
    }
    params.forEach { (k, v) -> b = b.queryParam(k, v) }
    return b.build().toUri()
  }

  private fun fetchRecent(user: String, from: Long, to: Long, page: Int): Map<String, Any> {
    if (user.isBlank()) throw LastFmException(400, "user is required")
    require(from <= to) { "from must be <= to" }
    val uri =
      buildUri(
        "user.getRecentTracks",
        mapOf("user" to user, "from" to from, "to" to to, "page" to page, "limit" to 200),
        lastFmAuthService.getSessionKey(user),
      )
    var attempt = 1
    while (true) {
      try {
        return rest.getForObject(uri, Map::class.java) as Map<String, Any>
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
    } catch (e: Exception) {
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

    val result = mutableListOf<Song>()
    var page = startPage
    var fetched = 0
    while (result.size < limit) {
      val data = fetchRecent(lastFmLogin, from, to, page++)
      val recent = data["recenttracks"] as Map<*, *>
      val tracks = recent["track"] as List<*>
      if (tracks.isEmpty()) break
      for (t in tracks) {
        val m = t as Map<*, *>
        val artist = (m["artist"] as Map<*, *>)["#text"] as String
        val title = m["name"] as String
        result += Song(artist = artist, title = title)
        if (result.size >= limit) break
      }
      fetched++
    }
    logger.debug("yearlyChartlist {} {} fetched {} pages", lastFmLogin, year, fetched)
    return result
  }

  fun globalChartlist(lastFmLogin: String, page: Int = 1): List<Song> {
    logger.info("Fetching global chartlist for user {}", lastFmLogin)
    logger.debug("globalChartlist {} {}", lastFmLogin, page)
    return yearlyChartlist("", 1970, lastFmLogin, startPage = page)
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
    private const val AUTHENTICATION_REQUIRED_CODE = 17
    private const val TRANSIENT_BACKEND_ERROR_CODE = 8
  }
}

fun interface LastFmSleeper {
  fun sleep(millis: Long)
}

class LastFmException(val code: Int, override val message: String) : RuntimeException(message)
