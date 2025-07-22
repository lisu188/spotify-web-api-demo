package com.lis.spotify.domain

enum class JobStatus {
  RUNNING,
  DONE,
  ERROR,
}

data class JobId(val jobId: String)

data class JobProgress(
  val status: JobStatus,
  val percent: Int,
  val eta: Int? = null,
  val message: String? = null,
)
