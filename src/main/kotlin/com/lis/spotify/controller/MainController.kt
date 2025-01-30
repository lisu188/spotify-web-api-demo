package com.lis.spotify.controller

import com.lis.spotify.service.SpotifyAuthenticationService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping

@Controller
class MainController(private val spotifyAuthenticationService: SpotifyAuthenticationService) {

  companion object {
    private val logger: Logger = LoggerFactory.getLogger(MainController::class.java)
  }

  @GetMapping("/")
  fun main(@CookieValue("clientId", defaultValue = "") clientId: String): String {
    logger.debug("Entering main() with clientId='{}'", clientId)

    return if (
      clientId.isNotEmpty() && spotifyAuthenticationService.getAuthToken(clientId) != null
    ) {
      logger.info(
        "Client ID is present and AuthToken exists; refreshing token for clientId='{}'.",
        clientId,
      )
      spotifyAuthenticationService.refreshToken(clientId)
      "forward:/index.html"
    } else {
      if (clientId.isEmpty()) {
        logger.warn("No clientId found in cookie; redirecting to /authorize.")
      } else {
        logger.warn("AuthToken for clientId='{}' is null; redirecting to /authorize.", clientId)
      }
      "redirect:/authorize"
    }
  }
}
