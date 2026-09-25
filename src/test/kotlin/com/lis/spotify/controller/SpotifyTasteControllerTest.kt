package com.lis.spotify.controller

import com.lis.spotify.service.SpotifyAuthenticationService
import com.lis.spotify.service.SpotifyTasteAnalysisService
import com.lis.spotify.service.SpotifyTasteSnapshot
import com.lis.spotify.service.SpotifyTasteWindow
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class SpotifyTasteControllerTest {
  private val service = mockk<SpotifyTasteAnalysisService>()
  private val auth = mockk<SpotifyAuthenticationService>()
  private val controller = SpotifyTasteController(service, auth)

  @Test
  fun tasteReturnsNoStoreSnapshotForAuthorizedSession() {
    val snapshot =
      SpotifyTasteSnapshot(
        shortTerm = SpotifyTasteWindow(emptyList(), emptyList()),
        mediumTerm = SpotifyTasteWindow(emptyList(), emptyList()),
        longTerm = SpotifyTasteWindow(emptyList(), emptyList()),
      )
    every { auth.isAuthorizedSession("session_test") } returns true
    every { service.snapshot("session_test", 50) } returns snapshot

    val response = controller.taste("session_test", 50)

    assertEquals(HttpStatus.OK, response.statusCode)
    assertEquals("no-store", response.headers.cacheControl)
    assertEquals(snapshot, response.body)
  }

  @Test
  fun tasteRejectsUnauthorizedSession() {
    every { auth.isAuthorizedSession("forged") } returns false

    val ex = assertThrows(ResponseStatusException::class.java) { controller.taste("forged", 50) }

    assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
  }

  @Test
  fun tasteRejectsOversizedLimit() {
    every { auth.isAuthorizedSession("session_test") } returns true

    val ex =
      assertThrows(ResponseStatusException::class.java) { controller.taste("session_test", 51) }

    assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
  }
}
