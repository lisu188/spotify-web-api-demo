package com.example.lastfm.controller

import com.lis.spotify.service.LastFmAuthenticationService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.view.RedirectView

/**
 * LastFmAuthenticationController provides endpoints for handling Last.fm authentication.
 *
 * Endpoints:
 * 1. GET /auth/lastfm - Redirects the user to Last.fm's authorization page.
 * 2. GET /auth/lastfm/callback - Receives the authentication token and exchanges it for a session.
 */
@RestController
class LastFmAuthenticationController(private val lastFmAuthService: LastFmAuthenticationService) {

  @GetMapping("/auth/lastfm")
  fun authenticateUser(): RedirectView {
    val authUrl = lastFmAuthService.getAuthorizationUrl()
    logger.info("Redirecting user to Last.fm auth URL: $authUrl")
    return RedirectView(authUrl)
  }

  @GetMapping("/auth/lastfm/callback")
  fun handleCallback(@RequestParam token: String?): ResponseEntity<Any> {
    if (token.isNullOrEmpty()) {
      logger.warn("Token is missing in Last.fm callback")
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Token is missing")
    }
    val sessionData = lastFmAuthService.getSession(token)
    return if (sessionData != null) {
      ResponseEntity.ok(sessionData)
    } else {
      ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body("Failed to authenticate with Last.fm")
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LastFmAuthenticationController::class.java)
  }
}
