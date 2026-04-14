package com.lis.spotify.service

import com.lis.spotify.domain.Song
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
  fun buildPrivateMoodLyricsProfilesUsesOpenAiAndFallsBackForMissingAssessments() {
    val rest = mockk<RestTemplate>()
    val service = LyricsService()
    var request: HttpEntity<*>? = null
    service.rest = rest
    service.fetchParallelism = 1
    service.moodProvider = "openai"
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
    service.moodProvider = "openai"
    service.openAiApiKey = "test-key"

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
}
