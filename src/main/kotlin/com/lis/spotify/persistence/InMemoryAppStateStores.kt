package com.lis.spotify.persistence

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

class InMemoryJobStatusStore : JobStatusStore {
  private val logger = LoggerFactory.getLogger(InMemoryJobStatusStore::class.java)
  private val jobs = ConcurrentHashMap<String, StoredJobStatus>()

  override fun save(job: StoredJobStatus): StoredJobStatus {
    jobs[job.jobId] = job
    logger.debug("Saved in-memory job {}", job.jobId)
    return job
  }

  override fun findById(jobId: String): StoredJobStatus? {
    return jobs[jobId]
  }

  fun clear() {
    jobs.clear()
  }
}

class InMemorySpotifyTokenStore : SpotifyTokenStore {
  private val logger = LoggerFactory.getLogger(InMemorySpotifyTokenStore::class.java)
  private val tokens = ConcurrentHashMap<String, StoredSpotifyAuthToken>()

  override fun save(token: StoredSpotifyAuthToken): StoredSpotifyAuthToken {
    tokens[token.clientId] = token
    logger.debug("Saved in-memory Spotify token {}", token.clientId)
    return token
  }

  override fun findByClientId(clientId: String): StoredSpotifyAuthToken? {
    return tokens[clientId]
  }

  fun clear() {
    tokens.clear()
  }
}

class InMemoryLastFmSessionStore : LastFmSessionStore {
  private val logger = LoggerFactory.getLogger(InMemoryLastFmSessionStore::class.java)
  private val sessions = ConcurrentHashMap<String, StoredLastFmSession>()

  override fun save(session: StoredLastFmSession): StoredLastFmSession {
    sessions[session.login] = session
    logger.debug("Saved in-memory Last.fm session {}", session.login)
    return session
  }

  override fun findByLogin(login: String): StoredLastFmSession? {
    return sessions[login]
  }

  override fun findBySessionKey(sessionKey: String): StoredLastFmSession? {
    return sessions.values.firstOrNull { it.sessionKey == sessionKey }
  }

  fun clear() {
    sessions.clear()
  }
}

class InMemoryRefreshStateStore : RefreshStateStore {
  private val logger = LoggerFactory.getLogger(InMemoryRefreshStateStore::class.java)
  private val state = AtomicReference<StoredRefreshState?>()

  override fun saveTopPlaylists(state: StoredRefreshState): StoredRefreshState {
    this.state.set(state)
    logger.debug("Saved in-memory top playlists refresh state")
    return state
  }

  override fun getTopPlaylists(): StoredRefreshState? {
    return state.get()
  }

  fun clear() {
    state.set(null)
  }
}
