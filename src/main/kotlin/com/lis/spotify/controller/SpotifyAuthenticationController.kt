package com.lis.spotify.controller

import com.lis.spotify.AppEnvironment.Spotify
import com.lis.spotify.domain.AuthToken
import com.lis.spotify.domain.User
import com.lis.spotify.service.SpotifyAuthenticationService
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.client.exchange
import org.springframework.web.client.postForObject
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.util.UriComponentsBuilder

@Controller
class SpotifyAuthenticationController(
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
  private val restTemplateBuilder: RestTemplateBuilder,
) {

  private fun callbackUrl(request: HttpServletRequest): String {
    val proto = request.getHeader("X-Forwarded-Proto") ?: request.scheme
    val host = request.getHeader("X-Forwarded-Host") ?: request.serverName
    val portHeader = request.getHeader("X-Forwarded-Port")
    val port = portHeader ?: request.serverPort.toString()
    val defaultPort = if (proto == "https") "443" else "80"
    val portPart = if (port == defaultPort || port.isEmpty()) "" else ":$port"
    return "$proto://$host$portPart" + Spotify.CALLBACK_PATH
  }

  fun getCurrentUserId(token: AuthToken): String? {
    logger.debug("Attempting to retrieve current user ID using provided AuthToken.")
    return try {
      val response =
        restTemplateBuilder
          .build()
          .exchange<User>(
            "https://api.spotify.com/v1/me",
            HttpMethod.GET,
            HttpEntity(null, spotifyAuthenticationService.getHeaders(token)),
          )
      logger.info("Successfully retrieved user info from Spotify.")
      response.body?.id
    } catch (ex: Exception) {
      logger.error("Failed to retrieve current user ID from Spotify API.", ex)
      null
    }
  }

  @GetMapping(Spotify.CALLBACK_PATH)
  fun callback(request: HttpServletRequest, code: String, response: HttpServletResponse): String {
    logger.info("Received callback from Spotify with code: {}", code)
    val tokenUrl =
      UriComponentsBuilder.fromHttpUrl(Spotify.TOKEN_URL)
        .queryParam("grant_type", "authorization_code")
        .queryParam("code", code)
        .queryParam("redirect_uri", callbackUrl(request))
        .build()
        .toUri()

    return try {
      val authToken =
        restTemplateBuilder
          .basicAuthentication(Spotify.CLIENT_ID, Spotify.CLIENT_SECRET)
          .build()
          .postForObject<AuthToken>(tokenUrl)
      logger.debug("Received AuthToken from Spotify (access token redacted).")
      val clientId = getCurrentUserId(authToken)
      if (clientId != null) {
        authToken.clientId = clientId
        spotifyAuthenticationService.setAuthToken(authToken)
        response.addCookie(Cookie("clientId", clientId))
        logger.info("Successfully set auth token for user: {}", clientId)
      } else {
        logger.warn("Could not retrieve client ID. Auth token not stored.")
      }
      "redirect:/"
    } catch (ex: Exception) {
      logger.error("Error occurred while handling Spotify callback.", ex)
      "redirect:/error"
    }
  }

  @GetMapping("/auth/spotify")
  fun authorize(
    request: HttpServletRequest,
    attributes: RedirectAttributes,
    response: HttpServletResponse,
    @CookieValue("clientId", defaultValue = "") clientId: String,
  ): String {
    logger.info("Authorize endpoint called. Current clientId from cookie: {}", clientId)
    val builder =
      UriComponentsBuilder.fromHttpUrl(Spotify.AUTH_URL)
        .queryParam("response_type", "code")
        .queryParam("client_id", Spotify.CLIENT_ID)
        .queryParam("scope", Spotify.SCOPES)
        .queryParam("redirect_uri", callbackUrl(request))
    logger.debug("Redirecting user to Spotify authorization URL.")
    return "redirect:" + builder.toUriString()
  }

  companion object {
    private val logger = LoggerFactory.getLogger(SpotifyAuthenticationController::class.java)
  }
}
