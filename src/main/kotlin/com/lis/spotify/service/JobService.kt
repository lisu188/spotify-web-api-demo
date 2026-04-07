package com.lis.spotify.service

import com.lis.spotify.domain.JobState
import com.lis.spotify.domain.JobStatus
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service

@Service
class JobService(
  private val playlistService: SpotifyTopPlaylistsService,
  private val scheduler: TaskScheduler,
) {
  private val logger = LoggerFactory.getLogger(JobService::class.java)
  private val jobs = ConcurrentHashMap<String, JobStatus>()

  fun getJobStatus(jobId: String): JobStatus? {
    return jobs[jobId]
  }

  fun startYearlyJob(clientId: String, lastFmLogin: String): String {
    val id = UUID.randomUUID().toString()
    logger.info(
      "Scheduling yearly playlist update for clientId={} lastFmLogin={}",
      clientId,
      lastFmLogin,
    )
    updateJob(id, JobState.QUEUED, 0, "Queued yearly playlist refresh")
    scheduler.schedule(
      {
        updateJob(id, JobState.RUNNING, 0, "Starting yearly playlist refresh")
        try {
          playlistService.updateYearlyPlaylists(clientId, lastFmLogin) { progressPercent, message ->
            updateJob(id, JobState.RUNNING, progressPercent, message)
          }
          updateJob(id, JobState.COMPLETED, 100, "Yearly playlists refreshed")
        } catch (e: AuthenticationRequiredException) {
          logger.error("Authentication required during yearly job {}", id, e)
          updateJob(id, JobState.FAILED, currentProgress(id), "Last.fm authentication required")
        } catch (e: Exception) {
          logger.error("Yearly playlist update failed for job {}", id, e)
          updateJob(id, JobState.FAILED, currentProgress(id), "Yearly playlist refresh failed")
        }
      },
      Date(),
    )
    logger.info("Yearly playlist update job {} scheduled", id)
    return id
  }

  private fun currentProgress(jobId: String): Int {
    return jobs[jobId]?.progressPercent ?: 0
  }

  private fun updateJob(
    jobId: String,
    state: JobState,
    progressPercent: Int,
    message: String,
  ): JobStatus {
    val status =
      JobStatus(
        jobId = jobId,
        state = state,
        progressPercent = progressPercent.coerceIn(0, 100),
        message = message,
      )
    jobs[jobId] = status
    logger.info(
      "Job {} updated: state={} progress={} message={}",
      jobId,
      state,
      status.progressPercent,
      message,
    )
    return status
  }
}
