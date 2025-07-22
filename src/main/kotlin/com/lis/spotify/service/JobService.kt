package com.lis.spotify.service

import java.util.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service

@Service
class JobService(
  private val playlistService: SpotifyTopPlaylistsService,
  private val scheduler: TaskScheduler,
  private val store: ProgressStore,
) {
  fun startYearlyJob(clientId: String, lastFmLogin: String): String {
    val id = UUID.randomUUID().toString()
    logger.info("Scheduled yearly playlist job {} for {}", id, clientId)
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
              val base = (year - startYear) * 100 / total
              val overall = base + pct / total
              store.update(id, overall, "year $year")
            },
            lastFmLogin,
          )
          logger.info("Yearly playlist job {} completed", id)
          store.complete(id)
        } catch (e: Exception) {
          logger.error("Yearly playlist job {} failed", id, e)
          store.fail(id, e.message)
        }
      },
      Date(),
    )
    return id
  }

  fun progress(id: String) = store.get(id)

  companion object {
    private val logger = LoggerFactory.getLogger(JobService::class.java)
  }
}
