package com.lis.spotify.service

import com.lis.spotify.domain.JobProgress
import com.lis.spotify.domain.JobStatus
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

@Component
class ProgressStore {
  private val map = ConcurrentHashMap<String, JobProgress>()

  fun create(id: String) {
    map[id] = JobProgress(JobStatus.RUNNING, 0)
  }

  fun update(id: String, percent: Int, message: String? = null) {
    map.computeIfPresent(id) { _, old -> old.copy(percent = percent, message = message) }
  }

  fun complete(id: String) {
    map.computeIfPresent(id) { _, _ -> JobProgress(JobStatus.DONE, 100) }
  }

  fun fail(id: String, message: String?) {
    map.computeIfPresent(id) { _, _ -> JobProgress(JobStatus.ERROR, 0, message = message) }
  }

  fun get(id: String): JobProgress? = map[id]
}
