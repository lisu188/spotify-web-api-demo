package com.lis.spotify.service

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.lis.spotify.domain.Song
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

@Service
class LyricsService(
  @Value("\${lyrics.api.base-url:https://lrclib.net/api}")
  configuredBaseUrl: String = DEFAULT_BASE_URL,
  @Value("\${lyrics.fetch.max-parallelism:8}")
  configuredFetchParallelism: Int = DEFAULT_FETCH_PARALLELISM,
) {
  internal var rest = RestTemplate()
  internal var baseUrl = configuredBaseUrl.trimEnd('/')
  internal var fetchParallelism = configuredFetchParallelism.coerceAtLeast(1)

  private val logger = LoggerFactory.getLogger(LyricsService::class.java)
  private val lyricsCache: Cache<Pair<String, String>, LyricsLookupResult> =
    CacheBuilder.newBuilder().expireAfterWrite(12, TimeUnit.HOURS).build()

  fun fetchLyrics(song: Song): String? {
    val key = song.normalizedKey()
    if (key.first.isBlank() || key.second.isBlank()) {
      return null
    }

    lyricsCache.getIfPresent(key)?.let {
      return it.lyrics
    }

    val lyrics =
      try {
        parseLyrics(rest.getForObject(buildLyricsUri(song), Map::class.java))
      } catch (ex: HttpStatusCodeException) {
        if (ex.statusCode == HttpStatus.NOT_FOUND) {
          logger.debug("Lyrics not found for {} - {}", song.artist, song.title)
          null
        } else {
          logger.warn("Lyrics lookup failed for {} - {}", song.artist, song.title, ex)
          null
        }
      } catch (ex: ResourceAccessException) {
        logger.warn("Lyrics lookup network error for {} - {}", song.artist, song.title, ex)
        null
      } catch (ex: Exception) {
        logger.warn("Unexpected lyrics lookup failure for {} - {}", song.artist, song.title, ex)
        null
      }

    lyricsCache.put(key, LyricsLookupResult(lyrics))
    return lyrics
  }

  fun fetchLyrics(songs: Collection<Song>): Map<Pair<String, String>, String> {
    val uniqueSongs = songs.distinctBy { it.normalizedKey() }
    if (uniqueSongs.isEmpty()) {
      return emptyMap()
    }

    return runBlocking(Dispatchers.IO) {
      val semaphore = Semaphore(fetchParallelism.coerceAtLeast(1))
      uniqueSongs
        .map { song ->
          async(Dispatchers.IO) {
            semaphore.withPermit { song.normalizedKey() to fetchLyrics(song) }
          }
        }
        .awaitAll()
        .mapNotNull { (key, lyrics) -> lyrics?.let { key to it } }
        .toMap(LinkedHashMap())
    }
  }

  private fun buildLyricsUri(song: Song): URI {
    return UriComponentsBuilder.fromUriString(baseUrl)
      .path("/get")
      .queryParam("artist_name", song.artist.trim())
      .queryParam("track_name", song.title.trim())
      .build()
      .toUri()
  }

  private fun parseLyrics(payload: Any?): String? {
    val map = payload as? Map<*, *> ?: return null
    if ((map["instrumental"] as? Boolean) == true) {
      return null
    }

    val plainLyrics = (map["plainLyrics"] as? String).orEmpty().trim()
    if (plainLyrics.isNotBlank()) {
      return plainLyrics
    }

    val syncedLyrics = (map["syncedLyrics"] as? String).orEmpty().trim()
    if (syncedLyrics.isBlank()) {
      return null
    }

    return syncedLyrics
      .lineSequence()
      .map { it.replace(SYNCED_LYRIC_TIMESTAMP_REGEX, "").trim() }
      .filter { it.isNotBlank() }
      .joinToString("\n")
      .trim()
      .ifBlank { null }
  }

  private fun Song.normalizedKey(): Pair<String, String> {
    return artist.normalizeToken() to title.normalizeToken()
  }

  private fun String.normalizeToken(): String {
    return trim().lowercase().replace("\\s+".toRegex(), " ")
  }

  companion object {
    internal const val DEFAULT_FETCH_PARALLELISM = 8
    private const val DEFAULT_BASE_URL = "https://lrclib.net/api"
    private val SYNCED_LYRIC_TIMESTAMP_REGEX = Regex("^\\[[^\\]]+]\\s*")
  }

  private data class LyricsLookupResult(val lyrics: String?)
}
