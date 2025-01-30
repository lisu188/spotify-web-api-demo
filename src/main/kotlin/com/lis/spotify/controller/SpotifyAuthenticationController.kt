package com.lis.spotify.controller

import com.lis.spotify.domain.AuthToken
import com.lis.spotify.domain.User
import com.lis.spotify.service.SpotifyAuthenticationService
import java.lang.Exception
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletResponse
import org.slf4j.Logger
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

  companion object {
    private val logger: Logger =
      LoggerFactory.getLogger(SpotifyAuthenticationController::class.java)

    // Read BASE_URL from an environment variable or use the default if it's not set
    private val BASE_URL = System.getenv("URL").orEmpty()

    // Client ID and secret still come from env vars
    val CLIENT_ID: String = System.getenv()["CLIENT_ID"].orEmpty()
    val CLIENT_SECRET: String = System.getenv()["CLIENT_SECRET"].orEmpty()

    // Build callback URL from the BASE_URL above
    val CALLBACK: String = "$BASE_URL/callback"
    val AUTH_URL = "https://accounts.spotify.com/authorize"
    val SCOPES = "user-top-read playlist-modify-public"
    val TOKEN_URL = "https://accounts.spotify.com/api/token"
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

  @GetMapping("/callback")
  fun callback(code: String, response: HttpServletResponse): String {
    logger.info("Received callback from Spotify with code: {}", code)
    val tokenUrl =
      UriComponentsBuilder.fromHttpUrl(TOKEN_URL)
        .queryParam("grant_type", "authorization_code")
        .queryParam("code", code)
        .queryParam("redirect_uri", CALLBACK)
        .build()
        .toUri()

    return try {
      val authToken =
        restTemplateBuilder
          .basicAuthentication(CLIENT_ID, CLIENT_SECRET)
          .build()
          .postForObject<AuthToken>(tokenUrl)

      if (authToken != null) {
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
      } else {
        logger.warn("AuthToken was null; could not process authentication callback.")
      }
      "redirect:/"
    } catch (ex: Exception) {
      logger.error("Error occurred while handling Spotify callback.", ex)
      "redirect:/error"
    }
  }

  @GetMapping("/authorize")
  fun authorize(
    attributes: RedirectAttributes,
    response: HttpServletResponse,
    @CookieValue("clientId", defaultValue = "") clientId: String,
  ): String {
    logger.info("Authorize endpoint called. Current clientId from cookie: {}", clientId)
    val builder =
      UriComponentsBuilder.fromHttpUrl(AUTH_URL)
        .queryParam("response_type", "code")
        .queryParam("client_id", CLIENT_ID)
        .queryParam("scope", SCOPES)
        .queryParam("redirect_uri", CALLBACK)

    logger.debug("Redirecting user to Spotify authorization URL.")
    return "redirect:" + builder.toUriString()
  }
}
