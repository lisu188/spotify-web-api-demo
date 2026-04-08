package com.lis.spotify.controller

import com.lis.spotify.AppEnvironment.LastFm
import com.lis.spotify.service.LastFmAuthenticationService
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.view.RedirectView

/**
 * LastFmAuthenticationController provides endpoints for handling Last.fm authentication.
 *
 * Endpoints:
 * 1. GET /auth/lastfm - Redirects the user to Last.fm's authorization page.
 * 2. GET /auth/lastfm/callback - Receives the authentication token and exchanges it for a session.
 */
@Controller
class LastFmAuthenticationController(private val lastFmAuthService: LastFmAuthenticationService) {

  private fun callbackUrl(request: HttpServletRequest): String {
    val proto = request.getHeader("X-Forwarded-Proto") ?: request.scheme
    val host = request.getHeader("X-Forwarded-Host") ?: request.serverName
    val portHeader = request.getHeader("X-Forwarded-Port")
    val port = portHeader ?: request.serverPort.toString()
    val defaultPort = if (proto == "https") "443" else "80"
    val portPart = if (port == defaultPort || port.isEmpty()) "" else ":$port"
    return "$proto://$host$portPart" + LastFm.CALLBACK_PATH
  }

  private fun isSecureRequest(request: HttpServletRequest): Boolean {
    val proto = request.getHeader("X-Forwarded-Proto") ?: request.scheme
    return proto.equals("https", ignoreCase = true) || request.isSecure
  }

  private fun createCookie(
    name: String,
    value: String,
    request: HttpServletRequest,
    httpOnly: Boolean,
  ): Cookie {
    return Cookie(name, value).apply {
      path = "/"
      isHttpOnly = httpOnly
      secure = isSecureRequest(request)
    }
  }

  private fun getCookieValue(request: HttpServletRequest, name: String): String? {
    return request.cookies
      .orEmpty()
      .firstOrNull { it.name == name }
      ?.value
      ?.takeIf { it.isNotBlank() }
  }

  @GetMapping("/auth/lastfm")
  fun authenticateUser(
    request: HttpServletRequest,
    response: HttpServletResponse,
    @RequestParam(required = false) lastFmLogin: String?,
  ): RedirectView {
    logger.debug("authenticateUser() called")
    lastFmLogin
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        response.addCookie(createCookie(LAST_FM_LOGIN_COOKIE, it, request, httpOnly = false))
      }
    val authUrl = lastFmAuthService.getAuthorizationUrl(callbackUrl(request))
    logger.info("Redirecting user to Last.fm auth URL: $authUrl")
    return RedirectView(authUrl)
  }

  @GetMapping("/auth/lastfm/callback")
  fun handleCallback(
    @RequestParam token: String?,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): String {
    logger.debug("handleCallback token={}", token)
    if (token.isNullOrEmpty()) {
      logger.warn("Token is missing in Last.fm callback")
      return "redirect:/error"
    }
    val sessionData = lastFmAuthService.getSession(token)
    logger.debug("Session data received: {}", sessionData != null)
    return if (sessionData != null) {
      val session = sessionData["session"] as? Map<*, *>
      val key = session?.get("key") as? String
      val login = (session?.get("name") as? String) ?: getCookieValue(request, LAST_FM_LOGIN_COOKIE)
      if (!key.isNullOrEmpty() && !login.isNullOrEmpty()) {
        response.addCookie(createCookie(LAST_FM_TOKEN_COOKIE, key, request, httpOnly = true))
        response.addCookie(createCookie(LAST_FM_LOGIN_COOKIE, login, request, httpOnly = false))
        lastFmAuthService.setSession(login, key)
        logger.info("Successfully authenticated Last.fm user {}", login)
      }
      "redirect:/"
    } else {
      "redirect:/error"
    }
  }

  companion object {
    private const val LAST_FM_LOGIN_COOKIE = "lastFmLogin"
    private const val LAST_FM_TOKEN_COOKIE = "lastFmToken"
    private val logger = LoggerFactory.getLogger(LastFmAuthenticationController::class.java)
  }
}
