package com.lis.spotify.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lis.spotify.AppEnvironment.LastFm
import com.lis.spotify.domain.Song
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

@Service
class LastFmService(private val lastFmAuthService: LastFmAuthenticationService) {

  private val rest = RestTemplate()
  private val log = LoggerFactory.getLogger(LastFmService::class.java)
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
    return try {
      runBlocking(Dispatchers.IO) { rest.getForObject(uri, Map::class.java) as Map<String, Any> }
    } catch (ex: HttpClientErrorException) {
      val err = parseError(ex)
      log.error("Last.fm error {} {}", err.code, err.message)
      throw err
    }
  }

  private fun parseError(ex: HttpClientErrorException): LastFmException {
    return try {
      val body = mapper.readValue(ex.responseBodyAsString, Map::class.java) as Map<*, *>
      val code = (body["error"] as? Int) ?: ex.statusCode.value()
      val msg = body["message"] as? String ?: ex.message
      LastFmException(code, msg ?: "error")
    } catch (e: Exception) {
      LastFmException(ex.statusCode.value(), ex.message ?: "error")
    }
  }

  fun yearlyChartlist(
    spotifyClientId: String,
    year: Int,
    lastFmLogin: String,
    limit: Int = Int.MAX_VALUE,
  ): List<Song> {
    log.debug("yearlyChartlist {} {} {}", spotifyClientId, year, lastFmLogin)
    if (lastFmLogin.isBlank()) throw LastFmException(400, "user is required")
    val from = LocalDate.of(year, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val to = LocalDate.of(year, 12, 31).atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC)

    val result = mutableListOf<Song>()
    var page = 1
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
    log.debug("yearlyChartlist {} {} fetched {} pages", lastFmLogin, year, fetched)
    return result
  }

  fun globalChartlist(lastFmLogin: String, page: Int = 1): List<Song> {
    log.debug("globalChartlist {} {}", lastFmLogin, page)
    return yearlyChartlist("", 1970, lastFmLogin) // reuse; no date filter needed
  }
}

class LastFmException(val code: Int, override val message: String) : RuntimeException(message)
