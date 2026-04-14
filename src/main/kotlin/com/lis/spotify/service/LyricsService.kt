package com.lis.spotify.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
  @Value("\${lyrics.mood.provider:auto}") configuredMoodProvider: String = DEFAULT_MOOD_PROVIDER,
  @Value("\${lyrics.mood.gemini.api-key:}") configuredGeminiApiKey: String = "",
  @Value("\${lyrics.mood.gemini.base-url:https://generativelanguage.googleapis.com}")
  configuredGeminiBaseUrl: String = DEFAULT_GEMINI_BASE_URL,
  @Value("\${lyrics.mood.gemini.model:gemini-2.5-flash-lite}")
  configuredGeminiModel: String = DEFAULT_GEMINI_MODEL,
  @Value("\${lyrics.mood.gemini.batch-size:8}")
  configuredGeminiBatchSize: Int = DEFAULT_GEMINI_BATCH_SIZE,
) {
  internal var rest = RestTemplate()
  internal var mapper = jacksonObjectMapper()
  internal var baseUrl = configuredBaseUrl.trimEnd('/')
  internal var fetchParallelism = configuredFetchParallelism.coerceAtLeast(1)
  internal var moodProvider = configuredMoodProvider.trim().lowercase()
  internal var geminiApiKey = configuredGeminiApiKey.trim()
  internal var geminiBaseUrl = configuredGeminiBaseUrl.trimEnd('/')
  internal var geminiModel = configuredGeminiModel.trim().ifBlank { DEFAULT_GEMINI_MODEL }
  internal var geminiBatchSize = configuredGeminiBatchSize.coerceIn(1, MAX_GEMINI_BATCH_SIZE)

  private val logger = LoggerFactory.getLogger(LyricsService::class.java)
  private val lyricsCache: Cache<Pair<String, String>, LyricsLookupResult> =
    CacheBuilder.newBuilder().expireAfterWrite(12, TimeUnit.HOURS).build()
  private val moodProfileCache:
    Cache<Pair<String, String>, SpotifyTopPlaylistsService.PrivateMoodLyricsProfile> =
    CacheBuilder.newBuilder().expireAfterWrite(7, TimeUnit.DAYS).build()

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

  internal fun buildPrivateMoodLyricsProfiles(
    songs: Collection<Song>,
    heuristicAnalyzer: (String) -> SpotifyTopPlaylistsService.PrivateMoodLyricsProfile,
  ): Map<Pair<String, String>, SpotifyTopPlaylistsService.PrivateMoodLyricsProfile> {
    val uniqueSongs = songs.distinctBy { it.normalizedKey() }
    if (uniqueSongs.isEmpty()) {
      return emptyMap()
    }

    val profiles =
      LinkedHashMap<Pair<String, String>, SpotifyTopPlaylistsService.PrivateMoodLyricsProfile>()
    val songsNeedingAnalysis = mutableListOf<Song>()

    uniqueSongs.forEach { song ->
      val key = song.normalizedKey()
      val cachedProfile = moodProfileCache.getIfPresent(key)
      if (cachedProfile != null) {
        profiles[key] = cachedProfile
      } else {
        songsNeedingAnalysis += song
      }
    }

    if (songsNeedingAnalysis.isEmpty()) {
      return profiles.filterValues { it.coverageScore > 0.0 }
    }

    val lyricsByKey = fetchLyrics(songsNeedingAnalysis)
    val geminiProfiles =
      if (shouldUseGeminiMoodScoring() && lyricsByKey.isNotEmpty()) {
        classifyPrivateMoodLyricsWithGemini(lyricsByKey)
      } else {
        emptyMap()
      }

    songsNeedingAnalysis.forEach { song ->
      val key = song.normalizedKey()
      val profile =
        geminiProfiles[key]
          ?: lyricsByKey[key]?.let(heuristicAnalyzer)
          ?: SpotifyTopPlaylistsService.PrivateMoodLyricsProfile.empty()
      moodProfileCache.put(key, profile)
      profiles[key] = profile
    }

    return profiles.filterValues { it.coverageScore > 0.0 }
  }

  private fun buildLyricsUri(song: Song): URI {
    return UriComponentsBuilder.fromUriString(baseUrl)
      .path("/get")
      .queryParam("artist_name", song.artist.trim())
      .queryParam("track_name", song.title.trim())
      .build()
      .toUri()
  }

  private fun shouldUseGeminiMoodScoring(): Boolean {
    return when (moodProvider) {
      "",
      DEFAULT_MOOD_PROVIDER -> geminiApiKey.isNotBlank()
      GEMINI_MOOD_PROVIDER -> {
        if (geminiApiKey.isBlank()) {
          logger.warn("lyrics.mood.provider is gemini but lyrics.mood.gemini.api-key is missing")
          false
        } else {
          true
        }
      }
      HEURISTIC_MOOD_PROVIDER -> false
      else -> {
        logger.warn(
          "Unknown lyrics mood provider '{}'; falling back to automatic provider selection",
          moodProvider,
        )
        geminiApiKey.isNotBlank()
      }
    }
  }

  private fun classifyPrivateMoodLyricsWithGemini(
    lyricsByKey: Map<Pair<String, String>, String>
  ): Map<Pair<String, String>, SpotifyTopPlaylistsService.PrivateMoodLyricsProfile> {
    val entries =
      lyricsByKey.entries.mapIndexed { index, (key, lyrics) ->
        GeminiMoodRequestSong(id = "song-$index", key = key, lyrics = lyrics)
      }

    return entries
      .chunked(geminiBatchSize)
      .flatMap { batch ->
        try {
          classifyGeminiBatch(batch).entries
        } catch (ex: HttpStatusCodeException) {
          logger.warn("Gemini lyric mood classification failed with status {}", ex.statusCode, ex)
          emptyList()
        } catch (ex: ResourceAccessException) {
          logger.warn("Gemini lyric mood classification network failure", ex)
          emptyList()
        } catch (ex: Exception) {
          logger.warn("Unexpected Gemini lyric mood classification failure", ex)
          emptyList()
        }
      }
      .associate { it.toPair() }
  }

  private fun classifyGeminiBatch(
    entries: List<GeminiMoodRequestSong>
  ): Map<Pair<String, String>, SpotifyTopPlaylistsService.PrivateMoodLyricsProfile> {
    if (entries.isEmpty()) {
      return emptyMap()
    }

    val headers =
      HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("x-goog-api-key", geminiApiKey)
      }
    val request = HttpEntity(buildGeminiRequest(entries), headers)
    val response =
      rest.postForObject(buildGeminiUri(), request, Map::class.java) ?: return emptyMap()
    val responseText = extractGeminiResponseText(response) ?: return emptyMap()
    return parseGeminiMoodProfiles(entries, responseText)
  }

  private fun buildGeminiUri(): URI {
    return UriComponentsBuilder.fromUriString(geminiBaseUrl)
      .path("/v1beta/models/$geminiModel:generateContent")
      .build()
      .toUri()
  }

  private fun buildGeminiRequest(entries: List<GeminiMoodRequestSong>): Map<String, Any> {
    val songsJson =
      mapper.writeValueAsString(
        entries.map {
          mapOf(
            "id" to it.id,
            "artist" to it.key.first,
            "title" to it.key.second,
            "lyrics" to it.lyrics,
          )
        }
      )

    return mapOf(
      "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to GEMINI_SYSTEM_PROMPT))),
      "contents" to
        listOf(
          mapOf(
            "parts" to
              listOf(
                mapOf(
                  "text" to "Assess the following song lyrics and return JSON only.\n$songsJson"
                )
              )
          )
        ),
      "generationConfig" to
        mapOf(
          "temperature" to 0,
          "responseMimeType" to "application/json",
          "responseSchema" to GEMINI_RESPONSE_SCHEMA,
        ),
    )
  }

  private fun extractGeminiResponseText(response: Map<*, *>): String? {
    val candidates = response["candidates"] as? List<*> ?: return null
    val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return null
    val content = firstCandidate["content"] as? Map<*, *> ?: return null
    val parts = content["parts"] as? List<*> ?: return null
    val firstPart = parts.firstOrNull() as? Map<*, *> ?: return null
    return (firstPart["text"] as? String)?.trim()?.ifBlank { null }
  }

  private fun parseGeminiMoodProfiles(
    entries: List<GeminiMoodRequestSong>,
    responseText: String,
  ): Map<Pair<String, String>, SpotifyTopPlaylistsService.PrivateMoodLyricsProfile> {
    val parsed = mapper.readValue(responseText, Map::class.java)
    val assessments = parsed["assessments"] as? List<*> ?: return emptyMap()
    val keysById = entries.associate { it.id to it.key }
    return assessments
      .mapNotNull { rawAssessment ->
        val assessment = rawAssessment as? Map<*, *> ?: return@mapNotNull null
        val id = (assessment["id"] as? String)?.trim().orEmpty()
        val key = keysById[id] ?: return@mapNotNull null
        key to
          SpotifyTopPlaylistsService.PrivateMoodLyricsProfile(
            happyScore = assessment.numberValue("happyScore"),
            sadScore = assessment.numberValue("sadScore"),
            surgeScore = assessment.numberValue("surgeScore"),
            nightDriftScore = assessment.numberValue("nightDriftScore"),
            anchorScore = assessment.numberValue("anchorScore"),
            frontierScore = assessment.numberValue("frontierScore"),
            coverageScore = assessment.numberValue("coverageScore"),
            tokenCount = assessment.intValue("tokenCount"),
          )
      }
      .toMap(LinkedHashMap())
  }

  private fun Map<*, *>.numberValue(key: String): Double {
    return ((this[key] as? Number)?.toDouble() ?: 0.0).coerceAtLeast(0.0)
  }

  private fun Map<*, *>.intValue(key: String): Int {
    return ((this[key] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
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
    internal const val DEFAULT_MOOD_PROVIDER = "auto"
    internal const val DEFAULT_GEMINI_BATCH_SIZE = 8
    private const val DEFAULT_BASE_URL = "https://lrclib.net/api"
    private const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
    private const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite"
    private const val GEMINI_MOOD_PROVIDER = "gemini"
    private const val HEURISTIC_MOOD_PROVIDER = "heuristic"
    private const val MAX_GEMINI_BATCH_SIZE = 16
    private val SYNCED_LYRIC_TIMESTAMP_REGEX = Regex("^\\[[^\\]]+]\\s*")
    private val GEMINI_RESPONSE_SCHEMA =
      mapOf(
        "type" to "object",
        "properties" to
          mapOf(
            "assessments" to
              mapOf(
                "type" to "array",
                "items" to
                  mapOf(
                    "type" to "object",
                    "properties" to
                      mapOf(
                        "id" to mapOf("type" to "string"),
                        "anchorScore" to mapOf("type" to "number"),
                        "happyScore" to mapOf("type" to "number"),
                        "sadScore" to mapOf("type" to "number"),
                        "surgeScore" to mapOf("type" to "number"),
                        "nightDriftScore" to mapOf("type" to "number"),
                        "frontierScore" to mapOf("type" to "number"),
                        "coverageScore" to mapOf("type" to "number"),
                        "tokenCount" to mapOf("type" to "integer"),
                      ),
                    "required" to
                      listOf(
                        "id",
                        "anchorScore",
                        "happyScore",
                        "sadScore",
                        "surgeScore",
                        "nightDriftScore",
                        "frontierScore",
                        "coverageScore",
                        "tokenCount",
                      ),
                  ),
              )
          ),
        "required" to listOf("assessments"),
      )
    private const val GEMINI_SYSTEM_PROMPT =
      "You classify song lyrics into six internal playlist moods. " +
        "Use only the provided lyrics. Return JSON only that matches the schema. " +
        "Scores must be non-negative numbers from 0 to 10, where higher means a stronger fit. " +
        "coverageScore measures how much lyrical evidence exists and should be 0 when the lyrics are too sparse or repetitive to judge. " +
        "tokenCount is the approximate number of lyric tokens used. " +
        "Anchor means comfort, grounding, home, reassurance, devotion, healing, and stability. " +
        "Happy means joy, lightness, celebration, playfulness, sunshine, and dancing. " +
        "Sad means heartbreak, grief, longing, loneliness, regret, tears, and darkness. " +
        "Surge means adrenaline, momentum, confidence, movement, defiance, and intensity. " +
        "Night Drift means nocturnal, hazy, intimate, dreamy, after-hours, insomnia, and reflective darkness. " +
        "Frontier means escape, horizons, wandering, reinvention, exploration, and the unknown."
  }

  private data class LyricsLookupResult(val lyrics: String?)

  private data class GeminiMoodRequestSong(
    val id: String,
    val key: Pair<String, String>,
    val lyrics: String,
  )
}
