package com.lis.spotify.persistence

import com.google.api.core.ApiFuture
import com.google.cloud.Timestamp
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QueryDocumentSnapshot
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.WriteResult
import com.lis.spotify.domain.JobState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class FirestoreAppStateStoresTest {
  @Test
  fun firestoreJobStatusStoreUsesJobsCollection() {
    val firestore = mockk<Firestore>()
    val collection = mockk<CollectionReference>()
    val document = mockk<DocumentReference>()
    val writeFuture = mockk<ApiFuture<WriteResult>>()
    val readFuture = mockk<ApiFuture<DocumentSnapshot>>()
    val snapshot = mockk<DocumentSnapshot>()
    val createdAt = Instant.parse("2026-04-08T07:00:00Z")
    val updatedAt = Instant.parse("2026-04-08T07:05:00Z")
    val expiresAt = Instant.parse("2026-04-15T07:00:00Z")
    val store = FirestoreJobStatusStore(firestore)
    val stored =
      StoredJobStatus(
        jobId = "job-1",
        state = JobState.RUNNING,
        progressPercent = 40,
        message = "Processing 2024",
        redirectUrl = "/auth/lastfm?lastFmLogin=login",
        clientId = "cid",
        lastFmLogin = "login",
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt,
      )

    every { firestore.collection("jobs") } returns collection
    every { collection.document("job-1") } returns document
    every { document.set(any<Map<String, Any>>()) } returns writeFuture
    every { writeFuture.get() } returns mockk()
    every { document.get() } returns readFuture
    every { readFuture.get() } returns snapshot
    every { snapshot.exists() } returns true
    every { snapshot.getString("jobId") } returns "job-1"
    every { snapshot.getString("state") } returns "RUNNING"
    every { snapshot.getLong("progressPercent") } returns 40L
    every { snapshot.getString("message") } returns "Processing 2024"
    every { snapshot.getString("redirectUrl") } returns "/auth/lastfm?lastFmLogin=login"
    every { snapshot.getString("clientId") } returns "cid"
    every { snapshot.getString("lastFmLogin") } returns "login"
    every { snapshot.get("createdAt") } returns createdAt.toFirestoreTimestamp()
    every { snapshot.get("updatedAt") } returns updatedAt.toFirestoreTimestamp()
    every { snapshot.get("expiresAt") } returns expiresAt.toFirestoreTimestamp()

    store.save(stored)
    val reloaded = store.findById("job-1")

    verify { firestore.collection("jobs") }
    assertNotNull(reloaded)
    assertEquals("job-1", reloaded?.jobId)
    assertEquals(expiresAt, reloaded?.expiresAt)
  }

  @Test
  fun firestoreSpotifyTokenStoreReadsStoredToken() {
    val firestore = mockk<Firestore>()
    val collection = mockk<CollectionReference>()
    val document = mockk<DocumentReference>()
    val readFuture = mockk<ApiFuture<DocumentSnapshot>>()
    val snapshot = mockk<DocumentSnapshot>()
    val expiresAt = Instant.now().plusSeconds(600)
    val updatedAt = Instant.now()
    val store = FirestoreSpotifyTokenStore(firestore)

    every { firestore.collection("spotifyAuthTokens") } returns collection
    every { collection.document("cid") } returns document
    every { document.get() } returns readFuture
    every { readFuture.get() } returns snapshot
    every { snapshot.exists() } returns true
    every { snapshot.getString("clientId") } returns "cid"
    every { snapshot.getString("access_token") } returns "access"
    every { snapshot.getString("refresh_token") } returns "refresh"
    every { snapshot.getString("token_type") } returns "Bearer"
    every { snapshot.getString("scope") } returns "scope"
    every { snapshot.get("expiresAt") } returns expiresAt.toFirestoreTimestamp()
    every { snapshot.get("updatedAt") } returns updatedAt.toFirestoreTimestamp()

    val token = store.findByClientId("cid")

    assertNotNull(token)
    assertEquals("access", token?.accessToken)
    assertEquals("refresh", token?.refreshToken)
  }

  @Test
  fun firestoreLastFmSessionStoreQueriesBySessionKey() {
    val firestore = mockk<Firestore>()
    val collection = mockk<CollectionReference>()
    val query = mockk<Query>()
    val future = mockk<ApiFuture<QuerySnapshot>>()
    val querySnapshot = mockk<QuerySnapshot>()
    val document = mockk<QueryDocumentSnapshot>()
    val updatedAt = Instant.parse("2026-04-08T07:00:00Z")
    val store = FirestoreLastFmSessionStore(firestore)

    every { firestore.collection("lastFmSessions") } returns collection
    every { collection.whereEqualTo("sessionKey", "session") } returns query
    every { query.limit(1) } returns query
    every { query.get() } returns future
    every { future.get() } returns querySnapshot
    every { querySnapshot.documents } returns listOf(document)
    every { document.exists() } returns true
    every { document.getString("login") } returns "login"
    every { document.getString("sessionKey") } returns "session"
    every { document.get("updatedAt") } returns updatedAt.toFirestoreTimestamp()

    val session = store.findBySessionKey("session")

    assertNotNull(session)
    assertEquals("login", session?.login)
    assertEquals("session", session?.sessionKey)
  }

  @Test
  fun firestoreRefreshStateStoreUsesTopPlaylistsDocument() {
    val firestore = mockk<Firestore>()
    val collection = mockk<CollectionReference>()
    val document = mockk<DocumentReference>()
    val writeFuture = mockk<ApiFuture<WriteResult>>()
    val readFuture = mockk<ApiFuture<DocumentSnapshot>>()
    val snapshot = mockk<DocumentSnapshot>()
    val updatedAt = Instant.parse("2026-04-08T07:00:00Z")
    val startedAt = Instant.parse("2026-04-08T06:55:00Z")
    val completedAt = Instant.parse("2026-04-08T07:00:00Z")
    val store = FirestoreRefreshStateStore(firestore)
    val saved =
      StoredRefreshState(
        clientId = "cid",
        lastStartedAt = startedAt,
        lastCompletedAt = completedAt,
        lastStatus = "COMPLETED",
        lastPlaylistIds = listOf("p1", "p2"),
        updatedAt = updatedAt,
      )

    every { firestore.collection("refreshState") } returns collection
    every { collection.document("topPlaylists") } returns document
    every { document.set(any<Map<String, Any>>()) } returns writeFuture
    every { writeFuture.get() } returns mockk()
    every { document.get() } returns readFuture
    every { readFuture.get() } returns snapshot
    every { snapshot.exists() } returns true
    every { snapshot.getString("clientId") } returns "cid"
    every { snapshot.getString("lastStatus") } returns "COMPLETED"
    every { snapshot.get("lastPlaylistIds") } returns listOf("p1", "p2")
    every { snapshot.get("lastStartedAt") } returns
      Timestamp.ofTimeSecondsAndNanos(startedAt.epochSecond, startedAt.nano)
    every { snapshot.get("lastCompletedAt") } returns
      Timestamp.ofTimeSecondsAndNanos(completedAt.epochSecond, completedAt.nano)
    every { snapshot.get("updatedAt") } returns
      Timestamp.ofTimeSecondsAndNanos(updatedAt.epochSecond, updatedAt.nano)

    store.saveTopPlaylists(saved)
    val loaded = store.getTopPlaylists()

    verify { collection.document("topPlaylists") }
    assertNotNull(loaded)
    assertEquals(listOf("p1", "p2"), loaded?.lastPlaylistIds)
    assertEquals("COMPLETED", loaded?.lastStatus)
  }

  @Test
  fun firestoreSpotifySearchCacheStoreUsesSpotifySearchCacheCollection() {
    val firestore = mockk<Firestore>()
    val collection = mockk<CollectionReference>()
    val document = mockk<DocumentReference>()
    val writeFuture = mockk<ApiFuture<WriteResult>>()
    val readFuture = mockk<ApiFuture<DocumentSnapshot>>()
    val snapshot = mockk<DocumentSnapshot>()
    val updatedAt = Instant.parse("2026-04-08T07:00:00Z")
    val expiresAt = Instant.parse("2026-04-15T07:00:00Z")
    val store = FirestoreSpotifySearchCacheStore(firestore)
    val entry =
      StoredSpotifySearchCacheEntry(
        cacheKey = "search-key",
        clientId = "client-id",
        query = "track:title artist:artist",
        payloadJson = "{\"tracks\":{\"items\":[]}}",
        updatedAt = updatedAt,
        expiresAt = expiresAt,
      )

    every { firestore.collection("spotifySearchCache") } returns collection
    every { collection.document("search-key") } returns document
    every { document.set(any<Map<String, Any>>()) } returns writeFuture
    every { writeFuture.get() } returns mockk()
    every { document.get() } returns readFuture
    every { readFuture.get() } returns snapshot
    every { snapshot.exists() } returns true
    every { snapshot.getString("cacheKey") } returns "search-key"
    every { snapshot.getString("clientId") } returns "client-id"
    every { snapshot.getString("query") } returns "track:title artist:artist"
    every { snapshot.getString("payloadJson") } returns "{\"tracks\":{\"items\":[]}}"
    every { snapshot.get("updatedAt") } returns updatedAt.toFirestoreTimestamp()
    every { snapshot.get("expiresAt") } returns expiresAt.toFirestoreTimestamp()

    store.save(entry)
    val loaded = store.findByKey("search-key")

    verify { firestore.collection("spotifySearchCache") }
    assertNotNull(loaded)
    assertEquals("client-id", loaded?.clientId)
    assertEquals(expiresAt, loaded?.expiresAt)
  }

  @Test
  fun firestoreLastFmRecentTracksCacheStoreUsesLastFmRecentTracksCacheCollection() {
    val firestore = mockk<Firestore>()
    val collection = mockk<CollectionReference>()
    val document = mockk<DocumentReference>()
    val writeFuture = mockk<ApiFuture<WriteResult>>()
    val readFuture = mockk<ApiFuture<DocumentSnapshot>>()
    val snapshot = mockk<DocumentSnapshot>()
    val updatedAt = Instant.parse("2026-04-08T07:00:00Z")
    val expiresAt = Instant.parse("2026-04-15T07:00:00Z")
    val store = FirestoreLastFmRecentTracksCacheStore(firestore)
    val entry =
      StoredLastFmRecentTracksPage(
        cacheKey = "recent-key",
        login = "login",
        from = 1L,
        to = 2L,
        page = 3,
        sessionKey = "session",
        payloadJson = "{\"totalPages\":5,\"songs\":[]}",
        updatedAt = updatedAt,
        expiresAt = expiresAt,
      )

    every { firestore.collection("lastFmRecentTracksCache") } returns collection
    every { collection.document("recent-key") } returns document
    every { document.set(any<Map<String, Any>>()) } returns writeFuture
    every { writeFuture.get() } returns mockk()
    every { document.get() } returns readFuture
    every { readFuture.get() } returns snapshot
    every { snapshot.exists() } returns true
    every { snapshot.getString("cacheKey") } returns "recent-key"
    every { snapshot.getString("login") } returns "login"
    every { snapshot.getLong("from") } returns 1L
    every { snapshot.getLong("to") } returns 2L
    every { snapshot.getLong("page") } returns 3L
    every { snapshot.getString("sessionKey") } returns "session"
    every { snapshot.getString("payloadJson") } returns "{\"totalPages\":5,\"songs\":[]}"
    every { snapshot.get("updatedAt") } returns updatedAt.toFirestoreTimestamp()
    every { snapshot.get("expiresAt") } returns expiresAt.toFirestoreTimestamp()

    store.save(entry)
    val loaded = store.findByKey("recent-key")

    verify { firestore.collection("lastFmRecentTracksCache") }
    assertNotNull(loaded)
    assertEquals("login", loaded?.login)
    assertEquals(3, loaded?.page)
    assertEquals(expiresAt, loaded?.expiresAt)
  }
}
