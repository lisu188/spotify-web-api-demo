package com.lis.spotify.controller

import com.lis.spotify.service.SpotifyTopPlaylistsRefreshService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class SpotifyTopPlaylistsRefreshController(
  private val spotifyTopPlaylistsRefreshService: SpotifyTopPlaylistsRefreshService,
) {
  @PostMapping("/refreshConfiguredTopPlaylists")
  fun refreshConfiguredTopPlaylists(
    @RequestHeader("X-Refresh-Token", required = false) refreshTriggerToken: String?,
  ): ResponseEntity<Any> {
    if (!spotifyTopPlaylistsRefreshService.isTriggerAuthorized(refreshTriggerToken)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(mapOf("message" to "Invalid refresh trigger token"))
    }

    val playlistIds = spotifyTopPlaylistsRefreshService.refreshConfiguredPlaylists("http")
    return if (playlistIds != null) {
      ResponseEntity.accepted().body(playlistIds)
    } else {
      ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(mapOf("message" to "Configured top playlist refresh failed"))
    }
  }
}
