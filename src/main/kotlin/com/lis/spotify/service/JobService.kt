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
    logger.info(
      "Scheduling yearly playlist update for clientId={} lastFmLogin={}",
      clientId,
      lastFmLogin,
    )
    return scheduleJob(
      clientId = clientId,
      lastFmLogin = lastFmLogin,
      queuedMessage = "Queued yearly playlist refresh",
      startMessage = "Starting yearly playlist refresh",
      failureMessage = "Yearly playlist refresh failed",
      work = { progress ->
        playlistService.updateYearlyPlaylists(clientId, lastFmLogin, progress)
        JobCompletion("Yearly playlists refreshed")
      },
    )
  }

  fun startForgottenObsessionsJob(clientId: String, lastFmLogin: String): String {
    logger.info(
      "Scheduling forgotten obsessions playlist update for clientId={} lastFmLogin={}",
      clientId,
      lastFmLogin,
    )
    return scheduleJob(
      clientId = clientId,
      lastFmLogin = lastFmLogin,
      queuedMessage = "Queued forgotten obsessions scan",
      startMessage = "Scanning Last.fm history for forgotten obsessions",
      failureMessage = "Forgotten obsessions playlist refresh failed",
      work = { progress ->
        val result =
          playlistService.updateForgottenObsessionsPlaylist(clientId, lastFmLogin, progress)
        when {
          result.playlistId != null ->
            JobCompletion(
              message =
                "Forgotten obsessions playlist refreshed (${result.matchedTrackCount} tracks)",
              playlistIds = listOf(result.playlistId),
            )
          result.candidateCount == 0 -> JobCompletion("No forgotten obsessions found yet")
          else -> JobCompletion("No Spotify matches found for forgotten obsessions")
        }
      },
    )
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

  private fun scheduleJob(
    clientId: String,
    lastFmLogin: String,
    queuedMessage: String,
    startMessage: String,
    failureMessage: String,
    work: (((Int, String) -> Unit) -> JobCompletion),
  ): String {
    val id = UUID.randomUUID().toString()
    val createdAt = Instant.now(clock)
    val expiresAt = createdAt.plus(JOB_TTL)
    updateJob(
      jobId = id,
      state = JobState.QUEUED,
      progressPercent = 0,
      message = queuedMessage,
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
          message = startMessage,
          clientId = clientId,
          lastFmLogin = lastFmLogin,
          createdAt = createdAt,
          expiresAt = expiresAt,
        )
        try {
          val completion = work { progressPercent, message ->
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
            message = completion.message,
            playlistIds = completion.playlistIds,
            clientId = clientId,
            lastFmLogin = lastFmLogin,
            createdAt = createdAt,
            expiresAt = expiresAt,
          )
        } catch (e: AuthenticationRequiredException) {
          logger.error("Authentication required during job {}", id, e)
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
          logger.error("Job {} failed", id, e)
          updateJob(
            jobId = id,
            state = JobState.FAILED,
            progressPercent = currentProgress(id),
            message = failureMessage,
            clientId = clientId,
            lastFmLogin = lastFmLogin,
            createdAt = createdAt,
            expiresAt = expiresAt,
          )
        }
      },
      Date.from(createdAt),
    )
    logger.info("Job {} scheduled", id)
    return id
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
    playlistIds: List<String> = emptyList(),
  ): JobStatus {
    val now = Instant.now(clock)
    val storedStatus =
      StoredJobStatus(
        jobId = jobId,
        state = state,
        progressPercent = progressPercent.coerceIn(0, 100),
        message = message,
        redirectUrl = redirectUrl,
        playlistIds = playlistIds,
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

  private data class JobCompletion(
    val message: String,
    val playlistIds: List<String> = emptyList(),
  )
}
