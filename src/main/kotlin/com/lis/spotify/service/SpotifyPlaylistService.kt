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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyPlaylistService(var spotifyRestService: SpotifyRestService) {

  fun getCurrentUserPlaylists(clientId: String): MutableList<Playlist> {
    logger.debug("getCurrentUserPlaylists {}", clientId)
    logger.info("getCurrentUserPlaylists: {}", clientId)

    val playlistList: MutableList<Playlist> = ArrayList()
    var url: String = USER_PLAYLISTS_URL
    do {
      val playlists: Playlists = spotifyRestService.doGet<Playlists>(url, clientId = clientId)
      playlists.items?.let { playlist: List<Playlist> -> playlist.forEach { playlistList.add(it) } }

      url = playlists.next.orEmpty()
    } while (!playlists.next.isNullOrEmpty())
    logger.debug("getCurrentUserPlaylists {} -> {} playlists", clientId, playlistList.size)
    return playlistList
  }

  fun getPlaylistTracks(id: String, clientId: String): List<Track>? {
    logger.debug("getPlaylistTracks {} {}", id, clientId)
    logger.info("getPlaylistTracks: {} {}", id, clientId)

    val trackList: MutableList<Track> = ArrayList()
    var url: String = PLAYLIST_TRACKS_URL
    do {
      val tracks: PlaylistTracks =
        spotifyRestService.doGet<PlaylistTracks>(url, mapOf("id" to id), clientId = clientId)
      tracks.items.let { it.forEach { trackList.add(it.track) } }

      url = tracks.next.orEmpty()
    } while (!tracks.next.isNullOrEmpty())
    logger.debug("getPlaylistTracks {} {} -> {} tracks", id, clientId, trackList.size)
    return trackList
  }

  fun getPlaylistTrackIds(id: String, clientId: String): List<String>? {
    logger.debug("getPlaylistTrackIds {} {}", id, clientId)
    return getPlaylistTracks(id, clientId = clientId)?.map { it.id }
  }

  fun deleteTracksFromPlaylist(playlistId: String, tracks: List<String>, clientId: String) {
    logger.debug("deleteTracksFromPlaylist {} {} {}", playlistId, clientId, tracks.size)
    logger.info("deleteTracksFromPlaylist: {} {} {}", playlistId, clientId, tracks)

    tracks.chunked(100).map {
      spotifyRestService.doDelete<Any>(
        PLAYLIST_TRACKS_URL,
        body = mapOf("tracks" to it.map { mapOf("uri" to "spotify:track:$it") }),
        params = mapOf("id" to playlistId),
        clientId = clientId,
      )
    }
    logger.debug("deleteTracksFromPlaylist {} {} -> removed {}", playlistId, clientId, tracks.size)
  }

  fun addTracksToPlaylist(playlistId: String, tracks: List<String>, clientId: String) {
    logger.debug("addTracksToPlaylist {} {} {}", playlistId, clientId, tracks.size)
    logger.info("addTracksToPlaylist: {} {} {}", playlistId, clientId, tracks)

    tracks.chunked(100).map {
      spotifyRestService.doPost<Any>(
        PLAYLIST_TRACKS_URL,
        body = mapOf("uris" to it.map { "spotify:track:$it" }),
        params = mapOf("id" to playlistId),
        clientId = clientId,
      )
    }
    logger.debug("addTracksToPlaylist {} {} -> added {}", playlistId, clientId, tracks.size)
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

  fun modifyPlaylist(
    id: String,
    trackList: List<String>,
    clientId: String,
  ): Map<String, List<String>> {
    logger.debug("modifyPlaylist {} {} {}", id, clientId, trackList.size)
    logger.info("modifyPlaylist: {} {} {}", id, clientId, trackList.size)

    if (trackList.isNotEmpty()) {
      var old = getPlaylistTrackIds(id, clientId).orEmpty()
      val new = trackList

      val tracksToRemove = getDiff(old, new)

      deleteTracksFromPlaylist(id, tracksToRemove, clientId)

      old = getPlaylistTrackIds(id, clientId).orEmpty()

      val tracksToAdd = getDiff(new, old)
      addTracksToPlaylist(id, tracksToAdd, clientId)

      val result = mapOf("added" to tracksToAdd, "removed" to tracksToRemove)
      logger.debug(
        "modifyPlaylist {} {} -> added {} removed {}",
        id,
        clientId,
        tracksToAdd.size,
        tracksToRemove.size,
      )
      return result
    }

    return mapOf("added" to emptyList(), "removed" to emptyList())
  }

  fun createPlaylist(name: String, clientId: String): Playlist {
    logger.debug("createPlaylist {} {}", name, clientId)
    logger.info("createPlaylist: {} {}", name, clientId)

    return spotifyRestService.doPost<Playlist>(
      USER_PLAYLISTS_URL,
      body = mapOf("name" to name),
      clientId = clientId,
    )
  }

  fun getOrCreatePlaylist(playlistName: String, clientId: String): Playlist {
    logger.debug("getOrCreatePlaylist {} {}", playlistName, clientId)
    logger.info("getOrCreatePlaylist: {} {}", playlistName, clientId)

    val findAny =
      getCurrentUserPlaylists(clientId).stream().filter { it.name == playlistName }.findAny()
    if (findAny.isPresent) {
      return findAny.get()
    }
    return createPlaylist(playlistName, clientId)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(SpotifyPlaylistService::class.java)
    private const val PLAYLIST_TRACKS_URL = "https://api.spotify.com/v1/playlists/{id}/tracks"
    private const val USER_PLAYLISTS_URL = "https://api.spotify.com/v1/me/playlists"
  }
}
