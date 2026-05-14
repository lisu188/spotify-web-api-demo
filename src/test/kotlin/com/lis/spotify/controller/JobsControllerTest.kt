package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobState
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.service.JobLimitExceededException
import com.lis.spotify.service.JobService
import com.lis.spotify.service.SpotifyAuthenticationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class JobsControllerTest {
  private val service = mockk<JobService>()
  private val authService = mockk<SpotifyAuthenticationService>()
  private val controller = JobsController(service, authService)

  @Test
  fun startReturnsId() {
    every { authService.isAuthorized("c") } returns true
    every { service.startYearlyJob("c", "login") } returns "id"
    val resp = controller.start("c", JobsController.StartRequest("login"))
    assertEquals(JobId("id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startForgottenObsessionsReturnsId() {
    every { authService.isAuthorized("c") } returns true
    every { service.startForgottenObsessionsJob("c", "login") } returns "forgotten-id"
    val resp = controller.startForgottenObsessions("c", JobsController.StartRequest("login"))
    assertEquals(JobId("forgotten-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyReturnsId() {
    every { authService.isAuthorized("c") } returns true
    every { service.startPrivateMoodTaxonomyJob("c", "login", 50) } returns "private-mood-id"

    val resp = controller.startPrivateMoodTaxonomy("c", JobsController.StartRequest("login"))

    assertEquals(JobId("private-mood-id"), resp.body)
    assertEquals(HttpStatus.ACCEPTED, resp.statusCode)
  }

  @Test
  fun startRejectsUnauthorizedClient() {
    every { authService.isAuthorized("c") } returns false

    val resp = controller.start("c", JobsController.StartRequest("login"))

    assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
  }

  @Test
  fun startReturnsTooManyRequestsWhenJobLimitIsExceeded() {
    every { authService.isAuthorized("c") } returns true
    every { service.startYearlyJob("c", "login") } throws JobLimitExceededException("too many")

    val resp = controller.start("c", JobsController.StartRequest("login"))

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.statusCode)
  }

  @Test
  fun startRejectsBlankLogin() {
    val resp = controller.start("c", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startForgottenObsessionsRejectsBlankLogin() {
    val resp = controller.startForgottenObsessions("c", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
  }

  @Test
  fun startPrivateMoodTaxonomyRejectsBlankLogin() {
    val resp = controller.startPrivateMoodTaxonomy("c", JobsController.StartRequest("   "))
    assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
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
