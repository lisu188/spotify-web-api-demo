package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.PlaylistTrack
import com.lis.spotify.domain.PlaylistTracks
import com.lis.spotify.domain.Playlists
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpotifyPlaylistServiceTest {
  private val rest = mockk<SpotifyRestService>(relaxed = true)
  private val service = SpotifyPlaylistService(rest)

  @Test
  fun getDiffReturnsRemovedElements() {
    val method =
      SpotifyPlaylistService::class
        .java
        .getDeclaredMethod("getDiff", List::class.java, List::class.java)
    method.isAccessible = true
    val result = method.invoke(service, listOf("a", "b"), listOf("b")) as List<*>
    assertEquals(listOf("a"), result)
  }

  @Test
  fun playlistTrackIdsAreReturned() {
    val tracks =
      PlaylistTracks(
        listOf(PlaylistTrack(Track("1", "t", emptyList(), Album("a", "n", emptyList())))),
        null,
      )
    every { rest.doRequest(any<() -> Any>()) } returns tracks
    val ids = service.getPlaylistTrackIds("id", clientId = "cid")
    assertEquals(listOf("1"), ids)
  }

  @Test
  fun getOrCreateReturnsExisting() {
    val playlists = Playlists(listOf(Playlist("p1", "name")), null)
    every { rest.doRequest(any<() -> Any>()) } returns playlists
    val result = service.getOrCreatePlaylist("name", "cid")
    assertEquals("p1", result.id)
  }

  @Test
  fun currentUserPlaylistsPaged() {
    val p1 = Playlists(listOf(Playlist("1", "n1")), "next")
    val p2 = Playlists(listOf(Playlist("2", "n2")), null)
    every { rest.doRequest(any<() -> Any>()) } returnsMany listOf(p1, p2)
    val list = service.getCurrentUserPlaylists("cid")
    assertEquals(listOf("1", "2"), list.map { it.id })
  }

  @Test
  fun modifyPlaylistAddsAndRemoves() {
    val spied = spyk(service)
    every { spied.getPlaylistTrackIds("id", "cid") } returns listOf("1", "2")
    every { spied.deleteTracksFromPlaylist("id", listOf("1"), "cid") } returns Unit
    every { spied.addTracksToPlaylist("id", listOf("3"), "cid") } returns Unit
    val result = spied.modifyPlaylist("id", listOf("2", "3"), "cid")
    assertEquals(mapOf("added" to listOf("3"), "removed" to listOf("1")), result)
    verify(exactly = 1) { spied.getPlaylistTrackIds("id", "cid") }
    verify(exactly = 1) { spied.deleteTracksFromPlaylist("id", listOf("1"), "cid") }
    verify(exactly = 1) { spied.addTracksToPlaylist("id", listOf("3"), "cid") }
  }

  @Test
  fun createPlaylistDelegates() {
    val playlist = Playlist("1", "n")
    every { rest.doRequest(any<() -> Any>()) } returns playlist
    val result = service.createPlaylist("n", "cid")
    assertEquals("1", result.id)
  }

  @Test
  fun addTracksChunksRequests() {
    val tracks = (1..101).map { it.toString() }
    service.addTracksToPlaylist("pl", tracks, "cid")
    verify(exactly = 2) { rest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun deleteTracksChunksRequests() {
    val tracks = (1..150).map { it.toString() }
    service.deleteTracksFromPlaylist("pl", tracks, "cid")
    verify(exactly = 2) { rest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun modifyPlaylistEmptyList() {
    val result = service.modifyPlaylist("id", emptyList(), "cid")
    assertEquals(emptyList<String>(), result["added"])
    assertEquals(emptyList<String>(), result["removed"])
  }

  @Test
  fun modifyPlaylistNoChanges() {
    val spied = spyk(service)
    every { spied.getPlaylistTrackIds("id", "cid") } returns listOf("1", "2")
    every { spied.addTracksToPlaylist("id", emptyList(), "cid") } returns Unit

    val result = spied.modifyPlaylist("id", listOf("1", "2"), "cid")

    assertEquals(emptyList<String>(), result["added"])
    assertEquals(emptyList<String>(), result["removed"])
    verify(exactly = 0) { spied.deleteTracksFromPlaylist(any(), any(), any()) }
  }

  @Test
  fun deduplicatePlaylistRemovesDuplicates() {
    val spied = spyk(service)
    every { spied.getPlaylistTrackIds("pl", "cid") } returns listOf("1", "1", "2")
    every { spied.replacePlaylistTracks("pl", listOf("1", "2"), "cid") } returns Unit

    spied.deduplicatePlaylist("pl", "cid")

    verify(exactly = 1) { spied.replacePlaylistTracks("pl", listOf("1", "2"), "cid") }
  }

  @Test
  fun deduplicatePlaylistNoDuplicates() {
    val spied = spyk(service)
    every { spied.getPlaylistTrackIds("pl", "cid") } returns listOf("1", "2")

    spied.deduplicatePlaylist("pl", "cid")

    verify(exactly = 0) { spied.replacePlaylistTracks(any(), any(), any()) }
  }

  @Test
  fun deduplicatePlaylistLarge() {
    val spied = spyk(service)
    val tracks = (1..105).map { it.toString() } + listOf("1", "2")
    every { spied.getPlaylistTrackIds("pl", "cid") } returns tracks
    every { spied.replacePlaylistTracks(any(), any(), any()) } returns Unit
    every { spied.addTracksToPlaylist(any(), any(), any()) } returns Unit

    spied.deduplicatePlaylist("pl", "cid")

    val distinct = tracks.distinct()
    verify(exactly = 1) { spied.replacePlaylistTracks("pl", distinct.take(100), "cid") }
    verify(exactly = 1) { spied.addTracksToPlaylist("pl", distinct.drop(100), "cid") }
  }
}
