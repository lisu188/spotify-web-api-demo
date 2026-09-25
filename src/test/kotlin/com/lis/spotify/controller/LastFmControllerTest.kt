package com.lis.spotify.controller

import com.lis.spotify.service.LastFmLibraryArtist
import com.lis.spotify.service.LastFmLibraryPage
import com.lis.spotify.service.LastFmService
import com.lis.spotify.service.SpotifyAuthenticationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class LastFmControllerTest {
  private val service = mockk<LastFmService>()
  private val spotify = mockk<SpotifyAuthenticationService>()
  private val controller = LastFmController(service, spotify)

  @Test
  fun libraryExportUsesServiceForAllowlistedUser() {
    val export =
      com.lis.spotify.service.LastFmLibraryExport(
        user = "login",
        artists = listOf(LastFmLibraryArtist("Linkin Park", 10970, null, null)),
        totalArtists = 1,
        totalScrobbles = 10970,
      )
    every { service.libraryExport("login") } returns export
    val allowlistedController = LastFmController(service, spotify, "login")

    val result = allowlistedController.libraryExport("login")

    assertEquals(export, result)
  }

  @Test
  fun libraryArtistsUsesServiceForAllowlistedUser() {
    val page =
      LastFmLibraryPage(
        artists = listOf(LastFmLibraryArtist("Linkin Park", 10900, null, null)),
        page = 2,
        perPage = 100,
        totalPages = 40,
        total = 7803,
      )
    every { service.libraryArtists("login", 2, 100) } returns page
    val allowlistedController = LastFmController(service, spotify, "login")

    val result = allowlistedController.libraryArtists("login", 2, 100)

    assertEquals(page, result)
  }

  @Test
  fun libraryArtistsHidesUsersOutsideAllowlist() {
    val allowlistedController = LastFmController(service, spotify, "login")

    val ex =
      assertThrows(ResponseStatusException::class.java) {
        allowlistedController.libraryArtists("other", 1, 200)
      }

    assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
  }

  @Test
  fun verifyLastFmIdUsesService() {
    every { service.userExists("login") } returns true
    every { spotify.isAuthorizedSession("session_test") } returns true
    val result = controller.verifyLastFmId("login", "session_test")
    assertTrue(result)
  }
}
