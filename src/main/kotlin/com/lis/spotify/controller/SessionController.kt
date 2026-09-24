package com.lis.spotify.controller

import com.lis.spotify.AppEnvironment
import com.lis.spotify.config.WebSecurity
import com.lis.spotify.service.LastFmAuthenticationService
import com.lis.spotify.service.SpotifyAuthenticationService
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SessionController(
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
  private val lastFmAuthenticationService: LastFmAuthenticationService,
) {
  data class SessionStatus(
    val authenticated: Boolean,
    val spotifyConfigured: Boolean,
    val lastFmAuthenticated: Boolean,
    val lastFmConfigured: Boolean,
  )

  @GetMapping("/api/session")
  fun session(
    @CookieValue("clientId", defaultValue = "") clientId: String,
    @CookieValue("lastFmLogin", defaultValue = "") lastFmLogin: String,
    @CookieValue("lastFmToken", defaultValue = "") lastFmToken: String,
  ): ResponseEntity<SessionStatus> =
    ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(
        SessionStatus(
          clientId.isNotBlank() && spotifyAuthenticationService.isAuthorizedSession(clientId),
          runCatching {
              AppEnvironment.Spotify.CLIENT_ID.isNotBlank() &&
                AppEnvironment.Spotify.CLIENT_SECRET.isNotBlank() &&
                AppEnvironment.BASE_URL.isNotBlank()
            }
            .getOrDefault(false),
          lastFmLogin.isNotBlank() &&
            lastFmToken.isNotBlank() &&
            lastFmAuthenticationService.isAuthorized(lastFmLogin, lastFmToken),
          runCatching {
              AppEnvironment.LastFm.API_KEY.isNotBlank() &&
                AppEnvironment.LastFm.API_SECRET.isNotBlank()
            }
            .getOrDefault(false),
        )
      )

  @PostMapping("/api/logout")
  fun logout(
    @CookieValue("clientId", defaultValue = "") clientId: String,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): ResponseEntity<Void> {
    spotifyAuthenticationService.revokeSession(clientId)
    for (name in
      listOf("clientId", "lastFmLogin", "lastFmToken", "spotifyAuthState", "lastFmAuthState")) {
      response.addCookie(
        Cookie(name, "").apply {
          path = "/"
          maxAge = 0
          isHttpOnly = true
          secure = WebSecurity.secureCookies(request)
          setAttribute("SameSite", "Lax")
        }
      )
    }
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
  }
}
