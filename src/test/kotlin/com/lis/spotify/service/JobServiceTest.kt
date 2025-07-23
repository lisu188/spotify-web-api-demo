package com.lis.spotify.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler

class JobServiceTest {
  @Test
  fun startJobRunsAsync() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    val service = JobService(playlistService, scheduler)
    service.startYearlyJob("c", "l")
    verify { playlistService.updateYearlyPlaylists("c", "l") }
  }

  @Test
  fun authFailureIgnored() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateYearlyPlaylists(any(), any()) } throws
      AuthenticationRequiredException("SPOTIFY")
    val service = JobService(playlistService, scheduler)
    service.startYearlyJob("c", "l")
    verify { playlistService.updateYearlyPlaylists("c", "l") }
  }
}
