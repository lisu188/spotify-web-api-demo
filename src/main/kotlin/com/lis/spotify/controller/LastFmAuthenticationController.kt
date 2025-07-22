package com.lis.spotify.controller

import com.lis.spotify.service.LastFmAuthenticationService
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
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
    logger.debug("authenticateUser() called")
    val authUrl = lastFmAuthService.getAuthorizationUrl()
    logger.info("Redirecting user to Last.fm auth URL: $authUrl")
    return RedirectView(authUrl)
  }

  @GetMapping("/auth/lastfm/callback")
  fun handleCallback(@RequestParam token: String?, response: HttpServletResponse): String {
    logger.debug("handleCallback token={}", token)
    if (token.isNullOrEmpty()) {
      logger.warn("Token is missing in Last.fm callback")
      return "redirect:/error"
    }
    val sessionData = lastFmAuthService.getSession(token)
    logger.debug("Session data received: {}", sessionData != null)
    return if (sessionData != null) {
      val key = ((sessionData["session"] as? Map<*, *>)?.get("key") as? String)
      if (!key.isNullOrEmpty()) {
        val cookie = Cookie("lastFmToken", key)
        cookie.path = "/"
        response.addCookie(cookie)
      }
      "redirect:/"
    } else {
      "redirect:/error"
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(LastFmAuthenticationController::class.java)
  }
}
