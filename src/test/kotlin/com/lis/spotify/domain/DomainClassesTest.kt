package com.lis.spotify.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DomainClassesTest {
  @Test
  fun playlistTrackHoldsTrack() {
    val artist = Artist("a", "artist")
    val album = Album("b", "album", listOf(artist))
    val track = Track("t", "name", listOf(artist), album)
    val pt = PlaylistTrack(track)
    assertEquals(track, pt.track)
  }

  @Test
  fun authTokenStoresValues() {
    val token = AuthToken("a", "type", "scope", 1, "r", "cid")
    assertEquals("a", token.access_token)
  }

  @Test
  fun songStoresValues() {
    val song = Song("artist", "title")
    assertEquals("title", song.title)
  }

  @Test
  fun bandPlaylistRequestStoresValues() {
    val request = BandPlaylistRequest(listOf("Band A", "Band B"))
    assertEquals(listOf("Band A", "Band B"), request.bands)
  }

  @Test
  fun allDomainClassesInstantiate() {
    val artist = Artist("1", "a")
    val album = Album("2", "al", listOf(artist))
    val track = Track("3", "t", listOf(artist), album)
    val playlist = Playlist("p", "name")
    val playlistTrack = PlaylistTrack(track)
    val playlistTracks = PlaylistTracks(listOf(playlistTrack), null)
    val playlists = Playlists(listOf(playlist), null)
    val tracks = Tracks(listOf(track))
    val srInt = SearchResultInternal(listOf(track))
    val sr = SearchResult(srInt)
    val artists = Artists(listOf(artist), null)
    val artistSearch = ArtistSearchResult(artists)
    val topTracks = ArtistTopTracks(listOf(track))
    val user = User("u")
    val login = LastFmLogin("cid", "l")
    assertEquals("u", user.id)
    assertEquals("l", login.lastFmLogin)
    assertEquals(listOf(track), tracks.items)
    assertEquals(srInt, sr.tracks)
    assertEquals(artists, artistSearch.artists)
    assertEquals(listOf(track), topTracks.tracks)
    assertEquals(playlist, playlists.items.first())
    assertEquals(playlistTrack, playlistTracks.items.first())
  }
}
