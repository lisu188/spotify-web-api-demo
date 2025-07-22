package com.lis.spotify.service

import com.lis.spotify.AppEnvironment.LastFm
import com.lis.spotify.domain.Song
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

@Service
class LastFmService {

  private val rest = RestTemplate()
  private val log = LoggerFactory.getLogger(LastFmService::class.java)

  private fun fetchRecent(user: String, from: Long, to: Long, page: Int): Map<String, Any> {
    val uri =
      UriComponentsBuilder.fromHttpUrl(LastFm.API_URL)
        .queryParam("method", "user.getrecenttracks")
        .queryParam("user", user)
        .queryParam("from", from)
        .queryParam("to", to)
        .queryParam("page", page)
        .queryParam("limit", 200)
        .queryParam("api_key", LastFm.API_KEY)
        .queryParam("format", "json")
        .build()
        .toUri()
    return rest.getForObject(uri, Map::class.java) as Map<String, Any>
  }

  fun yearlyChartlist(spotifyClientId: String, year: Int, lastFmLogin: String): List<Song> {
    log.debug("yearlyChartlist {} {} {}", spotifyClientId, year, lastFmLogin)
    val from = LocalDate.of(year, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    val to = LocalDate.of(year, 12, 31).atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC)
    val songs = mutableListOf<Song>()
    var page = 1
    var totalPages: Int
    do {
      val data = fetchRecent(lastFmLogin, from, to, page++)
      val recent = data["recenttracks"] as Map<*, *>
      totalPages = (recent["totalPages"] as String).toInt()
      val tracks = recent["track"] as List<*>
      for (t in tracks) {
        val m = t as Map<*, *>
        val artist = (m["artist"] as Map<*, *>)["#text"] as String
        val title = m["name"] as String
        songs += Song(artist = artist, title = title)
      }
    } while (page <= totalPages)
    log.info("yearlyChartlist {} {} => {}", lastFmLogin, year, songs.size)
    log.debug("yearlyChartlist {} {} fetched {} pages", lastFmLogin, year, totalPages)
    return songs
  }

  fun globalChartlist(lastFmLogin: String, page: Int = 1): List<Song> {
    log.debug("globalChartlist {} {}", lastFmLogin, page)
    return yearlyChartlist("", 1970, lastFmLogin) // reuse; no date filter needed
  }
}
