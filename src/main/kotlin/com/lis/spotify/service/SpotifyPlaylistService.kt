/*
 * MIT License
 *
 * Copyright (c) 2019 Andrzej Lis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.lis.spotify.service

import com.lis.spotify.domain.Playlist
import com.lis.spotify.domain.PlaylistTracks
import com.lis.spotify.domain.Playlists
import com.lis.spotify.domain.Track
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyPlaylistService(var spotifyRestService: SpotifyRestService) {

  companion object {
    private const val PLAYLIST_TRACKS_URL = "https://api.spotify.com/v1/playlists/{id}/tracks"
    private const val USER_PLAYLISTS_URL = "https://api.spotify.com/v1/me/playlists"
  }

  suspend fun getCurrentUserPlaylists(clientId: String): MutableList<Playlist> {
    LoggerFactory.getLogger(javaClass).info("getCurrentUserPlaylists: {}", clientId)

    val playlistList: MutableList<Playlist> = ArrayList()
    var url: String = USER_PLAYLISTS_URL
    do {
      val playlists: Playlists? = spotifyRestService.doGet<Playlists>(url, clientId = clientId)
      playlists?.items?.let { playlist: List<Playlist> ->
        playlist.forEach { playlistList.add(it) }
      }

      url = playlists?.next.orEmpty()
    } while (!playlists?.next.isNullOrEmpty())

    return playlistList
  }

  suspend fun getPlaylistTracks(id: String, clientId: String): List<Track>? {
    LoggerFactory.getLogger(javaClass).info("getPlaylistTracks: {} {}", id, clientId)

    val trackList: MutableList<Track> = ArrayList()
    var url: String = PLAYLIST_TRACKS_URL
    do {
      val tracks: PlaylistTracks? =
        spotifyRestService.doGet<PlaylistTracks>(url, mapOf("id" to id), clientId = clientId)
      tracks?.items?.let { it.forEach { trackList.add(it.track) } }

      url = tracks?.next.orEmpty()
    } while (!tracks?.next.isNullOrEmpty())
    return trackList
  }

  suspend fun getPlaylistTrackIds(id: String, clientId: String): List<String>? {
    return getPlaylistTracks(id, clientId = clientId)?.map { it.id }
  }

  suspend fun deleteTracksFromPlaylist(playlistId: String, tracks: List<String>, clientId: String) {
    LoggerFactory.getLogger(javaClass)
      .info("deleteTracksFromPlaylist: {} {} {}", playlistId, clientId, tracks)

    tracks
      .chunked(100)
      .map {
        GlobalScope.async {
          spotifyRestService.doDelete<Any>(
            PLAYLIST_TRACKS_URL,
            body = mapOf("tracks" to it.map { mapOf("uri" to "spotify:track:$it") }),
            params = mapOf("id" to playlistId),
            clientId = clientId,
          )
        }
      }
      .map { it.await() }
  }

  suspend fun addTracksToPlaylist(playlistId: String, tracks: List<String>, clientId: String) {
    LoggerFactory.getLogger(javaClass)
      .info("addTracksToPlaylist: {} {} {}", playlistId, clientId, tracks)

    tracks
      .chunked(100)
      .map {
        GlobalScope.async {
          spotifyRestService.doPost<Any>(
            PLAYLIST_TRACKS_URL,
            body = mapOf("uris" to it.map { "spotify:track:$it" }),
            params = mapOf("id" to playlistId),
            clientId = clientId,
          )
        }
      }
      .map { it.await() }
  }

  private fun getDiff(old: List<String>, new: List<String>): ArrayList<String> {
    val ret = ArrayList<String>()
    old.forEach {
      if (it in new) {} else {

        ret += it
      }
    }
    return ret
  }

  suspend fun modifyPlaylist(
    id: String,
    trackList: List<String>,
    clientId: String,
  ): Map<String, List<String>> {
    LoggerFactory.getLogger(javaClass)
      .info("modifyPlaylist: {} {} {}", id, clientId, trackList.size)

    if (trackList.isNotEmpty()) {
      var old = getPlaylistTrackIds(id, clientId).orEmpty()
      val new = trackList

      val tracksToRemove = getDiff(old, new)

      deleteTracksFromPlaylist(id, tracksToRemove, clientId)

      old = getPlaylistTrackIds(id, clientId).orEmpty()

      val tracksToAdd = getDiff(new, old)
      addTracksToPlaylist(id, tracksToAdd, clientId)

      return mapOf("added" to tracksToAdd, "removed" to tracksToRemove)
    }

    return mapOf("added" to emptyList(), "removed" to emptyList())
  }

  suspend fun createPlaylist(name: String, clientId: String): Playlist {
    LoggerFactory.getLogger(javaClass).info("createPlaylist: {} {}", name, clientId)

    return spotifyRestService.doPost<Playlist>(
      USER_PLAYLISTS_URL,
      body = mapOf("name" to name),
      clientId = clientId,
    )
  }

  suspend fun getOrCreatePlaylist(playlistName: String, clientId: String): Playlist {
    LoggerFactory.getLogger(javaClass).info("getOrCreatePlaylist: {} {}", playlistName, clientId)

    val findAny =
      getCurrentUserPlaylists(clientId).stream().filter { it.name == playlistName }.findAny()
    if (findAny.isPresent) {
      return findAny.get()
    }
    return createPlaylist(playlistName, clientId)
  }
}
