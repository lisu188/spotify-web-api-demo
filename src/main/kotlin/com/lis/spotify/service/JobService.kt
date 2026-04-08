package com.lis.spotify.service

import com.lis.spotify.domain.JobState
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.persistence.JobStatusStore
import com.lis.spotify.persistence.StoredJobStatus
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service

@Service
class JobService(
  private val playlistService: SpotifyTopPlaylistsService,
  private val jobStatusStore: JobStatusStore,
  private val scheduler: TaskScheduler,
) {
  private val logger = LoggerFactory.getLogger(JobService::class.java)
  private val clock: Clock = Clock.systemUTC()

  fun getJobStatus(jobId: String): JobStatus? {
    return jobStatusStore.findById(jobId)?.toJobStatus()
  }

  fun startYearlyJob(clientId: String, lastFmLogin: String): String {
    val id = UUID.randomUUID().toString()
    val createdAt = Instant.now(clock)
    val expiresAt = createdAt.plus(JOB_TTL)
    logger.info(
      "Scheduling yearly playlist update for clientId={} lastFmLogin={}",
      clientId,
      lastFmLogin,
    )
    updateJob(
      jobId = id,
      state = JobState.QUEUED,
      progressPercent = 0,
      message = "Queued yearly playlist refresh",
      clientId = clientId,
      lastFmLogin = lastFmLogin,
      createdAt = createdAt,
      expiresAt = expiresAt,
    )
    scheduler.schedule(
      {
        updateJob(
          jobId = id,
          state = JobState.RUNNING,
          progressPercent = 0,
          message = "Starting yearly playlist refresh",
          clientId = clientId,
          lastFmLogin = lastFmLogin,
          createdAt = createdAt,
          expiresAt = expiresAt,
        )
        try {
          playlistService.updateYearlyPlaylists(clientId, lastFmLogin) { progressPercent, message ->
            updateJob(
              jobId = id,
              state = JobState.RUNNING,
              progressPercent = progressPercent,
              message = message,
              clientId = clientId,
              lastFmLogin = lastFmLogin,
              createdAt = createdAt,
              expiresAt = expiresAt,
            )
          }
          updateJob(
            jobId = id,
            state = JobState.COMPLETED,
            progressPercent = 100,
            message = "Yearly playlists refreshed",
            clientId = clientId,
            lastFmLogin = lastFmLogin,
            createdAt = createdAt,
            expiresAt = expiresAt,
          )
        } catch (e: AuthenticationRequiredException) {
          logger.error("Authentication required during yearly job {}", id, e)
          updateJob(
            jobId = id,
            state = JobState.FAILED,
            progressPercent = currentProgress(id),
            message = authenticationMessage(e.provider),
            redirectUrl = authenticationRedirectUrl(e.provider, lastFmLogin),
            clientId = clientId,
            lastFmLogin = lastFmLogin,
            createdAt = createdAt,
            expiresAt = expiresAt,
          )
        } catch (e: Exception) {
          logger.error("Yearly playlist update failed for job {}", id, e)
          updateJob(
            jobId = id,
            state = JobState.FAILED,
            progressPercent = currentProgress(id),
            message = "Yearly playlist refresh failed",
            clientId = clientId,
            lastFmLogin = lastFmLogin,
            createdAt = createdAt,
            expiresAt = expiresAt,
          )
        }
      },
      Date.from(createdAt),
    )
    logger.info("Yearly playlist update job {} scheduled", id)
    return id
  }

  private fun currentProgress(jobId: String): Int {
    return jobStatusStore.findById(jobId)?.progressPercent ?: 0
  }

  private fun authenticationMessage(provider: String): String {
    return when (provider.uppercase()) {
      "SPOTIFY" -> "Spotify authentication required"
      "LASTFM" -> "Last.fm authentication required"
      else -> "Authentication required"
    }
  }

  private fun authenticationRedirectUrl(provider: String, lastFmLogin: String): String {
    return when (provider.uppercase()) {
      "SPOTIFY" -> "/auth/spotify"
      "LASTFM" -> {
        val encodedLogin = URLEncoder.encode(lastFmLogin, StandardCharsets.UTF_8)
        "/auth/lastfm?lastFmLogin=$encodedLogin"
      }
      else -> "/"
    }
  }

  private fun updateJob(
    jobId: String,
    state: JobState,
    progressPercent: Int,
    message: String,
    clientId: String,
    lastFmLogin: String,
    createdAt: Instant,
    expiresAt: Instant,
    redirectUrl: String? = null,
  ): JobStatus {
    val now = Instant.now(clock)
    val storedStatus =
      StoredJobStatus(
        jobId = jobId,
        state = state,
        progressPercent = progressPercent.coerceIn(0, 100),
        message = message,
        redirectUrl = redirectUrl,
        clientId = clientId,
        lastFmLogin = lastFmLogin,
        createdAt = createdAt,
        updatedAt = now,
        expiresAt = expiresAt,
      )
    val status = jobStatusStore.save(storedStatus).toJobStatus()
    logger.info(
      "Job {} updated: state={} progress={} message={}",
      jobId,
      state,
      status.progressPercent,
      message,
    )
    return status
  }

  companion object {
    private val JOB_TTL: Duration = Duration.ofDays(7)
  }
}
