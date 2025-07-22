package com.lis.spotify.controller

import com.lis.spotify.domain.JobId
import com.lis.spotify.service.JobService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/jobs")
class JobsController(private val jobService: JobService) {

  data class StartRequest(val lastFmLogin: String)

  @PostMapping
  fun start(
    @CookieValue("clientId") clientId: String,
    @RequestBody request: StartRequest,
  ): ResponseEntity<JobId> {
    val id = jobService.startYearlyJob(clientId, request.lastFmLogin)
    return ResponseEntity.accepted().body(JobId(id))
  }

  @GetMapping("/{id}/progress")
  fun progress(@PathVariable id: String) =
    jobService.progress(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}
