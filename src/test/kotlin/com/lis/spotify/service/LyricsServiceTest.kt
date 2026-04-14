package com.lis.spotify.service

import com.lis.spotify.domain.Song
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate

class LyricsServiceTest {
  @Test
  fun fetchLyricsReturnsPlainLyricsAndUsesCache() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf("plainLyrics" to "line one\nline two", "instrumental" to false)

    val first = service.fetchLyrics(Song("Artist A", "Song A"))
    val second = service.fetchLyrics(Song("Artist A", "Song A"))

    assertEquals("line one\nline two", first)
    assertEquals(first, second)
    verify(exactly = 1) { rest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun fetchLyricsFallsBackToSyncedLyrics() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf(
        "plainLyrics" to "",
        "syncedLyrics" to "[00:01.00]line one\n[00:02.00]line two",
        "instrumental" to false,
      )

    val lyrics = service.fetchLyrics(Song("Artist A", "Song A"))

    assertEquals("line one\nline two", lyrics)
  }

  @Test
  fun fetchLyricsReturnsNullWhenNotFound() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    val notFound =
      HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", HttpHeaders(), ByteArray(0), null)
    every { rest.getForObject(any<URI>(), Map::class.java) } throws notFound

    val lyrics = service.fetchLyrics(Song("Artist A", "Missing Song"))

