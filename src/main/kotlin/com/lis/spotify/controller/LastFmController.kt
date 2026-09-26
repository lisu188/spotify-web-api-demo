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

import com.lis.spotify.service.LastFmHistoryService
import com.lis.spotify.service.LastFmLibraryExport
import com.lis.spotify.service.LastFmLibraryPage
import com.lis.spotify.service.LastFmMonthSummary
import com.lis.spotify.service.LastFmService
import com.lis.spotify.service.LastFmYearSummary
import com.lis.spotify.service.SpotifyAuthenticationService
import java.time.YearMonth
import java.time.format.DateTimeParseException
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
  private val lastFmHistoryService: LastFmHistoryService = LastFmHistoryService(),
) {
  private val publicLibraryUsers =
    configuredLibraryUsers
      .split(",")
      .map { it.trim().lowercase() }
      .filter { it.isNotBlank() }
      .toSet()

  @GetMapping("/api/lastfm/users/{lastFmLogin}/library")
  fun libraryExport(@PathVariable("lastFmLogin") lastFmLogin: String): LastFmLibraryExport {
    requirePublicLibraryUser(lastFmLogin)
    logger.debug("Fetching full public Last.fm library export")
    return lastFmService.libraryExport(lastFmLogin)
  }

  @GetMapping("/api/lastfm/users/{lastFmLogin}/history")
  fun yearlyHistory(
    @PathVariable("lastFmLogin") lastFmLogin: String,
    @RequestParam fromYear: Int,
    @RequestParam toYear: Int,
    @RequestParam(defaultValue = "10") limit: Int,
  ): List<LastFmYearSummary> {
    requirePublicLibraryUser(lastFmLogin)
    return try {
      lastFmHistoryService.yearlyArtistHistory(lastFmLogin, fromYear, toYear, limit)
    } catch (ex: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message, ex)
    }
  }

  @GetMapping("/api/lastfm/users/{lastFmLogin}/monthly-history")
  fun monthlyHistory(
    @PathVariable("lastFmLogin") lastFmLogin: String,
    @RequestParam from: String,
    @RequestParam to: String,
    @RequestParam(defaultValue = "50") limit: Int,
  ): List<LastFmMonthSummary> {
    requirePublicLibraryUser(lastFmLogin)
    return try {
      lastFmHistoryService.monthlyArtistHistory(
        lastFmLogin,
        YearMonth.parse(from),
        YearMonth.parse(to),
        limit,
      )
    } catch (ex: DateTimeParseException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to must use YYYY-MM", ex)
    } catch (ex: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message, ex)
    }
  }

  @GetMapping("/api/lastfm/users/{lastFmLogin}/artists")
  fun libraryArtists(
    @PathVariable("lastFmLogin") lastFmLogin: String,
    @RequestParam(defaultValue = "1") page: Int,
    @RequestParam(defaultValue = "200") limit: Int,
  ): LastFmLibraryPage {
    requirePublicLibraryUser(lastFmLogin)
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

  private fun requirePublicLibraryUser(lastFmLogin: String) {
    if (!lastFmLogin.matches(Regex("[A-Za-z0-9_-]{1,64}")))
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last.fm username")
    if (lastFmLogin.lowercase() !in publicLibraryUsers)
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "Last.fm library is not exposed")
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LastFmController::class.java)
  }
}
