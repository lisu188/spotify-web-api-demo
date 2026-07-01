package com.lis.spotify.controller

import com.lis.spotify.domain.BandPlaylistRequest
import com.lis.spotify.service.SpotifyAuthenticationService
import com.lis.spotify.service.SpotifyBandPlaylistService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class SpotifyBandPlaylistControllerTest {
  private val service = mockk<SpotifyBandPlaylistService>()
  private val authService = mockk<SpotifyAuthenticationService>()
  private val controller = SpotifyBandPlaylistController(service, authService)

  init {
    every { authService.isAuthorizedSession("cid") } returns true
  }

  @Test
  fun createBandPlaylistReturnsOk() {
    every { service.createBandPlaylist("cid", listOf("Band A", "Band B")) } returns "playlist"

    val response =
      controller.createBandPlaylist("cid", BandPlaylistRequest(listOf("Band A", "Band B")))

    assertEquals(HttpStatus.OK, response.statusCode)
    assertEquals("playlist", response.body)
  }

  @Test
  fun createBandPlaylistReturnsBadRequestForEmptyBands() {
    val response = controller.createBandPlaylist("cid", BandPlaylistRequest(listOf(" ")))

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
  }

  @Test
  fun createBandPlaylistRejectsUnauthorizedSession() {
    every { authService.isAuthorizedSession("forged") } returns false

    val ex =
      assertThrows(ResponseStatusException::class.java) {
        controller.createBandPlaylist("forged", BandPlaylistRequest(listOf("Band A")))
      }

    assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
  }

  @Test
  fun createBandPlaylistReturnsNotFound() {
    every { service.createBandPlaylist("cid", listOf("Band A", "Band B")) } returns null

    val response =
      controller.createBandPlaylist("cid", BandPlaylistRequest(listOf("Band A", "Band B")))

    assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
  }

  @Test
  fun createBandPlaylistRejectsOversizedRawRequest() {
    // A huge raw list must be rejected on size before any per-element trimming/dedup work.
    val response =
      controller.createBandPlaylist("cid", BandPlaylistRequest((1..201).map { "Band" }))

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
  }

  @Test
  fun createBandPlaylistReturnsBadRequestForTooManyBands() {
    val response =
      controller.createBandPlaylist(
        "cid",
        BandPlaylistRequest((1..SpotifyBandPlaylistService.MAX_BAND_COUNT + 1).map { "Band $it" }),
      )

    assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
  }
}
