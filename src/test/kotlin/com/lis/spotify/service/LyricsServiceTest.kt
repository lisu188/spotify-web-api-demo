package com.lis.spotify.service

import com.lis.spotify.domain.Song
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
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
}
