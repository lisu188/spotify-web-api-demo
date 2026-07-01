package com.lis.spotify.service

import com.lis.spotify.domain.JobState
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.logging.asSafeClientIdForLogs
import com.lis.spotify.persistence.JobStatusStore
import com.lis.spotify.persistence.StoredJobStatus
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service

@Service
class JobService(
  private val playlistService: SpotifyTopPlaylistsService,
  private val privateMoodTaxonomyService: PrivateMoodTaxonomyService,
  private val jobStatusStore: JobStatusStore,
  private val scheduler: TaskScheduler,
) {
  private val logger = LoggerFactory.getLogger(JobService::class.java)
  private val clock: Clock = Clock.systemUTC()
  private val activeJobCount = AtomicInteger(0)
  private val activeClients = ConcurrentHashMap.newKeySet<String>()
  private val clientStartTimes = ConcurrentHashMap<String, ArrayDeque<Instant>>()

  fun getJobStatus(jobId: String, clientId: String? = null): JobStatus? {
    val now = Instant.now(clock)
    jobStatusStore.deleteExpired(now)
    val storedStatus =
      jobStatusStore.findById(jobId)?.takeIf { it.expiresAt.isAfter(now) } ?: return null
    if (clientId != null && storedStatus.clientId != clientId) {
      logger.warn(
        "Rejecting job status read for jobId={} by non-owner clientId={}",
        jobId,
        clientId.asSafeClientIdForLogs(),
      )
      return null
    }
    return storedStatus.toJobStatus()
  }

  fun startYearlyJob(
    clientId: String,
    lastFmLogin: String,
    lastFmSessionKey: String? = null,
  ): String {
    logger.info(
      "Scheduling yearly playlist update for clientId={} lastFmLogin={}",
      clientId.asSafeClientIdForLogs(),
      lastFmLogin,
    )
    return scheduleJob(
      clientId = clientId,
      lastFmLogin = lastFmLogin,
      queuedMessage = "Queued yearly playlist refresh",
      startMessage = "Starting yearly playlist refresh",
      failureMessage = "Yearly playlist refresh failed",
      work = { progress ->
        playlistService.updateYearlyPlaylists(clientId, lastFmLogin, lastFmSessionKey, progress)
        JobCompletion("Yearly playlists refreshed")
      },
    )
  }

  fun startForgottenObsessionsJob(
    clientId: String,
    lastFmLogin: String,
    lastFmSessionKey: String? = null,
  ): String {
    logger.info(
      "Scheduling forgotten obsessions playlist update for clientId={} lastFmLogin={}",
      clientId.asSafeClientIdForLogs(),
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
          playlistService.updateForgottenObsessionsPlaylist(
            clientId,
            lastFmLogin,
            lastFmSessionKey,
            progress,
          )
        when {
          result.playlistId != null ->
            JobCompletion(
              message = forgottenObsessionsCompletionMessage(result),
              playlistIds = listOf(result.playlistId),
            )
          result.candidateCount == 0 -> JobCompletion("No forgotten obsessions found yet")
          else -> JobCompletion("No Spotify matches found for forgotten obsessions")
        }
      },
    )
  }

  fun startPrivateMoodTaxonomyJob(
    clientId: String,
    lastFmLogin: String,
    playlistSize: Int = 50,
    lastFmSessionKey: String? = null,
  ): String {
    logger.info(
      "Scheduling private mood taxonomy playlist update for clientId={} lastFmLogin={} playlistSize={}",
      clientId.asSafeClientIdForLogs(),
      lastFmLogin,
      playlistSize,
    )
    return scheduleJob(
      clientId = clientId,
      lastFmLogin = lastFmLogin,
      queuedMessage = "Queued private mood taxonomy refresh",
      startMessage = "Scanning listening history for private moods",
      failureMessage = "Private mood taxonomy refresh failed",
      work = { progress ->
        val result =
          privateMoodTaxonomyService.updatePrivateMoodTaxonomyPlaylists(
            clientId = clientId,
            lastFmLogin = lastFmLogin,
            playlistSize = playlistSize,
            lastFmSessionKey = lastFmSessionKey,
            progress = progress,
          )
        JobCompletion(
          message = privateMoodTaxonomyCompletionMessage(result),
          playlistIds = result.playlistIds,
        )
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
    val createdAt = Instant.now(clock)
    reserveJobSlot(clientId, createdAt)
    val id = UUID.randomUUID().toString()
    val expiresAt = createdAt.plus(JOB_TTL)
    try {
      jobStatusStore.deleteExpired(createdAt)
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
          try {
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
          } catch (e: PlaylistUpdateException) {
            logger.error("Playlist update failed during job {}", id, e)
            updateJob(
              jobId = id,
              state = JobState.FAILED,
              progressPercent = currentProgress(id),
              message = failureMessage,
              playlistIds = e.playlistIds,
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
          } finally {
            releaseJobSlot(clientId)
          }
        },
        Date.from(createdAt),
      )
    } catch (e: Exception) {
      releaseJobSlot(clientId)
      throw e
    }
    logger.info("Job {} scheduled", id)
    return id
  }

  private fun reserveJobSlot(clientId: String, now: Instant) {
    pruneClientStarts(clientId, now)
    if (!activeClients.add(clientId)) {
      throw JobLimitExceededException("A playlist job is already running for this client")
    }

    val total = activeJobCount.incrementAndGet()
    if (total > MAX_ACTIVE_JOBS) {
      activeJobCount.decrementAndGet()
      activeClients.remove(clientId)
      throw JobLimitExceededException("Too many playlist jobs are running")
    }

    val starts = clientStartTimes.computeIfAbsent(clientId) { ArrayDeque() }
    synchronized(starts) {
      if (starts.size >= MAX_CLIENT_JOB_STARTS_PER_HOUR) {
        activeJobCount.decrementAndGet()
        activeClients.remove(clientId)
        throw JobLimitExceededException("Too many playlist jobs were started recently")
      }
      starts.addLast(now)
    }
  }

  private fun releaseJobSlot(clientId: String) {
    activeClients.remove(clientId)
    activeJobCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
  }

  private fun pruneClientStarts(clientId: String, now: Instant) {
    val starts = clientStartTimes[clientId] ?: return
    synchronized(starts) {
      while (starts.isNotEmpty() && starts.first().plus(CLIENT_RATE_LIMIT_WINDOW).isBefore(now)) {
        starts.removeFirst()
      }
      if (starts.isEmpty()) {
        clientStartTimes.remove(clientId, starts)
      }
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

  private fun forgottenObsessionsCompletionMessage(
    result: ForgottenObsessionsPlaylistResult
  ): String {
    return if (result.spotifyMatchCount > result.playlistTrackCount) {
      "Forgotten obsessions playlist refreshed (${result.playlistTrackCount} of ${result.spotifyMatchCount} Spotify matches)"
    } else {
      "Forgotten obsessions playlist refreshed (${result.playlistTrackCount} tracks)"
    }
  }

  private fun privateMoodTaxonomyCompletionMessage(result: PrivateMoodTaxonomyResult): String {
    val counts =
      result.playlists.joinToString(separator = ", ") { playlist ->
        "${playlist.label} ${playlist.trackCount}"
      }
    return "Private mood taxonomy refreshed ($counts)"
  }

  companion object {
    private val JOB_TTL: Duration = Duration.ofDays(7)
    private val CLIENT_RATE_LIMIT_WINDOW: Duration = Duration.ofHours(1)
    private const val MAX_ACTIVE_JOBS = 25
    private const val MAX_CLIENT_JOB_STARTS_PER_HOUR = 10
  }

  private data class JobCompletion(
    val message: String,
    val playlistIds: List<String> = emptyList(),
  )
}

class JobLimitExceededException(message: String) : RuntimeException(message)
