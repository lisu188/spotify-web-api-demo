package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.domain.JobStatus
import com.lis.spotify.service.JobService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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

  @GetMapping("/{jobId}")
  fun getStatus(@PathVariable jobId: String): ResponseEntity<JobStatus> {
    val status = jobService.getJobStatus(jobId) ?: return ResponseEntity.notFound().build()
    return ResponseEntity.ok(status)
  }
}
