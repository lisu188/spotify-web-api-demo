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
}
