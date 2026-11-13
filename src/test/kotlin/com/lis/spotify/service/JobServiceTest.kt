package com.lis.spotify.service

import com.lis.spotify.domain.JobState
import com.lis.spotify.persistence.InMemoryJobStatusStore
import com.lis.spotify.persistence.StoredJobStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.RejectedExecutionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TaskScheduler

class JobServiceTest {
  @Test
  fun schedulerRejectionMarksQueuedJobFailedAndAllowsClientToRetry() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val jobStatusStore = spyk(InMemoryJobStatusStore())
    val queued = slot<StoredJobStatus>()
    every { jobStatusStore.save(match { it.state == JobState.QUEUED }) } answers
      {
        queued.captured = firstArg()
        callOriginal()
      }
    val scheduler = mockk<TaskScheduler>()
    val rejection = RejectedExecutionException("Executor is shutting down")
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } throws rejection
    val service = JobService(playlistService, mockk(relaxed = true), jobStatusStore, scheduler)

    val thrown =
      assertThrows(RejectedExecutionException::class.java) {
        service.startYearlyJob("client", "login")
      }

    assertSame(rejection, thrown)
    val failed = jobStatusStore.findById(queued.captured.jobId)!!
    assertEquals(JobState.FAILED, failed.state)
    assertEquals(0, failed.progressPercent)
    assertEquals("Playlist job could not be scheduled", failed.message)
    assertEquals("client", failed.clientId)
    assertEquals("login", failed.lastFmLogin)
    assertEquals(queued.captured.createdAt, failed.createdAt)
    assertEquals(queued.captured.expiresAt, failed.expiresAt)
    verify(exactly = 0) { playlistService.updateYearlyPlaylists(any(), any(), any(), any()) }
    val retry = slot<Runnable>()
    every { scheduler.schedule(capture(retry), any<java.util.Date>()) } returns
      mockk(relaxed = true)

    val retryId = service.startYearlyJob("client", "login")
    assertNotEquals(failed.jobId, retryId)
    assertEquals(JobState.QUEUED, service.getJobStatus(retryId)?.state)
    assertThrows(JobLimitExceededException::class.java) {
      service.startYearlyJob("client", "login")
    }
    retry.captured.run()

    assertEquals(JobState.COMPLETED, service.getJobStatus(retryId)?.state)
    assertEquals(JobState.FAILED, service.getJobStatus(failed.jobId)?.state)
    verify(exactly = 1) { playlistService.updateYearlyPlaylists("client", "login", null, any()) }
  }

  @Test
  fun schedulerRejectionPreservesOriginalErrorWhenFailedStatusCannotBeSaved() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val jobStatusStore = spyk(InMemoryJobStatusStore())
    val queued = slot<StoredJobStatus>()
    every { jobStatusStore.save(match { it.state == JobState.QUEUED }) } answers
      {
        queued.captured = firstArg()
        callOriginal()
      }
    val persistenceFailure = IllegalStateException("Status store unavailable")
    every { jobStatusStore.save(match { it.state == JobState.FAILED }) } throws persistenceFailure
    val scheduler = mockk<TaskScheduler>()
    val rejection = RejectedExecutionException("Executor is shutting down")
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } throws rejection
    val service = JobService(playlistService, mockk(relaxed = true), jobStatusStore, scheduler)

    val thrown =
      assertThrows(RejectedExecutionException::class.java) {
        service.startYearlyJob("client", "login")
      }

    assertSame(rejection, thrown)
    assertEquals(listOf(persistenceFailure), thrown.suppressed.toList())
    assertEquals(JobState.QUEUED, jobStatusStore.findById(queued.captured.jobId)?.state)
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } returns
      mockk(relaxed = true)
    val retryId = service.startYearlyJob("client", "login")
    assertEquals(JobState.QUEUED, service.getJobStatus(retryId)?.state)
    verify(exactly = 0) { playlistService.updateYearlyPlaylists(any(), any(), any(), any()) }
  }

  @Test
  fun schedulerRejectionPreservesOriginalErrorWhenQueuedStatusCannotBeRead() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val jobStatusStore = spyk(InMemoryJobStatusStore())
    val persistenceFailure = IllegalStateException("Status store unavailable")
    every { jobStatusStore.findById(any()) } throws persistenceFailure
    val scheduler = mockk<TaskScheduler>()
    val rejection = RejectedExecutionException("Executor is shutting down")
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } throws rejection
    val service = JobService(playlistService, mockk(relaxed = true), jobStatusStore, scheduler)

    val thrown =
      assertThrows(RejectedExecutionException::class.java) {
        service.startYearlyJob("client", "login")
      }

    assertSame(rejection, thrown)
    assertEquals(listOf(persistenceFailure), thrown.suppressed.toList())
    every { jobStatusStore.findById(any()) } answers { callOriginal() }
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } returns
      mockk(relaxed = true)
    val retryId = service.startYearlyJob("client", "login")
    assertEquals(JobState.QUEUED, service.getJobStatus(retryId)?.state)
    verify(exactly = 0) { playlistService.updateYearlyPlaylists(any(), any(), any(), any()) }
  }

  @Test
  fun queuedStatusSaveFailureDoesNotScheduleOrAttemptToSaveFailedStatus() {
    val jobStatusStore = spyk(InMemoryJobStatusStore())
    val persistenceFailure = IllegalStateException("Status store unavailable")
    every { jobStatusStore.save(any()) } throws persistenceFailure
    val scheduler = mockk<TaskScheduler>()
    val service =
      JobService(mockk(relaxed = true), mockk(relaxed = true), jobStatusStore, scheduler)

    val thrown =
      assertThrows(IllegalStateException::class.java) { service.startYearlyJob("client", "login") }

    assertSame(persistenceFailure, thrown)
    assertTrue(thrown.suppressed.isEmpty())
    verify(exactly = 0) { jobStatusStore.save(match { it.state == JobState.FAILED }) }
    verify(exactly = 0) { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) }
    every { jobStatusStore.save(any()) } answers { callOriginal() }
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } returns
      mockk(relaxed = true)
    val retryId = service.startYearlyJob("client", "login")
    assertEquals(JobState.QUEUED, service.getJobStatus(retryId)?.state)
  }

  @Test
  fun completedJobIsNotOverwrittenOrReleasedTwiceIfSchedulerThrowsAfterExecution() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val jobStatusStore = spyk(InMemoryJobStatusStore())
    val saved = slot<StoredJobStatus>()
    every { jobStatusStore.save(capture(saved)) } answers { callOriginal() }
    val scheduler = mockk<TaskScheduler>()
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } returns
      mockk(relaxed = true)
    val service = JobService(playlistService, mockk(relaxed = true), jobStatusStore, scheduler)
    repeat(24) { service.startYearlyJob("active-$it", "login") }
    val rejection = RejectedExecutionException("Failure after task execution")
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } answers
      {
        firstArg<Runnable>().run()
        throw rejection
      }

    val thrown =
      assertThrows(RejectedExecutionException::class.java) {
        service.startYearlyJob("completed", "login")
      }

    assertSame(rejection, thrown)
    assertEquals(JobState.COMPLETED, jobStatusStore.findById(saved.captured.jobId)?.state)
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } returns
      mockk(relaxed = true)
    service.startYearlyJob("last-slot", "login")
    assertThrows(JobLimitExceededException::class.java) {
      service.startYearlyJob("over-limit", "login")
    }
    verify(exactly = 1) { playlistService.updateYearlyPlaylists("completed", "login", null, any()) }
  }

  @Test
  fun startJobRunsAsyncAndMarksJobCompleted() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

    val jobId = service.startYearlyJob("c", "l")

    verify { playlistService.updateYearlyPlaylists("c", "l", null, any()) }
    val status = service.getJobStatus(jobId)
    assertNotNull(status)
    assertEquals(JobState.COMPLETED, status?.state)
    assertEquals(100, status?.progressPercent)
  }

  @Test
  fun startJobRejectsSecondActiveJobForClient() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    every { scheduler.schedule(any<Runnable>(), any<java.util.Date>()) } returns
      mockk(relaxed = true)
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

    service.startYearlyJob("c", "login")

    assertThrows(JobLimitExceededException::class.java) {
      service.startForgottenObsessionsJob("c", "login")
    }
  }

  @Test
  fun authFailureMarksJobFailed() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateYearlyPlaylists(any(), any(), null, any()) } throws
      AuthenticationRequiredException("LASTFM")
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

    val jobId = service.startYearlyJob("c", "last fm")

    verify { playlistService.updateYearlyPlaylists("c", "last fm", null, any()) }
    val status = service.getJobStatus(jobId)
    assertEquals(JobState.FAILED, status?.state)
    assertEquals("Last.fm authentication required", status?.message)
    assertEquals("/auth/lastfm?lastFmLogin=last+fm", status?.redirectUrl)
  }

  @Test
  fun spotifyAuthFailureRedirectsToSpotify() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateYearlyPlaylists(any(), any(), null, any()) } throws
      AuthenticationRequiredException("SPOTIFY")
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

    val jobId = service.startYearlyJob("c", "login")

    val status = service.getJobStatus(jobId)
    assertEquals(JobState.FAILED, status?.state)
    assertEquals("Spotify authentication required", status?.message)
    assertEquals("/auth/spotify", status?.redirectUrl)
  }

  @Test
  fun storedJobIncludesPersistenceMetadata() {
    val playlistService = mockk<SpotifyTopPlaylistsService>(relaxed = true)
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

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
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateForgottenObsessionsPlaylist(any(), any(), null, any()) } returns
      ForgottenObsessionsPlaylistResult("playlist-1", 12, 12, 18)
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

    val jobId = service.startForgottenObsessionsJob("c", "login")

    val status = service.getJobStatus(jobId)
    assertEquals(JobState.COMPLETED, status?.state)
    assertEquals(listOf("playlist-1"), status?.playlistIds)
    assertEquals("Forgotten obsessions playlist refreshed (12 tracks)", status?.message)
  }

  @Test
  fun forgottenObsessionsJobPreservesPlaylistIdOnPartialFailure() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every { playlistService.updateForgottenObsessionsPlaylist(any(), any(), null, any()) } throws
      PlaylistUpdateException(listOf("playlist-1"), IllegalStateException("boom"))
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

    val jobId = service.startForgottenObsessionsJob("c", "login")

    val status = service.getJobStatus(jobId)
    assertEquals(JobState.FAILED, status?.state)
    assertEquals(listOf("playlist-1"), status?.playlistIds)
  }

  @Test
  fun privateMoodTaxonomyJobStoresPlaylistIds() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every {
      privateMoodTaxonomyService.updatePrivateMoodTaxonomyPlaylists(
        any(),
        any(),
        any(),
        null,
        any(),
      )
    } returns
      PrivateMoodTaxonomyResult(
        listOf(
          PrivateMoodPlaylistResult("Anchor", "Private Mood - Anchor", "anchor-id", 12, 20),
          PrivateMoodPlaylistResult("Happy", "Private Mood - Happy", "happy-id", 10, 16),
          PrivateMoodPlaylistResult("Sad", "Private Mood - Sad", "sad-id", 9, 15),
          PrivateMoodPlaylistResult("Surge", "Private Mood - Surge", "surge-id", 8, 14),
          PrivateMoodPlaylistResult("Night Drift", "Private Mood - Night Drift", "night-id", 6, 10),
          PrivateMoodPlaylistResult("Frontier", "Private Mood - Frontier", "frontier-id", 15, 24),
        )
      )
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

    val jobId = service.startPrivateMoodTaxonomyJob("c", "login")

    val status = service.getJobStatus(jobId)
    assertEquals(JobState.COMPLETED, status?.state)
    assertEquals(
      listOf("anchor-id", "happy-id", "sad-id", "surge-id", "night-id", "frontier-id"),
      status?.playlistIds,
    )
    assertEquals(
      "Private mood taxonomy refreshed (Anchor 12, Happy 10, Sad 9, Surge 8, Night Drift 6, Frontier 15)",
      status?.message,
    )
  }

  @Test
  fun privateMoodTaxonomyJobPreservesPlaylistIdsOnFailure() {
    val playlistService = mockk<SpotifyTopPlaylistsService>()
    val privateMoodTaxonomyService = mockk<PrivateMoodTaxonomyService>(relaxed = true)
    val jobStatusStore = InMemoryJobStatusStore()
    val scheduler = mockk<TaskScheduler>()
    val runnable = slot<Runnable>()
    every { scheduler.schedule(capture(runnable), any<java.util.Date>()) } answers
      {
        runnable.captured.run()
        mockk(relaxed = true)
      }
    every {
      privateMoodTaxonomyService.updatePrivateMoodTaxonomyPlaylists(
        any(),
        any(),
        any(),
        null,
        any(),
      )
    } throws PlaylistUpdateException(listOf("anchor-id", "surge-id"), IllegalStateException("boom"))
    val service = JobService(playlistService, privateMoodTaxonomyService, jobStatusStore, scheduler)

    val jobId = service.startPrivateMoodTaxonomyJob("c", "login")

    val status = service.getJobStatus(jobId)
    assertEquals(JobState.FAILED, status?.state)
    assertEquals(listOf("anchor-id", "surge-id"), status?.playlistIds)
  }
}
