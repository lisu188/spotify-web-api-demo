package com.lis.spotify.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class SpotifyTopPlaylistsRefreshService(
  private val spotifyAuthenticationService: SpotifyAuthenticationService,
  private val spotifyTopPlaylistsService: SpotifyTopPlaylistsService,
  @Value("\${spotify.top-playlists.refresh-enabled:false}")
  private val refreshEnabled: Boolean,
  @Value("\${spotify.top-playlists.refresh-client-id:}")
  private val refreshClientId: String,
  @Value("\${spotify.top-playlists.refresh-token:}")
  private val refreshToken: String,
  @Value("\${spotify.top-playlists.refresh-on-startup:true}")
  private val refreshOnStartupEnabled: Boolean,
  @Value("\${spotify.top-playlists.refresh-trigger-token:}")
  private val refreshTriggerToken: String,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun triggerRefreshOnStartup() {
    if (refreshEnabled && refreshOnStartupEnabled) {
      refreshConfiguredPlaylists("startup")
    }
  }

  @Scheduled(fixedDelayString = "\${spotify.top-playlists.refresh-interval-ms:21600000}")
  fun refreshOnSchedule() {
    if (refreshEnabled) {
      refreshConfiguredPlaylists("schedule")
    }
  }

  fun isTriggerAuthorized(providedToken: String?): Boolean {
    return refreshTriggerToken.isNotBlank() && refreshTriggerToken == providedToken
  }

  fun refreshConfiguredPlaylists(trigger: String): List<String>? {
    if (refreshClientId.isBlank() || refreshToken.isBlank()) {
      logger.warn(
        "Skipping configured top playlist refresh for trigger={} because refresh credentials are missing",
        trigger,
      )
      return null
    }

    spotifyAuthenticationService.seedRefreshToken(refreshClientId, refreshToken)
    if (!spotifyAuthenticationService.refreshToken(refreshClientId)) {
      logger.warn(
        "Skipping configured top playlist refresh for trigger={} because Spotify token refresh failed",
        trigger,
      )
      return null
    }

    val playlistIds = spotifyTopPlaylistsService.updateTopPlaylists(refreshClientId)
    logger.info(
      "Configured top playlist refresh finished for trigger={} clientId={} playlists={}",
      trigger,
      refreshClientId,
      playlistIds,
    )
    return playlistIds
  }

  companion object {
    private val logger = LoggerFactory.getLogger(SpotifyTopPlaylistsRefreshService::class.java)
  }
}
