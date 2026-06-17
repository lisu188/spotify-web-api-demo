package com.lis.spotify.persistence

import com.google.cloud.firestore.Firestore
import io.mockk.mockk
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class AppStateStoreConfigurationTest {
  @Test
  fun clockUsesUtc() {
    val configuration = AppStateStoreConfiguration("")

    assertEquals(ZoneOffset.UTC, configuration.clock().zone)
  }

  @Test
  fun memoryBeanFactoriesReturnInMemoryStores() {
    val configuration = AppStateStoreConfiguration("")

    assertInstanceOf(InMemoryJobStatusStore::class.java, configuration.inMemoryJobStatusStore())
    assertInstanceOf(
      InMemorySpotifyTokenStore::class.java,
      configuration.inMemorySpotifyTokenStore(),
    )
    assertInstanceOf(
      InMemoryLastFmSessionStore::class.java,
      configuration.inMemoryLastFmSessionStore(),
    )
    assertInstanceOf(
      InMemoryRefreshStateStore::class.java,
      configuration.inMemoryRefreshStateStore(),
    )
    assertInstanceOf(
      InMemorySpotifySearchCacheStore::class.java,
      configuration.inMemorySpotifySearchCacheStore(),
    )
    assertInstanceOf(
      InMemoryLastFmRecentTracksCacheStore::class.java,
      configuration.inMemoryLastFmRecentTracksCacheStore(),
    )
  }

  @Test
  fun firestoreBeanFactoriesReturnFirestoreStores() {
    val configuration = AppStateStoreConfiguration("")
    val firestore = mockk<Firestore>()

    assertInstanceOf(
      FirestoreJobStatusStore::class.java,
      configuration.firestoreJobStatusStore(firestore),
    )
    assertInstanceOf(
      FirestoreSpotifyTokenStore::class.java,
      configuration.firestoreSpotifyTokenStore(firestore),
    )
    assertInstanceOf(
      FirestoreLastFmSessionStore::class.java,
      configuration.firestoreLastFmSessionStore(firestore),
    )
    assertInstanceOf(
      FirestoreRefreshStateStore::class.java,
      configuration.firestoreRefreshStateStore(firestore),
    )
    assertInstanceOf(
      FirestoreSpotifySearchCacheStore::class.java,
      configuration.firestoreSpotifySearchCacheStore(firestore),
    )
    assertInstanceOf(
      FirestoreLastFmRecentTracksCacheStore::class.java,
      configuration.firestoreLastFmRecentTracksCacheStore(firestore),
    )
  }
}
