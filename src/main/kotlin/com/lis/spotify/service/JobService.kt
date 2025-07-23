package com.lis.spotify.service

import java.util.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service

@Service
class JobService(
  private val playlistService: SpotifyTopPlaylistsService,
  private val scheduler: TaskScheduler,
) {
  private val logger = LoggerFactory.getLogger(JobService::class.java)

  fun startYearlyJob(clientId: String, lastFmLogin: String): String {
    val id = UUID.randomUUID().toString()
    logger.info(
      "Scheduling yearly playlist update for clientId={} lastFmLogin={}",
      clientId,
      lastFmLogin,
    )
    scheduler.schedule(
      {
        try {
          playlistService.updateYearlyPlaylists(clientId, lastFmLogin)
        } catch (e: AuthenticationRequiredException) {
          logger.error("Authentication required during yearly job {}", id, e)
        } catch (e: Exception) {
          logger.error("Yearly playlist update failed for job {}", id, e)
        }
      },
      Date(),
    )
    logger.info("Yearly playlist update job {} scheduled", id)
    return id
  }
}