    assertNull(lyrics)
  }

  @Test
  fun fetchLyricsRetriesWithSimplifiedTitleAfterBadRequest() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    val requestedUris = mutableListOf<String>()
    service.rest = rest
    val badRequest =
      HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "", HttpHeaders(), ByteArray(0), null)

    every { rest.getForObject(any<URI>(), Map::class.java) } answers
      {
        val uri = firstArg<URI>().toString()
        requestedUris += uri
        if (requestedUris.size == 1) {
          throw badRequest
        }
        mapOf("plainLyrics" to "let it go", "instrumental" to false)
      }

    val lyrics = service.fetchLyrics(Song("Artist A", "Song A - Live / OST"))

    assertEquals("let it go", lyrics)
    assertEquals(2, requestedUris.size)
    assertTrue(requestedUris.first().contains("Live"))
    assertNotEquals(requestedUris.first(), requestedUris.last())
    assertTrue(requestedUris.last().contains("Live"))
    assertFalse(requestedUris.last().contains("OST"))
  }

  @Test
  fun fetchLyricsRetriesTransientServerFailure() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    service.retryBaseDelayMillis = 0
    service.retrySleeper = {}
    val unavailable =
      HttpClientErrorException.create(
        HttpStatus.SERVICE_UNAVAILABLE,
        "",
        HttpHeaders(),
        ByteArray(0),
        null,
      )
    var lyricsLookupCalls = 0

    every { rest.getForObject(any<URI>(), Map::class.java) } answers
      {
        lyricsLookupCalls += 1
        if (lyricsLookupCalls == 1) {
          throw unavailable
        }
        mapOf("plainLyrics" to "retry success", "instrumental" to false)
      }

    val lyrics = service.fetchLyrics(Song("Artist A", "Song A"))

    assertEquals("retry success", lyrics)
    verify(exactly = 2) { rest.getForObject(any<URI>(), Map::class.java) }
  }

  @Test
  fun buildPrivateMoodLyricsProfilesUsesOpenAiAndFallsBackForMissingAssessments() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    var request: HttpEntity<*>? = null
    service.rest = rest
    service.fetchParallelism = 1
    service.moodProvider = "auto"
    service.openAiApiKey = "test-key"

    every { rest.getForObject(any<URI>(), Map::class.java) } answers
      {
        val uri = firstArg<URI>().toString()
        when {
          uri.contains("artist_name=Artist+A") || uri.contains("artist_name=Artist%20A") ->
            mapOf("plainLyrics" to "sunshine dancing all day", "instrumental" to false)
          uri.contains("artist_name=Artist+B") || uri.contains("artist_name=Artist%20B") ->
            mapOf("plainLyrics" to "lonely tears in the dark", "instrumental" to false)
          else -> error("Unexpected lyrics URI $uri")
        }
      }
    every { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) } answers
      {
        request = secondArg<HttpEntity<*>>()
        mapOf(
          "choices" to
            listOf(
              mapOf(
                "message" to
                  mapOf(
                    "content" to
                      """
                      {"assessments":[{"id":"song-0","anchorScore":1,"happyScore":9,"sadScore":0.5,"surgeScore":4,"nightDriftScore":0.5,"frontierScore":1.5,"coverageScore":8,"tokenCount":4}]}
                      """
                        .trimIndent()
                  )
              )
            )
        )
      }

    val profiles =
      service.buildPrivateMoodLyricsProfiles(
        listOf(Song("Artist A", "Song A"), Song("Artist B", "Song B"))
      ) { lyrics ->
        if (lyrics.contains("lonely", ignoreCase = true)) {
          SpotifyTopPlaylistsService.PrivateMoodLyricsProfile(
            happyScore = 0.5,
            sadScore = 8.0,
            surgeScore = 0.0,
            nightDriftScore = 3.0,
            anchorScore = 0.0,
            frontierScore = 0.0,
            coverageScore = 6.0,
            tokenCount = 5,
          )
        } else {
          SpotifyTopPlaylistsService.PrivateMoodLyricsProfile.empty()
        }
      }

    assertEquals(2, profiles.size)
    assertEquals(9.0, profiles.getValue("artist a" to "song a").happyScore)
    assertEquals(8.0, profiles.getValue("artist b" to "song b").sadScore)
    assertEquals("Bearer test-key", request?.headers?.getFirst("Authorization"))
    val body = request?.body as Map<*, *>
    val responseFormat = body["response_format"] as Map<*, *>
    val jsonSchema = responseFormat["json_schema"] as Map<*, *>
    assertEquals("gpt-5.4-mini", body["model"])
    assertFalse(body.containsKey("temperature"))
    assertEquals("json_schema", responseFormat["type"])
    assertEquals("private_mood_lyrics_batch", jsonSchema["name"])
    assertTrue(
      jsonSchema.containsKey("schema"),
      "Expected OpenAI request to include a strict JSON schema",
    )
  }

  @Test
  fun buildPrivateMoodLyricsProfilesFallsBackWhenOpenAiFails() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    service.fetchParallelism = 1
    service.moodProvider = "auto"
    service.openAiApiKey = "test-key"
    service.retryBaseDelayMillis = 0
    service.retrySleeper = {}

    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf("plainLyrics" to "sunshine dancing all day", "instrumental" to false)
    every { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) } throws
      ResourceAccessException("offline")

    val profiles =
      service.buildPrivateMoodLyricsProfiles(listOf(Song("Artist A", "Song A"))) {
        SpotifyTopPlaylistsService.PrivateMoodLyricsProfile(
          happyScore = 7.0,
          sadScore = 0.0,
          surgeScore = 2.0,
          nightDriftScore = 0.0,
          anchorScore = 1.0,
          frontierScore = 0.0,
          coverageScore = 5.0,
          tokenCount = 4,
        )
      }

    assertEquals(7.0, profiles.getValue("artist a" to "song a").happyScore)
  }

  @Test
  fun buildPrivateMoodLyricsProfilesRetriesOpenAiBatchAfterTransientFailure() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    service.fetchParallelism = 1
    service.moodProvider = "openai"
    service.openAiApiKey = "test-key"
    service.retryBaseDelayMillis = 0
    service.retrySleeper = {}
    val unavailable =
      HttpClientErrorException.create(
        HttpStatus.SERVICE_UNAVAILABLE,
        "",
        HttpHeaders(),
        ByteArray(0),
        null,
      )
    var openAiCalls = 0

    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf("plainLyrics" to "sunshine dancing all day", "instrumental" to false)
    every { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) } answers
      {
        openAiCalls += 1
        if (openAiCalls == 1) {
          throw unavailable
        }
        mapOf(
          "choices" to
            listOf(
              mapOf(
                "message" to
                  mapOf(
                    "content" to
                      """
                      {"assessments":[{"id":"song-0","anchorScore":1,"happyScore":9,"sadScore":0.5,"surgeScore":4,"nightDriftScore":0.5,"frontierScore":1.5,"coverageScore":8,"tokenCount":4}]}
                      """
                        .trimIndent()
                  )
              )
            )
        )
      }

    val profiles =
      service.buildPrivateMoodLyricsProfiles(listOf(Song("Artist A", "Song A"))) {
        SpotifyTopPlaylistsService.PrivateMoodLyricsProfile.empty()
      }

    assertEquals(1, profiles.size)
    assertEquals(9.0, profiles.getValue("artist a" to "song a").happyScore)
    verify(exactly = 2) { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) }
  }

  @Test
  fun buildPrivateMoodLyricsProfilesDoesNotFallbackWhenProviderIsOpenAiAndOpenAiFails() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    service.fetchParallelism = 1
    service.moodProvider = "openai"
    service.openAiApiKey = "test-key"
    service.retryBaseDelayMillis = 0
    service.retrySleeper = {}
    val unavailable =
      HttpClientErrorException.create(
        HttpStatus.SERVICE_UNAVAILABLE,
        "",
        HttpHeaders(),
        ByteArray(0),
        null,
      )

    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf("plainLyrics" to "sunshine dancing all day", "instrumental" to false)
    every { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) } throws
      unavailable

    val profiles =
      service.buildPrivateMoodLyricsProfiles(listOf(Song("Artist A", "Song A"))) {
        SpotifyTopPlaylistsService.PrivateMoodLyricsProfile(
          happyScore = 7.0,
          sadScore = 0.0,
          surgeScore = 2.0,
          nightDriftScore = 0.0,
          anchorScore = 1.0,
          frontierScore = 0.0,
          coverageScore = 5.0,
          tokenCount = 4,
        )
      }

    assertTrue(profiles.isEmpty())
    verify(exactly = service.openAiRequestMaxAttempts) {
      rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java)
    }
  }

  @Test
  fun buildPrivateMoodLyricsProfilesDoesNotRetryOpenAiQuotaFailure() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    service.fetchParallelism = 1
    service.moodProvider = "openai"
    service.openAiApiKey = "test-key"
    service.retryBaseDelayMillis = 0
    service.retrySleeper = {}
    val quotaExceeded =
      HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "",
        HttpHeaders(),
        """
        {"error":{"message":"quota exceeded","type":"insufficient_quota","code":"insufficient_quota"}}
        """
          .trimIndent()
          .toByteArray(),
        null,
      )

    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf("plainLyrics" to "sunshine dancing all day", "instrumental" to false)
    every { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) } throws
      quotaExceeded

    val profiles =
      service.buildPrivateMoodLyricsProfiles(listOf(Song("Artist A", "Song A"))) {
        SpotifyTopPlaylistsService.PrivateMoodLyricsProfile(
          happyScore = 7.0,
          sadScore = 0.0,
          surgeScore = 2.0,
          nightDriftScore = 0.0,
          anchorScore = 1.0,
          frontierScore = 0.0,
          coverageScore = 5.0,
          tokenCount = 4,
        )
      }

    assertTrue(profiles.isEmpty())
    verify(exactly = 1) { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) }
  }

  @Test
  fun buildPrivateMoodLyricsProfilesStopsOpenAiBatchesAfterQuotaFailure() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    service.rest = rest
    service.fetchParallelism = 1
    service.moodProvider = "openai"
    service.openAiApiKey = "test-key"
    service.openAiBatchSize = 1
    service.retryBaseDelayMillis = 0
    service.retrySleeper = {}
    val quotaExceeded =
      HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "",
        HttpHeaders(),
        """
        {"error":{"message":"quota exceeded","type":"insufficient_quota","code":"insufficient_quota"}}
        """
          .trimIndent()
          .toByteArray(),
        null,
      )

    every { rest.getForObject(any<URI>(), Map::class.java) } returns
      mapOf("plainLyrics" to "sunshine dancing all day", "instrumental" to false)
    every { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) } throws
      quotaExceeded

    val profiles =
      service.buildPrivateMoodLyricsProfiles(
        listOf(Song("Artist A", "Song A"), Song("Artist B", "Song B"))
      ) {
        SpotifyTopPlaylistsService.PrivateMoodLyricsProfile.empty()
      }

    assertTrue(profiles.isEmpty())
    verify(exactly = 1) { rest.postForObject(any<URI>(), any<HttpEntity<*>>(), Map::class.java) }
  }
}
