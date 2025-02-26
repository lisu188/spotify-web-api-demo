package com.lis.spotify.controller

import com.lis.spotify.service.LastFmAuthenticationService
import com.lis.spotify.service.SpotifyAuthenticationService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping

@Controller
class MainController(
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
  private val lastFmAuthenticationService: LastFmAuthenticationService,
) {

  @GetMapping("/")
  fun main(
    @CookieValue("clientId", defaultValue = "") clientId: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmToken: String,
  ): String {
    logger.debug("Entering main() with clientId='{}' and lastFmToken='{}'", clientId, lastFmToken)

    val spotifyAuthorized =
      clientId.isNotEmpty() && spotifyAuthenticationService.getAuthToken(clientId) != null
    val lastFmAuthorized = lastFmToken.isNotEmpty()

    return if (spotifyAuthorized && lastFmAuthorized) {
      logger.info(
        "Both Spotify and Last.fm are authenticated; refreshing token for clientId='{}'.",
        clientId,
      )
      spotifyAuthenticationService.refreshToken(clientId)
      "forward:/index.html"
    } else {
      if (!spotifyAuthorized) {
        logger.warn("Spotify token missing or invalid; redirecting to /auth/spotify.")
        "redirect:/auth/spotify"
      } else {
        logger.warn("Last.fm token missing; redirecting to /auth/lastfm.")
        "redirect:/auth/lastfm"
      }
    }
  }

  companion object {
    private val logger: Logger = LoggerFactory.getLogger(MainController::class.java)
  }
}
