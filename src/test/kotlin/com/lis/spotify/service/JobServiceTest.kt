package com.lis.spotify.service

import com.lis.spotify.domain.JobState
import com.lis.spotify.persistence.InMemoryJobStatusStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler

class JobServiceTest {
  @Test
  fun startJobRunsAsyncAndMarksJobCompleted() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    val service = JobService(playlistService, jobStatusStore, scheduler)

    val jobId = service.startYearlyJob("c", "l")

    verify { playlistService.updateYearlyPlaylists("c", "l", any()) }
    val status = service.getJobStatus(jobId)
    assertNotNull(status)
    assertEquals(JobState.COMPLETED, status?.state)
    assertEquals(100, status?.progressPercent)
  }

  @Test
  fun authFailureMarksJobFailed() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateYearlyPlaylists(any(), any(), any()) } throws
      AuthenticationRequiredException("LASTFM")
    val service = JobService(playlistService, jobStatusStore, scheduler)

    val jobId = service.startYearlyJob("c", "last fm")

    verify { playlistService.updateYearlyPlaylists("c", "last fm", any()) }
    val status = service.getJobStatus(jobId)
    assertEquals(JobState.FAILED, status?.state)
    assertEquals("Last.fm authentication required", status?.message)
    assertEquals("/auth/lastfm?lastFmLogin=last+fm", status?.redirectUrl)
  }

  @Test
  fun spotifyAuthFailureRedirectsToSpotify() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateYearlyPlaylists(any(), any(), any()) } throws
      AuthenticationRequiredException("SPOTIFY")
    val service = JobService(playlistService, jobStatusStore, scheduler)

    val jobId = service.startYearlyJob("c", "login")

    val status = service.getJobStatus(jobId)
    assertEquals(JobState.FAILED, status?.state)
    assertEquals("Spotify authentication required", status?.message)
    assertEquals("/auth/spotify", status?.redirectUrl)
  }

  @Test
  fun storedJobIncludesPersistenceMetadata() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    val service = JobService(playlistService, jobStatusStore, scheduler)

    val jobId = service.startYearlyJob("client-id", "lastfm-login")
    val stored = jobStatusStore.findById(jobId)

    assertNotNull(stored)
    assertEquals("client-id", stored?.clientId)
    assertEquals("lastfm-login", stored?.lastFmLogin)
    assertTrue(stored!!.expiresAt.isAfter(stored.createdAt))
  }

  @Test
  fun forgottenObsessionsJobStoresPlaylistIds() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateForgottenObsessionsPlaylist(any(), any(), any()) } returns
      ForgottenObsessionsPlaylistResult("playlist-1", 12, 12, 18)
    val service = JobService(playlistService, jobStatusStore, scheduler)

    val jobId = service.startForgottenObsessionsJob("c", "login")

    val status = service.getJobStatus(jobId)
    assertEquals(JobState.COMPLETED, status?.state)
    assertEquals(listOf("playlist-1"), status?.playlistIds)
    assertEquals("Forgotten obsessions playlist refreshed (12 tracks)", status?.message)
  }

  @Test
  fun forgottenObsessionsJobPreservesPlaylistIdOnPartialFailure() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateForgottenObsessionsPlaylist(any(), any(), any()) } throws
      PlaylistUpdateException(listOf("playlist-1"), IllegalStateException("boom"))
    val service = JobService(playlistService, jobStatusStore, scheduler)

    val jobId = service.startForgottenObsessionsJob("c", "login")

    val status = service.getJobStatus(jobId)
    assertEquals(JobState.FAILED, status?.state)
    assertEquals(listOf("playlist-1"), status?.playlistIds)
  }
}
