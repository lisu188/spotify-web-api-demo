package com.lis.spotify.service

import com.lis.spotify.domain.Song
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

class LastFmServiceTest {
  @Test
  fun yearlyChartlistParsesSongs() {
    val rest = mockk<RestTemplate>()
    val service = LastFmService()
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val map =
      mapOf(
        "recenttracks" to
          mapOf(
            "totalPages" to "1",
            "track" to listOf(mapOf("artist" to mapOf("#text" to "A"), "name" to "T")),
          )
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } returns map
    val songs = service.yearlyChartlist("cid", 2020, "login")
    assertEquals(listOf(Song("A", "T")), songs)
  }

  @Test
  fun pagingReturnsAllSongs() {
    val rest = mockk<RestTemplate>()
    val service = LastFmService()
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val map1 =
      mapOf(
        "recenttracks" to
          mapOf(
            "totalPages" to "2",
            "track" to listOf(mapOf("artist" to mapOf("#text" to "A"), "name" to "T1")),
          )
      )
    val map2 =
      mapOf(
        "recenttracks" to
          mapOf(
            "totalPages" to "2",
            "track" to listOf(mapOf("artist" to mapOf("#text" to "B"), "name" to "T2")),
          )
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } returnsMany listOf(map1, map2)
    val songs = service.yearlyChartlist("cid", 2020, "login")
    assertEquals(listOf(Song("A", "T1"), Song("B", "T2")), songs)
  }

  @Test
  fun missingUserThrows400() {
    val service = LastFmService()
    assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "") }
  }

  @Test
  fun rateLimitExceptionParsed() {
    val rest = mockk<RestTemplate>()
    val service = LastFmService()
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val ex =
      HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "",
        HttpHeaders(),
        "{\"error\":29,\"message\":\"Rate limit\"}".toByteArray(),
        null,
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } throws ex
    val thrown =
      assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "u") }
    assertEquals(29, thrown.code)
  }

  @Test
  fun invalidApiKeyExceptionParsed() {
    val rest = mockk<RestTemplate>()
    val service = LastFmService()
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val ex =
      HttpClientErrorException.create(
        HttpStatus.FORBIDDEN,
        "",
        HttpHeaders(),
        "{\"error\":10,\"message\":\"Invalid API key\"}".toByteArray(),
        null,
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } throws ex
    val thrown =
      assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "u") }
    assertEquals(10, thrown.code)
  }
}
