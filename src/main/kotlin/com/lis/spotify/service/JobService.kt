package com.lis.spotify.service

import java.util.*
import kotlin.math.roundToInt
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
              val base = (year - startYear) * 100.0 / total
              val overall = base + pct / total.toDouble()
              store.update(id, overall.roundToInt(), "year $year")
            },
            lastFmLogin,
          )
          store.complete(id)
        } catch (e: Exception) {
          store.fail(id, e.message)
        }
      },
      Date(),
    )
    return id
  }

  fun progress(id: String) = store.get(id)
}
