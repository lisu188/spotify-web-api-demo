package com.lis.spotify.domain

enum class JobState {
  QUEUED,
  RUNNING,
  COMPLETED,
  FAILED,
}

data class JobId(val jobId: String)

data class JobStatus(
  val jobId: String,
  val state: JobState,
  val progressPercent: Int,
  val message: String,
  val redirectUrl: String? = null,
)
