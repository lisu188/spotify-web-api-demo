package com.lis.spotify.controller

import com.lis.spotify.domain.BandPlaylistRequest
import com.lis.spotify.service.SpotifyAuthenticationService
import com.lis.spotify.service.SpotifyBandPlaylistService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class SpotifyBandPlaylistControllerTest {
  private val service = mockk<SpotifyBandPlaylistService>()
  private val authenticationService = mockk<SpotifyAuthenticationService>()
  private val controller = SpotifyBandPlaylistController(service, authenticationService)

  @Test
  fun createBandPlaylistReturnsOk() {
    every { authenticationService.isValidSpotifySession("cid", "session") } returns true
    every { service.createBandPlaylist("cid", listOf("Band A", "Band B")) } returns "playlist"

    val response =
      controller.createBandPlaylist(
        "cid",
        "session",
        BandPlaylistRequest(listOf("Band A", "Band B")),
      )

    assertEquals(HttpStatus.OK, response.statusCode)
    assertEquals("playlist", response.body)
  }

  @Test
  fun createBandPlaylistReturnsBadRequestForEmptyBands() {
    every { authenticationService.isValidSpotifySession("cid", "session") } returns true

    val response = controller.createBandPlaylist("cid", "session", BandPlaylistRequest(listOf(" ")))

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
  }

  @Test
  fun createBandPlaylistReturnsNotFound() {
    every { authenticationService.isValidSpotifySession("cid", "session") } returns true
    every { service.createBandPlaylist("cid", listOf("Band A", "Band B")) } returns null

    val response =
      controller.createBandPlaylist(
        "cid",
        "session",
        BandPlaylistRequest(listOf("Band A", "Band B")),
      )

    assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
  }

  @Test
  fun createBandPlaylistReturnsUnauthorizedForInvalidSession() {
    every { authenticationService.isValidSpotifySession("cid", "forged") } returns false

    val response =
      controller.createBandPlaylist("cid", "forged", BandPlaylistRequest(listOf("Band A")))

    assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
  }

  @Test
  fun createBandPlaylistReturnsBadRequestForTooManyBands() {
    every { authenticationService.isValidSpotifySession("cid", "session") } returns true

    val response =
      controller.createBandPlaylist(
        "cid",
        "session",
        BandPlaylistRequest((1..SpotifyBandPlaylistService.MAX_BAND_COUNT + 1).map { "Band $it" }),
      )

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
  }
}
