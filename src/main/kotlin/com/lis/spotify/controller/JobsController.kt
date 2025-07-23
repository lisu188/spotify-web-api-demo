package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.service.JobService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/jobs")
class JobsController(private val jobService: JobService) {

  companion object {
    private val logger = LoggerFactory.getLogger(JobsController::class.java)
  }

  data class StartRequest(val lastFmLogin: String)

  @PostMapping
  fun start(
    @CookieValue("clientId") clientId: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    logger.info("Starting yearly job for clientId={} lastFmLogin={}", clientId, request.lastFmLogin)
    val id = jobService.startYearlyJob(clientId, request.lastFmLogin)
    logger.info("Yearly job {} scheduled", id)
    return ResponseEntity.accepted().body(JobId(id))
  }
}
