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

class InMemorySpotifySearchCacheStore : SpotifySearchCacheStore {
  private val logger = LoggerFactory.getLogger(InMemorySpotifySearchCacheStore::class.java)
  private val entries = ConcurrentHashMap<String, StoredSpotifySearchCacheEntry>()

  override fun save(entry: StoredSpotifySearchCacheEntry): StoredSpotifySearchCacheEntry {
    entries[entry.cacheKey] = entry
    logger.debug("Saved in-memory Spotify search cache {}", entry.cacheKey)
    return entry
  }

  override fun findByKey(cacheKey: String): StoredSpotifySearchCacheEntry? {
    return entries[cacheKey]
  }

  fun clear() {
    entries.clear()
  }
}

class InMemoryLastFmRecentTracksCacheStore : LastFmRecentTracksCacheStore {
  private val logger = LoggerFactory.getLogger(InMemoryLastFmRecentTracksCacheStore::class.java)
  private val pages = ConcurrentHashMap<String, StoredLastFmRecentTracksPage>()

  override fun save(page: StoredLastFmRecentTracksPage): StoredLastFmRecentTracksPage {
    pages[page.cacheKey] = page
    logger.debug("Saved in-memory Last.fm recent-tracks cache {}", page.cacheKey)
    return page
  }

  override fun findByKey(cacheKey: String): StoredLastFmRecentTracksPage? {
    return pages[cacheKey]
  }

  fun clear() {
    pages.clear()
  }
}
