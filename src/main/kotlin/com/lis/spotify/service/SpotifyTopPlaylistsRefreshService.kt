package com.lis.spotify.service

import com.lis.spotify.persistence.RefreshStateStore
import com.lis.spotify.persistence.StoredRefreshState
import java.time.Clock
import java.time.Instant
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
  private val refreshStateStore: RefreshStateStore,
  @Value("\${spotify.top-playlists.refresh-enabled:false}") private val refreshEnabled: Boolean,
  @Value("\${spotify.top-playlists.refresh-client-id:}") private val refreshClientId: String,
  @Value("\${spotify.top-playlists.refresh-token:}") private val refreshToken: String,
  @Value("\${spotify.top-playlists.refresh-on-startup:true}")
  private val refreshOnStartupEnabled: Boolean,
  @Value("\${spotify.top-playlists.refresh-trigger-token:}") private val refreshTriggerToken: String,
) {
  private val clock: Clock = Clock.systemUTC()

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
    val startedAt = Instant.now(clock)
    val previousState = currentRefreshState()
    saveRefreshState(
      lastStartedAt = startedAt,
      lastCompletedAt = previousState?.lastCompletedAt,
      lastStatus = "STARTED",
      lastPlaylistIds = previousState?.lastPlaylistIds.orEmpty(),
      updatedAt = startedAt,
    )

    if (refreshClientId.isBlank() || refreshToken.isBlank()) {
      logger.warn(
        "Skipping configured top playlist refresh for trigger={} because refresh credentials are missing",
        trigger,
      )
      saveRefreshState(
        lastStartedAt = startedAt,
        lastCompletedAt = previousState?.lastCompletedAt,
        lastStatus = "SKIPPED_MISSING_CREDENTIALS",
        lastPlaylistIds = previousState?.lastPlaylistIds.orEmpty(),
        updatedAt = Instant.now(clock),
      )
      return null
    }

    spotifyAuthenticationService.seedRefreshToken(refreshClientId, refreshToken)
    if (!spotifyAuthenticationService.refreshToken(refreshClientId)) {
      logger.warn(
        "Skipping configured top playlist refresh for trigger={} because Spotify token refresh failed",
        trigger,
      )
      saveRefreshState(
        clientId = refreshClientId,
        lastStartedAt = startedAt,
        lastCompletedAt = previousState?.lastCompletedAt,
        lastStatus = "FAILED_SPOTIFY_REFRESH",
        lastPlaylistIds = previousState?.lastPlaylistIds.orEmpty(),
        updatedAt = Instant.now(clock),
      )
      return null
    }

    return try {
      val playlistIds = spotifyTopPlaylistsService.updateTopPlaylists(refreshClientId)
      val completedAt = Instant.now(clock)
      saveRefreshState(
        clientId = refreshClientId,
        lastStartedAt = startedAt,
        lastCompletedAt = completedAt,
        lastStatus = "COMPLETED",
        lastPlaylistIds = playlistIds,
        updatedAt = completedAt,
      )
      logger.info(
        "Configured top playlist refresh finished for trigger={} clientId={} playlists={}",
        trigger,
        refreshClientId,
        playlistIds,
      )
      playlistIds
    } catch (ex: Exception) {
      logger.error(
        "Configured top playlist refresh failed for trigger={} clientId={}",
        trigger,
        refreshClientId,
        ex,
      )
      saveRefreshState(
        clientId = refreshClientId,
        lastStartedAt = startedAt,
        lastCompletedAt = previousState?.lastCompletedAt,
        lastStatus = "FAILED",
        lastPlaylistIds = previousState?.lastPlaylistIds.orEmpty(),
        updatedAt = Instant.now(clock),
      )
      throw ex
    }
  }

  private fun currentRefreshState(): StoredRefreshState? {
    return refreshStateStore.getTopPlaylists()
  }

  private fun saveRefreshState(
    clientId: String? = refreshClientId.takeIf { it.isNotBlank() },
    lastStartedAt: Instant?,
    lastCompletedAt: Instant?,
    lastStatus: String,
    lastPlaylistIds: List<String>,
    updatedAt: Instant,
  ): StoredRefreshState {
    val state =
      StoredRefreshState(
        clientId = clientId,
        lastStartedAt = lastStartedAt,
        lastCompletedAt = lastCompletedAt,
        lastStatus = lastStatus,
        lastPlaylistIds = lastPlaylistIds,
        updatedAt = updatedAt,
      )
    return refreshStateStore.saveTopPlaylists(state)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(SpotifyTopPlaylistsRefreshService::class.java)
  }
}
