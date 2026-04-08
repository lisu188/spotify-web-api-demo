package com.lis.spotify.persistence

import com.google.cloud.Timestamp
import com.google.cloud.firestore.DocumentSnapshot
import com.lis.spotify.domain.AuthToken
import com.lis.spotify.domain.JobState
import com.lis.spotify.domain.JobStatus
import java.time.Duration
import java.time.Instant

data class StoredJobStatus(
  val jobId: String,
  val state: JobState,
  val progressPercent: Int,
  val message: String,
  val redirectUrl: String? = null,
  val clientId: String,
  val lastFmLogin: String,
  val createdAt: Instant,
  val updatedAt: Instant,
  val expiresAt: Instant,
) {
  fun toJobStatus(): JobStatus {
    return JobStatus(
      jobId = jobId,
      state = state,
      progressPercent = progressPercent,
      message = message,
      redirectUrl = redirectUrl,
    )
  }

  fun toFirestoreMap(): Map<String, Any> {
    val data =
      mutableMapOf<String, Any>(
        "jobId" to jobId,
        "state" to state.name,
        "progressPercent" to progressPercent,
        "message" to message,
        "clientId" to clientId,
        "lastFmLogin" to lastFmLogin,
        "createdAt" to createdAt.toFirestoreTimestamp(),
        "updatedAt" to updatedAt.toFirestoreTimestamp(),
        "expiresAt" to expiresAt.toFirestoreTimestamp(),
      )
    redirectUrl?.let { data["redirectUrl"] = it }
    return data
  }

  companion object {
    fun fromDocument(document: DocumentSnapshot): StoredJobStatus? {
      if (!document.exists()) {
        return null
      }

      val jobId = document.getString("jobId") ?: document.id
      val state =
        document.getString("state")?.let { runCatching { JobState.valueOf(it) }.getOrNull() }
          ?: return null
      val progressPercent = document.getLong("progressPercent")?.toInt() ?: 0
      val message = document.getString("message").orEmpty()
      val clientId = document.getString("clientId").orEmpty()
      val lastFmLogin = document.getString("lastFmLogin").orEmpty()
      val createdAt = document.getInstant("createdAt") ?: return null
      val updatedAt = document.getInstant("updatedAt") ?: createdAt
      val expiresAt = document.getInstant("expiresAt") ?: updatedAt

      return StoredJobStatus(
        jobId = jobId,
        state = state,
        progressPercent = progressPercent,
        message = message,
        redirectUrl = document.getString("redirectUrl"),
        clientId = clientId,
        lastFmLogin = lastFmLogin,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt,
      )
    }
  }
}

data class StoredSpotifyAuthToken(
  val clientId: String,
  val accessToken: String,
  val refreshToken: String?,
  val tokenType: String,
  val scope: String,
  val expiresAt: Instant?,
  val updatedAt: Instant,
) {
  fun toAuthToken(now: Instant): AuthToken {
    return AuthToken(
      access_token = accessToken,
      token_type = tokenType.ifBlank { "Bearer" },
      scope = scope,
      expires_in = expiresAt.remainingSecondsFrom(now),
      refresh_token = refreshToken,
      clientId = clientId,
    )
  }

  fun toFirestoreMap(): Map<String, Any> {
    val data =
      mutableMapOf<String, Any>(
        "clientId" to clientId,
        "access_token" to accessToken,
        "token_type" to tokenType,
        "scope" to scope,
        "updatedAt" to updatedAt.toFirestoreTimestamp(),
      )
    refreshToken?.let { data["refresh_token"] = it }
    expiresAt?.let { data["expiresAt"] = it.toFirestoreTimestamp() }
    return data
  }

  companion object {
    fun fromAuthToken(token: AuthToken, now: Instant): StoredSpotifyAuthToken {
      return StoredSpotifyAuthToken(
        clientId =
          requireNotNull(token.clientId) { "clientId is required for persisted Spotify tokens" },
        accessToken = token.access_token,
        refreshToken = token.refresh_token,
        tokenType = token.token_type,
        scope = token.scope,
        expiresAt = token.expires_in.takeIf { it > 0 }?.let { now.plusSeconds(it.toLong()) },
        updatedAt = now,
      )
    }

    fun fromDocument(document: DocumentSnapshot): StoredSpotifyAuthToken? {
      if (!document.exists()) {
        return null
      }

      val clientId = document.getString("clientId") ?: document.id
      return StoredSpotifyAuthToken(
        clientId = clientId,
        accessToken = document.getString("access_token").orEmpty(),
        refreshToken = document.getString("refresh_token"),
        tokenType = document.getString("token_type") ?: "Bearer",
        scope = document.getString("scope").orEmpty(),
        expiresAt = document.getInstant("expiresAt"),
        updatedAt = document.getInstant("updatedAt") ?: Instant.EPOCH,
      )
    }
  }
}

