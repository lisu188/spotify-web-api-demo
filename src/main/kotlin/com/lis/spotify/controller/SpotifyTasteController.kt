package com.lis.spotify.controller

import com.lis.spotify.service.SpotifyAuthenticationService
import com.lis.spotify.service.SpotifyTasteAnalysisService
import com.lis.spotify.service.SpotifyTasteSnapshot
import com.lis.spotify.service.SpotifyTopArtistService
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class SpotifyTasteController(
  private val spotifyTasteAnalysisService: SpotifyTasteAnalysisService,
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
) {
  @GetMapping("/api/spotify/taste")
  fun taste(
    @CookieValue("clientId", defaultValue = "") clientId: String,
    @RequestParam(defaultValue = "50") limit: Int,
  ): ResponseEntity<SpotifyTasteSnapshot> {
    if (!spotifyAuthenticationService.isAuthorizedSession(clientId))
      throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Spotify authentication required")
    if (limit !in 1..SpotifyTopArtistService.MAX_LIMIT)
      throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "limit must be between 1 and ${SpotifyTopArtistService.MAX_LIMIT}",
      )

    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(spotifyTasteAnalysisService.snapshot(clientId, limit))
  }
}
