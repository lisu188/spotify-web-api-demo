package com.lis.spotify.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler

class JobServiceTest {
  @Test
  fun startJobRunsAsync() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val scheduler = mockk<TaskScheduler>()
    val store = ProgressStore()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk<java.util.concurrent.ScheduledFuture<*>>(relaxed = true)
      }
    every { playlistService.updateYearlyPlaylists(any(), any(), any()) } answers
      {
        val updater = arg<(Pair<Int, Int>) -> Unit>(1)
        updater(Pair(2005, 100))
      }
    val service = JobService(playlistService, scheduler, store)
    val id = service.startYearlyJob("c", "l")
    val progress = service.progress(id)!!
    assertAll(
      { assertEquals(100, progress.percent) },
      { assertEquals("DONE", progress.status.name) },
    )
  }

  @Test
  fun progressUsesDecimals() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val scheduler = mockk<TaskScheduler>()
    val store = mockk<ProgressStore>(relaxed = true)
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk<java.util.concurrent.ScheduledFuture<*>>(relaxed = true)
      }
    val updater = slot<(Pair<Int, Int>) -> Unit>()
    every { playlistService.updateYearlyPlaylists(any(), capture(updater), any()) } answers
      {
        updater.captured(Pair(2005, 50))
      }
    val service = JobService(playlistService, scheduler, store)
    service.startYearlyJob("c", "l")
    val startYear = 2005
    val endYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val total = endYear - startYear + 1
    val expected = (((endYear - 2005) * 100.0 / total) + 50 / total.toDouble()).roundToInt()
    verify { store.update(any(), expected, "year 2005") }
  }
}
