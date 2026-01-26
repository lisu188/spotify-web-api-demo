package com.lis.spotify.controller

import com.lis.spotify.domain.BandPlaylistRequest
import com.lis.spotify.service.SpotifyBandPlaylistService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class SpotifyBandPlaylistControllerTest {
  private val service = mockk<SpotifyBandPlaylistService>()
  private val controller = SpotifyBandPlaylistController(service)

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
  fun createBandPlaylistReturnsNotFound() {
    every { service.createBandPlaylist("cid", listOf("Band A", "Band B")) } returns null

    val response =
      controller.createBandPlaylist("cid", BandPlaylistRequest(listOf("Band A", "Band B")))

    assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
  }
}
