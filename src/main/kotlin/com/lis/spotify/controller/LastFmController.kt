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

import com.lis.spotify.service.LastFmLibraryPage
import com.lis.spotify.service.LastFmService
import com.lis.spotify.service.SpotifyAuthenticationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class LastFmController(
  val lastFmService: LastFmService,
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
  @Value("\${lastfm.library.allowed-users:}") configuredLibraryUsers: String = "",
) {
  private val publicLibraryUsers =
    configuredLibraryUsers
      .split(",")
      .map { it.trim().lowercase() }
      .filter { it.isNotBlank() }
      .toSet()

  @GetMapping("/api/lastfm/users/{lastFmLogin}/artists")
  fun libraryArtists(
    @PathVariable("lastFmLogin") lastFmLogin: String,
    @RequestParam(defaultValue = "1") page: Int,
    @RequestParam(defaultValue = "200") limit: Int,
  ): LastFmLibraryPage {
    if (!lastFmLogin.matches(Regex("[A-Za-z0-9_-]{1,64}")))
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last.fm username")
    if (lastFmLogin.lowercase() !in publicLibraryUsers)
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "Last.fm library is not exposed")
    if (page < 1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 1")
    if (limit !in 1..LastFmService.LIBRARY_ARTISTS_MAX_PAGE_SIZE)
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "limit must be between 1 and ${LastFmService.LIBRARY_ARTISTS_MAX_PAGE_SIZE}",
      )

    logger.debug("Fetching public Last.fm library artists page {}", page)
    return lastFmService.libraryArtists(lastFmLogin, page, limit)
  }

  @PostMapping("/verifyLastFmId/{lastFmLogin}")
  fun verifyLastFmId(
    @PathVariable("lastFmLogin") lastFmLogin: String,
    @CookieValue("clientId", defaultValue = "") clientId: String,
  ): Boolean {
    if (!spotifyAuthenticationService.isAuthorizedSession(clientId))
      throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Spotify authentication required")
    if (!lastFmLogin.matches(Regex("[A-Za-z0-9_-]{1,64}")))
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last.fm username")
    logger.debug("Verifying Last.fm profile")

    val result = lastFmService.userExists(lastFmLogin)
    logger.debug("Last.fm profile exists: {}", result)
    return result
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LastFmController::class.java)
  }
}
