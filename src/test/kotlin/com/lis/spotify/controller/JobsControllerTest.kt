package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobState
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.service.JobLimitExceededException
import com.lis.spotify.service.JobService
import com.lis.spotify.service.LastFmAuthenticationService
import com.lis.spotify.service.SpotifyAuthenticationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class JobsControllerTest {
  private val service = mockk<JobService>()
  private val lastFmAuthenticationService = mockk<LastFmAuthenticationService>()
  private val authService = mockk<SpotifyAuthenticationService>()
  private val controller = JobsController(service, lastFmAuthenticationService, authService)

  init {
    every { authService.isAuthorizedSession("c") } returns true
    every { authService.isAuthorized("c") } returns true
  }

  @Test
  fun startReturnsId() {
    every { lastFmAuthenticationService.isAuthorized("login", "token") } returns true
    every { service.startYearlyJob("c", "login", "token") } returns "id"
    val resp = controller.start("c", "token", JobsController.StartRequest("login"))
    assertEquals(JobId("id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startForgottenObsessionsReturnsId() {
    every { lastFmAuthenticationService.isAuthorized("login", "token") } returns true
    every { service.startForgottenObsessionsJob("c", "login", "token") } returns "forgotten-id"
    val resp =
      controller.startForgottenObsessions("c", "token", JobsController.StartRequest("login"))
    assertEquals(JobId("forgotten-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyReturnsId() {
    every { lastFmAuthenticationService.isAuthorized("login", "token") } returns true
    every { service.startPrivateMoodTaxonomyJob("c", "login", 50, "token") } returns
      "private-mood-id"

    val resp =
      controller.startPrivateMoodTaxonomy("c", "token", JobsController.StartRequest("login"))

    assertEquals(JobId("private-mood-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startRejectsUnauthorizedClient() {
    every { authService.isAuthorized("c") } returns false

    val resp = controller.start("c", "token", JobsController.StartRequest("login"))

    assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
  }

  @Test
  fun startReturnsTooManyRequestsWhenJobLimitIsExceeded() {
    every { lastFmAuthenticationService.isAuthorized("login", "token") } returns true
    every { service.startYearlyJob("c", "login", "token") } throws
      JobLimitExceededException("too many")

    val resp = controller.start("c", "token", JobsController.StartRequest("login"))

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyRejectsUnauthorizedLastFmLogin() {
    every { lastFmAuthenticationService.isAuthorized("victim", "attacker-token") } returns false

    val resp =
      controller.startPrivateMoodTaxonomy(
        "c",
        "attacker-token",
        JobsController.StartRequest("victim"),
      )

    assertEquals(HttpStatus.FORBIDDEN, resp.statusCode)
    verify(exactly = 0) { service.startPrivateMoodTaxonomyJob(any(), any(), any(), any()) }
  }

  @Test
  fun startRejectsBlankLogin() {
    val resp = controller.start("c", "token", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startForgottenObsessionsRejectsBlankLogin() {
    val resp = controller.startForgottenObsessions("c", "token", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyRejectsBlankLogin() {
    val resp = controller.startPrivateMoodTaxonomy("c", "token", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startRejectsUnauthorizedLastFmLogin() {
    every { lastFmAuthenticationService.isAuthorized("victim", "attacker-token") } returns false

    val resp = controller.start("c", "attacker-token", JobsController.StartRequest("victim"))

    assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
    verify(exactly = 0) { service.startYearlyJob(any(), any(), any()) }
  }

  @Test
  fun startRejectsUnauthorizedSession() {
    every { authService.isAuthorizedSession("forged") } returns false

    val ex =
      assertThrows(ResponseStatusException::class.java) {
        controller.start("forged", "token", JobsController.StartRequest("login"))
      }

    assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
  }

  @Test
  fun getStatusReturnsJobStatus() {
    val status = JobStatus("id", JobState.RUNNING, 25, "Processing 2025 (1/21)")
    every { service.getJobStatus("id") } returns status

    val response = controller.getStatus("id")

    assertEquals(HttpStatus.OK, response.statusCode)
    assertEquals(status, response.body)
  }
}
