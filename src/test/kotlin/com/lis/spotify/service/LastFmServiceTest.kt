package com.lis.spotify.service

import com.lis.spotify.domain.Song
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
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
    val auth = mockk<LastFmAuthenticationService>(relaxed = true)
    val service = LastFmService(auth)
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val map1 =
      mapOf(
        "recenttracks" to
          mapOf(
            "@attr" to mapOf("totalPages" to "1"),
            "track" to listOf(mapOf("artist" to mapOf("#text" to "A"), "name" to "T")),
          )
      )
    val map2 =
      mapOf(
        "recenttracks" to
          mapOf("@attr" to mapOf("totalPages" to "1"), "track" to emptyList<String>())
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } returnsMany listOf(map1, map2)
    val songs = service.yearlyChartlist("cid", 2020, "login")
    assertEquals(listOf(Song("A", "T")), songs)
  }

  @Test
  fun pagingReturnsAllSongs() {
    val rest = mockk<RestTemplate>()
    val auth = mockk<LastFmAuthenticationService>(relaxed = true)
    val service = LastFmService(auth)
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val map1 =
      mapOf(
        "recenttracks" to
          mapOf(
            "@attr" to mapOf("totalPages" to "2"),
            "track" to listOf(mapOf("artist" to mapOf("#text" to "A"), "name" to "T1")),
          )
      )
    val map2 =
      mapOf(
        "recenttracks" to
          mapOf(
            "@attr" to mapOf("totalPages" to "2"),
            "track" to listOf(mapOf("artist" to mapOf("#text" to "B"), "name" to "T2")),
          )
      )
    val map3 =
      mapOf(
        "recenttracks" to
          mapOf("@attr" to mapOf("totalPages" to "2"), "track" to emptyList<String>())
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } returnsMany
      listOf(map1, map2, map3)
    val songs = service.yearlyChartlist("cid", 2020, "login")
    assertEquals(listOf(Song("A", "T1"), Song("B", "T2")), songs)
  }

  @Test
  fun limitStopsPaging() {
    val rest = mockk<RestTemplate>()
    val auth = mockk<LastFmAuthenticationService>(relaxed = true)
    val service = LastFmService(auth)
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val map1 =
      mapOf(
        "recenttracks" to
          mapOf(
            "@attr" to mapOf("totalPages" to "2"),
            "track" to listOf(mapOf("artist" to mapOf("#text" to "A"), "name" to "T1")),
          )
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } returns map1

    val songs = service.yearlyChartlist("cid", 2020, "login", limit = 1)

    assertEquals(listOf(Song("A", "T1")), songs)
    io.mockk.verify(exactly = 1) { rest.getForObject(any<java.net.URI>(), Map::class.java) }
  }

  @Test
  fun missingUserThrows400() {
    val service = LastFmService(mockk(relaxed = true))
    assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "") }
  }

  @Test
  fun rateLimitExceptionParsed() {
    val rest = mockk<RestTemplate>()
    val auth = mockk<LastFmAuthenticationService>(relaxed = true)
    val service = LastFmService(auth)
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
      assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "u").first() }
    assertEquals(29, thrown.code)
  }

  @Test
  fun invalidApiKeyExceptionParsed() {
    val rest = mockk<RestTemplate>()
    val auth = mockk<LastFmAuthenticationService>(relaxed = true)
    val service = LastFmService(auth)
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
      assertThrows(LastFmException::class.java) { service.yearlyChartlist("c", 2020, "u").first() }
    assertEquals(10, thrown.code)
  }

  @Test
  fun sessionKeyAddedToRequest() {
    val rest = mockk<RestTemplate>()
    val auth = mockk<LastFmAuthenticationService>()
    every { auth.getSessionKey("login") } returns "sess"
    val service = LastFmService(auth)
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val uriSlot = io.mockk.slot<java.net.URI>()
    every { rest.getForObject(capture(uriSlot), Map::class.java) } returns
      mapOf(
        "recenttracks" to
          mapOf("@attr" to mapOf("totalPages" to "1"), "track" to emptyList<String>())
      )

    service.yearlyChartlist("c", 2020, "login")
    assert(uriSlot.captured.query!!.contains("sk=sess"))
  }

  @Test
  fun globalChartlistForwardsPage() {
    val service = spyk(LastFmService(mockk(relaxed = true)))
    every { service.yearlyChartlist("", 1970, "login", startPage = 2) } returns
      listOf(Song("a", "b"))
    val result = service.globalChartlist("login", 2)
    assertEquals(listOf(Song("a", "b")), result)
  }

  @Test
  fun userExistsReturnsTrue() {
    val rest = mockk<RestTemplate>()
    val service = LastFmService(mockk(relaxed = true))
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } returns
      emptyMap<String, Any>()
    val result = service.userExists("login")
    assertEquals(true, result)
  }

  @Test
  fun userExistsReturnsFalseOnNotFound() {
    val rest = mockk<RestTemplate>()
    val service = LastFmService(mockk(relaxed = true))
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val ex =
      HttpClientErrorException.create(
        HttpStatus.NOT_FOUND,
        "",
        HttpHeaders(),
        "{\"error\":6,\"message\":\"Not found\"}".toByteArray(),
        null,
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } throws ex
    val result = service.userExists("login")
    assertEquals(false, result)
  }

  @Test
  fun userExistsThrowsOtherErrors() {
    val rest = mockk<RestTemplate>()
    val service = LastFmService(mockk(relaxed = true))
    val field = LastFmService::class.java.getDeclaredField("rest")
    field.isAccessible = true
    field.set(service, rest)
    val ex =
      HttpClientErrorException.create(
        HttpStatus.UNAUTHORIZED,
        "",
        HttpHeaders(),
        "{\"error\":17,\"message\":\"Login\"}".toByteArray(),
        null,
      )
    every { rest.getForObject(any<java.net.URI>(), Map::class.java) } throws ex
    assertThrows(LastFmException::class.java) { service.userExists("login") }
  }
}
