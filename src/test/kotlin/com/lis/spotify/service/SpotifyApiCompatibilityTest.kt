package com.lis.spotify.service

import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import com.lis.spotify.persistence.InMemorySpotifySearchCacheStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpServerErrorException

class SpotifyApiCompatibilityTest {
  private val authentication = mockk<SpotifyAuthenticationService>()
  private val rest = SpotifyRestService(RestTemplateBuilder(), authentication)
  private val spotify = MockRestServiceServer.bindTo(rest.restTemplate).build()
  private val service = SpotifyPlaylistService(rest)
  private val playlistUrl = "https://api.spotify.com/v1/playlists/playlist-id/items"

  init {
    every { authentication.getHeaders("listener") } returns
      HttpHeaders().apply { setBearerAuth("test-access") }
  }

  @Test
  fun readsCurrentAndLegacyTrackFieldsAndSkipsEpisodesLocalAndUnavailableItems() {
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.GET))
      .andRespond(
        withSuccess(
          """{"items":[
          {"item":${trackJson("current")}},
          {"track":${trackJson("legacy")}},
          {"item":null},
          {"track":null},
          {"item":{"id":"episode","type":"episode","name":"A podcast"}},
          {"item":{"id":null,"type":"track","name":"Unavailable"}},
          {"is_local":true,"item":${trackJson("local")}},
          {"item":{"id":"local-file","is_local":true}},
          {}
        ],"next":"$playlistUrl?offset=9"}""",
          MediaType.APPLICATION_JSON,
        )
      )
    spotify
      .expect(requestTo("$playlistUrl?offset=9"))
      .andExpect(method(HttpMethod.GET))
      .andRespond(
        withSuccess(
          """{"items":[{"item":${trackJson("next-page")}}],"next":null}""",
          MediaType.APPLICATION_JSON,
        )
      )

    assertEquals(
      listOf("current", "legacy", "next-page"),
      service.getPlaylistTrackIds("playlist-id", "listener"),
    )
    spotify.verify()
  }

  @Test
  fun allPlaylistWritesUseItemsEndpointAndNormalizeTrackUris() {
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.POST))
      .andExpect(content().json("""{"uris":["spotify:track:first","spotify:track:second"]}"""))
      .andRespond(withSuccess("""{"snapshot_id":"added"}""", MediaType.APPLICATION_JSON))
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.DELETE))
      .andExpect(
        content()
          .json("""{"items":[{"uri":"spotify:track:first"},{"uri":"spotify:track:second"}]}""")
      )
      .andRespond(withSuccess("""{"snapshot_id":"deleted"}""", MediaType.APPLICATION_JSON))
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.PUT))
      .andExpect(content().json("""{"uris":["spotify:track:kept"]}"""))
      .andRespond(withSuccess("""{"snapshot_id":"replaced"}""", MediaType.APPLICATION_JSON))

    service.addTracksToPlaylist("playlist-id", listOf("first", "spotify:track:second"), "listener")
    service.deleteTracksFromPlaylist(
      "playlist-id",
      listOf("first", "spotify:track:second"),
      "listener",
    )
    service.replacePlaylistTracks("playlist-id", listOf("spotify:track:kept"), "listener")
    spotify.verify()
  }

  @Test
  fun failedLaterAddChunkPreservesOriginalAndRetryAddsOnlyMissingTracks() {
    val desired = (1..101).map { "new-$it" }
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.GET))
      .andRespond(withSuccess(pageJson(listOf("original")), MediaType.APPLICATION_JSON))
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.POST))
      .andExpect(content().json(urisJson(desired.take(100))))
      .andRespond(withSuccess("""{"snapshot_id":"first-chunk"}""", MediaType.APPLICATION_JSON))
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.POST))
      .andExpect(content().json(urisJson(desired.drop(100))))
      .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

    assertThrows(HttpServerErrorException::class.java) {
      service.modifyPlaylist("playlist-id", desired, "listener")
    }
    spotify.verify()
    spotify.reset()

    // This is the remote state after the partial append: the original track still exists.
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.GET))
      .andRespond(
        withSuccess(pageJson(listOf("original") + desired.take(100)), MediaType.APPLICATION_JSON)
      )
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.POST))
      .andExpect(content().json(urisJson(desired.drop(100))))
      .andRespond(withSuccess("""{"snapshot_id":"last-chunk"}""", MediaType.APPLICATION_JSON))
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.DELETE))
      .andExpect(content().json("""{"items":[{"uri":"spotify:track:original"}]}"""))
      .andRespond(withSuccess("""{"snapshot_id":"complete"}""", MediaType.APPLICATION_JSON))

    assertEquals(
      mapOf("added" to listOf("new-101"), "removed" to listOf("original")),
      service.modifyPlaylist("playlist-id", desired, "listener"),
    )
    spotify.verify()
  }

  @Test
  fun malformedTrackUriIsRejectedBeforeAnyModification() {
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.GET))
      .andRespond(withSuccess(pageJson(listOf("original")), MediaType.APPLICATION_JSON))

    assertThrows(IllegalArgumentException::class.java) {
      service.modifyPlaylist("playlist-id", listOf("valid", "spotify:episode:episode"), "listener")
    }
    spotify.verify()
  }

  @Test
  fun addAndReplaceRejectInvalidUrisBeforeSendingAnyChunk() {
    assertThrows(IllegalArgumentException::class.java) {
      service.addTracksToPlaylist(
        "playlist-id",
        (1..100).map { "$it" } + "spotify:track:",
        "listener",
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      service.replacePlaylistTracks("playlist-id", listOf("spotify:album:album"), "listener")
    }
    assertThrows(IllegalArgumentException::class.java) {
      service.replacePlaylistTracks("playlist-id", (1..101).map { "$it" }, "listener")
    }
    spotify.verify()
  }

  @Test
  fun mixedContentDuplicatesAreNeverReplacedOrDiscarded() {
    spotify
      .expect(requestTo(playlistUrl))
      .andExpect(method(HttpMethod.GET))
      .andRespond(
        withSuccess(
          """{"items":[{"item":${trackJson("same")}},{"item":${trackJson("same")}},
          {"item":{"id":"episode","type":"episode"}}],"next":null}""",
          MediaType.APPLICATION_JSON,
        )
      )

    val exception =
      assertThrows(IllegalStateException::class.java) {
        service.deduplicatePlaylist("playlist-id", "listener")
      }
    assertTrue(exception.message.orEmpty().contains("left unchanged"))
    spotify.verify()
  }

  @Test
  fun bandMixUsesCatalogSearchAndExcludesArtistsWithSimilarNames() {
    spotify
      .expect(requestTo("https://api.spotify.com/v1/artists/band"))
      .andExpect(method(HttpMethod.GET))
      .andRespond(withSuccess("""{"id":"band","name":"Same Name"}""", MediaType.APPLICATION_JSON))
    spotify
      .expect { request ->
        assertEquals("/v1/search", request.uri.path)
        val query = URLDecoder.decode(request.uri.rawQuery, StandardCharsets.UTF_8)
        assertTrue(query.contains("q=artist:\"Same Name\""), query)
        assertTrue(query.contains("type=track&limit=10&offset=0"), query)
      }
      .andExpect(method(HttpMethod.GET))
      .andRespond(
        withSuccess(
          """{"tracks":{"items":[${trackJson("match", "band")},${trackJson("wrong", "other-band")}]}}""",
          MediaType.APPLICATION_JSON,
        )
      )

    assertEquals(
      listOf("match"),
      SpotifyArtistService(rest).getArtistTopTracks("band", "listener").map { it.id },
    )
    spotify.verify()
  }

  @Test
  fun searchExplicitlyRequestsTheCurrentTenResultMaximum() {
    spotify
      .expect { request ->
        assertEquals("/v1/search", request.uri.path)
        assertTrue(request.uri.rawQuery.endsWith("type=track&limit=10"))
      }
      .andRespond(withSuccess("""{"tracks":{"items":[]}}""", MediaType.APPLICATION_JSON))
    val search = SpotifySearchService(rest, InMemorySpotifySearchCacheStore(), Clock.systemUTC())

    assertEquals(
      emptyList<Track>(),
      search.doSearch(Song("Artist", "Title"), "listener")?.tracks?.items,
    )
    spotify.verify()
  }

  @Test
  fun creationIsSerializedAcrossDirectAndIndependentProvisionerCalls() {
    val playlists = Collections.synchronizedList(mutableListOf<Playlist>())
    val created = AtomicInteger()
    val spied = spyk(service)
    every { spied.getCurrentUserPlaylists("concurrent-user") } answers
      {
        synchronized(playlists) { playlists.toMutableList() }
      }
    every { spied.createPlaylist("Shared Name", "concurrent-user", true) } answers
      {
        Thread.sleep(15)
        Playlist("created-${created.incrementAndGet()}", "Shared Name").also { playlists.add(it) }
      }
    val executor = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    try {
      val calls =
        (1..24).map { index ->
          executor.submit<String> {
            start.await()
            if (index % 2 == 0) {
              spied.getOrCreatePlaylist("Shared Name", "concurrent-user").id
            } else {
              PlaylistProvisioner(spied)
                .getOrCreate("Shared Name", "concurrent-user", ConcurrentHashMap())
                .id
            }
          }
        }
      start.countDown()
      assertEquals(setOf("created-1"), calls.map { it.get(5, TimeUnit.SECONDS) }.toSet())
      assertEquals(1, created.get())
    } finally {
      executor.shutdownNow()
    }
  }

  private fun trackJson(id: String, artistId: String = "artist") =
    """{"id":"$id","type":"track","name":"Song","artists":[{"id":"$artistId","name":"Artist"}],
       "album":{"id":"album","name":"Album","artists":[]}}"""

  private fun pageJson(ids: List<String>) =
    """{"items":[${ids.joinToString(",") { """{"item":${trackJson(it)}}""" }}],"next":null}"""

  private fun urisJson(ids: List<String>) =
    """{"uris":[${ids.joinToString(",") { "\"" + "spotify:track:" + it + "\"" }}]}"""
}