data class StoredLastFmSession(val login: String, val sessionKey: String, val updatedAt: Instant) {
  fun toFirestoreMap(): Map<String, Any> {
    return mapOf(
      "login" to login,
      "sessionKey" to sessionKey,
      "updatedAt" to updatedAt.toFirestoreTimestamp(),
    )
  }

  companion object {
    fun fromDocument(document: DocumentSnapshot): StoredLastFmSession? {
      if (!document.exists()) {
        return null
      }

      val login = document.getString("login") ?: document.id
      val sessionKey = document.getString("sessionKey") ?: return null
      return StoredLastFmSession(
        login = login,
        sessionKey = sessionKey,
        updatedAt = document.getInstant("updatedAt") ?: Instant.EPOCH,
      )
    }
  }
}

data class StoredRefreshState(
  val clientId: String?,
  val lastStartedAt: Instant?,
  val lastCompletedAt: Instant?,
  val lastStatus: String,
  val lastPlaylistIds: List<String>,
  val updatedAt: Instant,
) {
  fun toFirestoreMap(): Map<String, Any> {
    val data =
      mutableMapOf<String, Any>(
        "lastStatus" to lastStatus,
        "lastPlaylistIds" to lastPlaylistIds,
        "updatedAt" to updatedAt.toFirestoreTimestamp(),
      )
    clientId?.let { data["clientId"] = it }
    lastStartedAt?.let { data["lastStartedAt"] = it.toFirestoreTimestamp() }
    lastCompletedAt?.let { data["lastCompletedAt"] = it.toFirestoreTimestamp() }
    return data
  }

  companion object {
    const val TOP_PLAYLISTS_DOCUMENT_ID = "topPlaylists"

    fun fromDocument(document: DocumentSnapshot): StoredRefreshState? {
      if (!document.exists()) {
        return null
      }

      @Suppress("UNCHECKED_CAST")
      return StoredRefreshState(
        clientId = document.getString("clientId"),
        lastStartedAt = document.getInstant("lastStartedAt"),
        lastCompletedAt = document.getInstant("lastCompletedAt"),
        lastStatus = document.getString("lastStatus").orEmpty(),
        lastPlaylistIds =
          (document.get("lastPlaylistIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        updatedAt = document.getInstant("updatedAt") ?: Instant.EPOCH,
      )
    }
  }
}

internal fun Instant.toFirestoreTimestamp(): Timestamp {
  return Timestamp.ofTimeSecondsAndNanos(epochSecond, nano)
}

internal fun DocumentSnapshot.getInstant(field: String): Instant? {
  return get(field).toInstantOrNull()
}

private fun Any?.toInstantOrNull(): Instant? {
  return when (this) {
    is Instant -> this
    is Timestamp -> Instant.ofEpochSecond(seconds, nanos.toLong())
    is java.util.Date -> toInstant()
    is String -> runCatching { Instant.parse(this) }.getOrNull()
    else -> null
  }
}

private fun Instant?.remainingSecondsFrom(now: Instant): Int {
  if (this == null) {
    return 0
  }

  return Duration.between(now, this)
    .seconds
    .coerceAtLeast(0)
    .coerceAtMost(Int.MAX_VALUE.toLong())
    .toInt()
}
