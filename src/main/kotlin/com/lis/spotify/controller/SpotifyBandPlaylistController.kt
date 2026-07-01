/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.lis.spotify.controller

import com.lis.spotify.domain.BandPlaylistRequest
import com.lis.spotify.logging.asSafeClientIdForLogs
import com.lis.spotify.service.SpotifyAuthenticationService
import com.lis.spotify.service.SpotifyBandPlaylistService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class SpotifyBandPlaylistController(
  private val spotifyBandPlaylistService: SpotifyBandPlaylistService,
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
) {
  @PostMapping("/bandPlaylist")
  fun createBandPlaylist(
    @CookieValue("clientId", defaultValue = "") clientId: String,
    @RequestBody request: BandPlaylistRequest,
  ): ResponseEntity<String> {
    requireAuthorizedSession(clientId)
    if (request.bands.size > MAX_BAND_REQUEST_ENTRIES) {
      logger.warn(
        "Rejecting band playlist request with {} raw entries; maximum is {}",
        request.bands.size,
        MAX_BAND_REQUEST_ENTRIES,
      )
      return ResponseEntity.badRequest().build()
    }
    val bands = request.bands.map { it.trim() }.filter { it.isNotEmpty() }
    if (bands.isEmpty()) {
      logger.warn(
        "Band playlist request had no valid bands for clientId={}",
        clientId.asSafeClientIdForLogs(),
      )
      return ResponseEntity.badRequest().build()
    }

    val distinctBandCount = bands.distinctBy { it.lowercase() }.size
    if (distinctBandCount > SpotifyBandPlaylistService.MAX_BAND_COUNT) {
      logger.warn(
        "Rejecting band playlist request with {} bands; maximum is {}",
        distinctBandCount,
        SpotifyBandPlaylistService.MAX_BAND_COUNT,
      )
      return ResponseEntity.badRequest().build()
    }

    logger.info(
      "Band playlist request for {} bands (clientId={})",
      bands.size,
      clientId.asSafeClientIdForLogs(),
    )
    val playlistId = spotifyBandPlaylistService.createBandPlaylist(clientId, bands)
    return if (playlistId == null) {
      ResponseEntity.status(HttpStatus.NOT_FOUND).build()
    } else {
      ResponseEntity.ok(playlistId)
    }
  }

  private fun requireAuthorizedSession(clientId: String) {
    if (!spotifyAuthenticationService.isAuthorizedSession(clientId)) {
      logger.warn("Rejecting band playlist request for unauthorized Spotify session")
      throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Spotify authentication required")
    }
  }

  companion object {
    // Generous bound on raw request entries (far above the MAX_BAND_COUNT distinct limit) so a
    // malicious client cannot force unbounded trimming/dedup work before the size check.
    private const val MAX_BAND_REQUEST_ENTRIES = 200
    private val logger = LoggerFactory.getLogger(SpotifyBandPlaylistController::class.java)
  }
}
