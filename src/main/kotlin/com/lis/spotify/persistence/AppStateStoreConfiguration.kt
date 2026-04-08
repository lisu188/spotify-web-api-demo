package com.lis.spotify.persistence

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AppStateStoreConfiguration(
  @Value("\${firestore.project-id:}") private val firestoreProjectId: String
) {
  private val logger = LoggerFactory.getLogger(AppStateStoreConfiguration::class.java)

  @Bean
  fun clock(): Clock {
    return Clock.systemUTC()
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
    name = ["app.state-store.mode"],
    havingValue = "firestore",
    matchIfMissing = true,
  )
  fun firestore(): Firestore {
    val builder = FirestoreOptions.getDefaultInstance().toBuilder()
    val projectId = configuredProjectId()
    if (projectId.isNotBlank()) {
      builder.setProjectId(projectId)
    }
    val emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST").orEmpty()
    if (emulatorHost.isNotBlank()) {
      logger.info(
        "Initializing Firestore-backed app state stores for emulator host {}",
        emulatorHost,
      )
    } else {
      logger.info(
        "Initializing Firestore-backed app state stores with Application Default Credentials"
      )
    }
    return builder.build().service
  }

  @Bean
  @ConditionalOnProperty(
    name = ["app.state-store.mode"],
    havingValue = "firestore",
    matchIfMissing = true,
  )
  fun firestoreJobStatusStore(firestore: Firestore): JobStatusStore {
    return FirestoreJobStatusStore(firestore)
  }

  @Bean
  @ConditionalOnProperty(
    name = ["app.state-store.mode"],
    havingValue = "firestore",
    matchIfMissing = true,
  )
  fun firestoreSpotifyTokenStore(firestore: Firestore): SpotifyTokenStore {
    return FirestoreSpotifyTokenStore(firestore)
  }

  @Bean
  @ConditionalOnProperty(
    name = ["app.state-store.mode"],
    havingValue = "firestore",
    matchIfMissing = true,
  )
  fun firestoreLastFmSessionStore(firestore: Firestore): LastFmSessionStore {
    return FirestoreLastFmSessionStore(firestore)
  }

  @Bean
  @ConditionalOnProperty(
    name = ["app.state-store.mode"],
    havingValue = "firestore",
    matchIfMissing = true,
  )
  fun firestoreRefreshStateStore(firestore: Firestore): RefreshStateStore {
    return FirestoreRefreshStateStore(firestore)
  }

  @Bean
  @ConditionalOnProperty(name = ["app.state-store.mode"], havingValue = "memory")
  fun inMemoryJobStatusStore(): JobStatusStore {
    logger.info("Initializing in-memory job status store")
    return InMemoryJobStatusStore()
  }

  @Bean
  @ConditionalOnProperty(name = ["app.state-store.mode"], havingValue = "memory")
  fun inMemorySpotifyTokenStore(): SpotifyTokenStore {
    logger.info("Initializing in-memory Spotify token store")
    return InMemorySpotifyTokenStore()
  }

  @Bean
  @ConditionalOnProperty(name = ["app.state-store.mode"], havingValue = "memory")
  fun inMemoryLastFmSessionStore(): LastFmSessionStore {
    logger.info("Initializing in-memory Last.fm session store")
    return InMemoryLastFmSessionStore()
  }

  @Bean
  @ConditionalOnProperty(name = ["app.state-store.mode"], havingValue = "memory")
  fun inMemoryRefreshStateStore(): RefreshStateStore {
    logger.info("Initializing in-memory refresh state store")
    return InMemoryRefreshStateStore()
  }

  private fun configuredProjectId(): String {
    return firestoreProjectId.takeIf { it.isNotBlank() }
      ?: System.getProperty("firestore.project-id")
      ?: System.getenv("GOOGLE_CLOUD_PROJECT")
      ?: System.getProperty("GOOGLE_CLOUD_PROJECT")
      ?: System.getenv("GCLOUD_PROJECT")
      ?: System.getProperty("GCLOUD_PROJECT")
      ?: ""
  }
}
