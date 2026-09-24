package com.lis.spotify.service

import com.lis.spotify.domain.Album
import com.lis.spotify.domain.Artist
import com.lis.spotify.domain.ArtistSearchResult
import com.lis.spotify.domain.Artists
import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.SearchResultInternal
import com.lis.spotify.domain.Track
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SpotifyArtistServiceTest {
  @Test
  fun serviceInstantiates() {
    val service = SpotifyArtistService(mockk(relaxed = true))
    assertNotNull(service)
  }

  @Test
  fun searchArtistReturnsFirstMatch() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyArtistService(rest)
    val artist = Artist("1", "Band")
    every { rest.doRequest(any<() -> Any>()) } returns
      ArtistSearchResult(Artists(listOf(artist), null))

    val result = service.searchArtist("Band", "cid")
    assertEquals(artist, result)
  }

  @Test
  fun getArtistTopTracksReturnsTracks() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyArtistService(rest)
    val track = Track("t", "name", listOf(Artist("a", "band")), Album("b", "album", emptyList()))
    every { rest.doRequest(any<() -> Any>()) } returnsMany
      listOf(Artist("a", "band"), SearchResult(SearchResultInternal(listOf(track))))

    val result = service.getArtistTopTracks("a", "cid")
    assertEquals(listOf(track), result)
  }

  @Test
  fun catalogSearchContinuesAfterFullPageOfUnrelatedArtists() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyArtistService(rest)
    val unrelated = (1..10).map { catalogTrack("other-$it", "other") }
    val wanted = catalogTrack("wanted", "band")
    every { rest.doRequest(any<() -> Any>()) } returnsMany
      listOf(
        Artist("band", "Band"),
        SearchResult(SearchResultInternal(unrelated)),
        SearchResult(SearchResultInternal(listOf(wanted, wanted))),
      )

    assertEquals(listOf("wanted"), service.getArtistTopTracks("band", "cid").map { it.id })
    io.mockk.verify(exactly = 3) { rest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun catalogSearchStopsAfterTenMatchingTracks() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyArtistService(rest)
    val wanted = (1..10).map { catalogTrack("wanted-$it", "band") }
    every { rest.doRequest(any<() -> Any>()) } returnsMany
      listOf(Artist("band", "Band"), SearchResult(SearchResultInternal(wanted)))

    assertEquals(wanted, service.getArtistTopTracks("band", "cid"))
    io.mockk.verify(exactly = 2) { rest.doRequest(any<() -> Any>()) }
  }

  @Test
  fun catalogSearchHasABoundedPageCountForAmbiguousArtistNames() {
    val rest = mockk<SpotifyRestService>()
    val service = SpotifyArtistService(rest)
    val unrelated =
      SearchResult(SearchResultInternal((1..10).map { catalogTrack("other-$it", "other") }))
    every { rest.doRequest(any<() -> Any>()) } returnsMany
      listOf(Artist("band", "Band"), unrelated, unrelated, unrelated)

    assertEquals(emptyList<Track>(), service.getArtistTopTracks("band", "cid"))
    io.mockk.verify(exactly = 4) { rest.doRequest(any<() -> Any>()) }
  }

  private fun catalogTrack(id: String, artistId: String) =
    Track(id, "Song", listOf(Artist(artistId, "Band")), Album("album", "Album", emptyList()))
}
