package com.lis.spotify.persistence

import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import org.slf4j.LoggerFactory

private const val JOBS_COLLECTION = "jobs"
private const val SPOTIFY_AUTH_TOKENS_COLLECTION = "spotifyAuthTokens"
private const val LAST_FM_SESSIONS_COLLECTION = "lastFmSessions"
private const val REFRESH_STATE_COLLECTION = "refreshState"

abstract class FirestoreStoreSupport(protected val firestore: Firestore) {
  protected fun loadDocument(collection: String, documentId: String): DocumentSnapshot? {
    val snapshot = firestore.collection(collection).document(documentId).get().get()
    return snapshot.takeIf { it.exists() }
  }

  protected fun saveDocument(collection: String, documentId: String, data: Map<String, Any>): Unit {
    firestore.collection(collection).document(documentId).set(data).get()
  }

  protected fun queryFirst(collection: String, field: String, value: String): DocumentSnapshot? {
    val snapshot = firestore.collection(collection).whereEqualTo(field, value).limit(1).get().get()
    return snapshot.documents.firstOrNull { it.exists() }
  }
}

class FirestoreJobStatusStore(firestore: Firestore) :
  FirestoreStoreSupport(firestore), JobStatusStore {
  private val logger = LoggerFactory.getLogger(FirestoreJobStatusStore::class.java)

  override fun save(job: StoredJobStatus): StoredJobStatus {
    saveDocument(JOBS_COLLECTION, job.jobId, job.toFirestoreMap())
    logger.debug("Saved Firestore job {}", job.jobId)
    return job
  }

  override fun findById(jobId: String): StoredJobStatus? {
    val document = loadDocument(JOBS_COLLECTION, jobId) ?: return null
    return StoredJobStatus.fromDocument(document)
  }
}

class FirestoreSpotifyTokenStore(firestore: Firestore) :
  FirestoreStoreSupport(firestore), SpotifyTokenStore {
  private val logger = LoggerFactory.getLogger(FirestoreSpotifyTokenStore::class.java)

  override fun save(token: StoredSpotifyAuthToken): StoredSpotifyAuthToken {
    saveDocument(SPOTIFY_AUTH_TOKENS_COLLECTION, token.clientId, token.toFirestoreMap())
    logger.debug("Saved Firestore Spotify token {}", token.clientId)
    return token
  }

  override fun findByClientId(clientId: String): StoredSpotifyAuthToken? {
    val document = loadDocument(SPOTIFY_AUTH_TOKENS_COLLECTION, clientId) ?: return null
    return StoredSpotifyAuthToken.fromDocument(document)
  }
}

class FirestoreLastFmSessionStore(firestore: Firestore) :
  FirestoreStoreSupport(firestore), LastFmSessionStore {
  private val logger = LoggerFactory.getLogger(FirestoreLastFmSessionStore::class.java)

  override fun save(session: StoredLastFmSession): StoredLastFmSession {
    saveDocument(LAST_FM_SESSIONS_COLLECTION, session.login, session.toFirestoreMap())
    logger.debug("Saved Firestore Last.fm session {}", session.login)
    return session
  }

  override fun findByLogin(login: String): StoredLastFmSession? {
    val document = loadDocument(LAST_FM_SESSIONS_COLLECTION, login) ?: return null
    return StoredLastFmSession.fromDocument(document)
  }

  override fun findBySessionKey(sessionKey: String): StoredLastFmSession? {
    val document = queryFirst(LAST_FM_SESSIONS_COLLECTION, "sessionKey", sessionKey) ?: return null
    return StoredLastFmSession.fromDocument(document)
  }
}

class FirestoreRefreshStateStore(firestore: Firestore) :
  FirestoreStoreSupport(firestore), RefreshStateStore {
  private val logger = LoggerFactory.getLogger(FirestoreRefreshStateStore::class.java)

  override fun saveTopPlaylists(state: StoredRefreshState): StoredRefreshState {
    saveDocument(
      REFRESH_STATE_COLLECTION,
      StoredRefreshState.TOP_PLAYLISTS_DOCUMENT_ID,
      state.toFirestoreMap(),
    )
    logger.debug("Saved Firestore refresh state for top playlists")
    return state
  }

  override fun getTopPlaylists(): StoredRefreshState? {
    val document =
      loadDocument(REFRESH_STATE_COLLECTION, StoredRefreshState.TOP_PLAYLISTS_DOCUMENT_ID)
        ?: return null
    return StoredRefreshState.fromDocument(document)
  }
}
