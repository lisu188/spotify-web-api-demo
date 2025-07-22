package com.lis.spotify.service

import java.util.*
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service

@Service
class JobService(
  private val playlistService: SpotifyTopPlaylistsService,
  private val scheduler: TaskScheduler,
  private val store: ProgressStore,
) {
  private val logger = LoggerFactory.getLogger(JobService::class.java)

  fun startYearlyJob(clientId: String, lastFmLogin: String): String {
    val id = UUID.randomUUID().toString()
    logger.info(
      "Scheduling yearly playlist update for clientId={} lastFmLogin={}",
      clientId,
      lastFmLogin,
    )
    store.create(id)
    scheduler.schedule(
      {
        try {
          val startYear = 2005
          val endYear = Calendar.getInstance().get(Calendar.YEAR)
          val total = endYear - startYear + 1
          playlistService.updateYearlyPlaylists(
            clientId,
            { (year, pct) ->
              val base = (endYear - year) * 100.0 / total
              val overall = base + pct / total.toDouble()
              store.update(id, overall.roundToInt(), "year $year")
            },
            lastFmLogin,
          )
          store.complete(id)
        } catch (e: AuthenticationRequiredException) {
          logger.error("Authentication required during yearly job {}", id, e)
          store.fail(id, "AUTH_${e.provider}")
        } catch (e: Exception) {
          logger.error("Yearly playlist update failed for job {}", id, e)
          store.fail(id, e.message)
        }
      },
      Date(),
    )
    logger.info("Yearly playlist update job {} scheduled", id)
    return id
  }

  fun progress(id: String) = store.get(id)
}
