package com.lis.spotify.persistence

import com.google.api.core.ApiFutures
import com.google.cloud.firestore.CollectionReference
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QueryDocumentSnapshot
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FirestoreJobCleanupTest {
  private val now = Instant.parse("2026-09-24T10:00:00Z")

  @Test
  fun deletesOnlyDocumentsStillExpiredInsideTransaction() {
    val fixture = cleanupFixture(now.minusSeconds(1))
    assertEquals(1, fixture.store.deleteExpired(now))
    verify(exactly = 1) { fixture.transaction.delete(fixture.document) }
  }

  @Test
  fun preservesJobExtendedAfterInitialQuery() {
    val fixture = cleanupFixture(now.plusSeconds(3600))
    assertEquals(0, fixture.store.deleteExpired(now))
    verify(exactly = 0) { fixture.transaction.delete(any()) }
  }

  @Test
  fun toleratesConcurrentDeletionAndMissingExpiry() {
    for (exists in listOf(false, true)) {
      val fixture = cleanupFixture(null, exists)
      assertEquals(0, fixture.store.deleteExpired(now))
      verify(exactly = 0) { fixture.transaction.delete(any()) }
    }
  }

  private fun cleanupFixture(expiresAt: Instant?, exists: Boolean = true): Fixture {
    val firestore = mockk<Firestore>()
    val collection = mockk<CollectionReference>()
    val query = mockk<Query>()
    val results = mockk<QuerySnapshot>()
    val candidate = mockk<QueryDocumentSnapshot>()
    val document = mockk<DocumentReference>()
    val current = mockk<DocumentSnapshot>()
    val transaction = mockk<Transaction>()
    every { firestore.collection("jobs") } returns collection
    every { collection.whereLessThanOrEqualTo("expiresAt", any()) } returns query
    every { query.limit(100) } returns query
    every { query.get() } returns ApiFutures.immediateFuture(results)
    every { results.documents } returns listOf(candidate)
    every { candidate.reference } returns document
    every { transaction.get(document) } returns ApiFutures.immediateFuture(current)
    every { current.exists() } returns exists
    every { current.get("expiresAt") } returns expiresAt?.toFirestoreTimestamp()
    every { transaction.delete(document) } returns transaction
    every { firestore.runTransaction(any<Transaction.Function<Boolean>>()) } answers
      {
        ApiFutures.immediateFuture(
          firstArg<Transaction.Function<Boolean>>().updateCallback(transaction)
        )
      }
    return Fixture(FirestoreJobStatusStore(firestore), transaction, document)
  }

  private data class Fixture(
    val store: FirestoreJobStatusStore,
    val transaction: Transaction,
    val document: DocumentReference,
  )
}
