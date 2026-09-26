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
import com.lis.spotify.domain.PlaylistTrack
import com.lis.spotify.domain.PlaylistTracks
import com.lis.spotify.domain.Playlists
import com.lis.spotify.domain.Track
import com.lis.spotify.logging.asSafeClientIdForLogs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyPlaylistService(var spotifyRestService: SpotifyRestService) {

  private fun isUri(id: String) = id.startsWith("spotify:track:")

  private fun trackUri(value: String): String {
    require(
      value.removePrefix("spotify:track:").isNotBlank() &&
        (!value.startsWith("spotify:") || isUri(value))
    ) {
      "Invalid track URI: $value"
    }
    return if (isUri(value)) value else "spotify:track:$value"
  }

  fun getCurrentUserPlaylists(clientId: String): MutableList<Playlist> {
    logger.debug("getCurrentUserPlaylists {}", clientId.asSafeClientIdForLogs())
    logger.info("getCurrentUserPlaylists: {}", clientId.asSafeClientIdForLogs())

    val playlistList: MutableList<Playlist> = ArrayList()
    var url: String = USER_PLAYLISTS_URL
    do {
      val playlists: Playlists = spotifyRestService.doGet<Playlists>(url, clientId = clientId)
      playlists.items?.let { playlist: List<Playlist> -> playlist.forEach { playlistList.add(it) } }

      url = playlists.next.orEmpty()
    } while (!playlists.next.isNullOrEmpty())
    logger.debug(
      "getCurrentUserPlaylists {} -> {} playlists",
      clientId.asSafeClientIdForLogs(),
      playlistList.size,
    )
    return playlistList
  }

  fun getPlaylistTracks(id: String, clientId: String): List<Track>? {
    logger.debug("getPlaylistTracks {} {}", id, clientId.asSafeClientIdForLogs())
    logger.info("getPlaylistTracks: {} {}", id, clientId.asSafeClientIdForLogs())

    val trackList = getPlaylistItems(id, clientId).mapNotNull { it.track }
    logger.debug(
      "getPlaylistTracks {} {} -> {} tracks",
      id,
      clientId.asSafeClientIdForLogs(),
      trackList.size,
    )
    return trackList
  }

  internal fun getPlaylistItems(id: String, clientId: String): List<PlaylistTrack> {
    val items = mutableListOf<PlaylistTrack>()
    var url = PLAYLIST_TRACKS_URL
    do {
      val page =
        spotifyRestService.doGet<PlaylistTracks>(url, mapOf("id" to id), clientId = clientId)
      items.addAll(page.items)
      url = page.next.orEmpty()
    } while (url.isNotEmpty())
    return items
  }

  fun getPlaylistTrackIds(id: String, clientId: String): List<String>? {
    logger.debug("getPlaylistTrackIds {} {}", id, clientId.asSafeClientIdForLogs())
    return getPlaylistTracks(id, clientId = clientId)?.map { it.id }
  }

  fun deleteTracksFromPlaylist(
    playlistId: String,
    tracks: List<String>,
    clientId: String,
    beforeMutation: () -> Unit = NO_MUTATION_CHECK,
  ) {
    require(tracks.isNotEmpty()) { "Track list must not be empty" }
    tracks.forEach { trackUri(it) }

    logger.debug(
      "deleteTracksFromPlaylist {} {} {}",
      playlistId,
      clientId.asSafeClientIdForLogs(),
      tracks.size,
    )
    logger.info(
      "deleteTracksFromPlaylist: {} {} {}",
      playlistId,
      clientId.asSafeClientIdForLogs(),
      tracks,
    )

    tracks.chunked(100).map { chunk ->
      val payload = mapOf("items" to chunk.map { mapOf("uri" to trackUri(it)) })
      logger.debug("delete payload {}", payload)
      spotifyRestService.doDelete<Any>(
        PLAYLIST_TRACKS_URL,
        body = payload,
        params = mapOf("id" to playlistId),
        clientId = clientId,
        beforeAttempt = beforeMutation,
      )
    }
    logger.debug(
      "deleteTracksFromPlaylist {} {} -> removed {}",
      playlistId,
      clientId.asSafeClientIdForLogs(),
      tracks.size,
    )
  }

  fun addTracksToPlaylist(
    playlistId: String,
    tracks: List<String>,
    clientId: String,
    beforeMutation: () -> Unit = NO_MUTATION_CHECK,
  ) {
    val uris = tracks.map(::trackUri)
    logger.debug(
      "addTracksToPlaylist {} {} {}",
      playlistId,
      clientId.asSafeClientIdForLogs(),
      tracks.size,
    )
    logger.info(
      "addTracksToPlaylist: {} {} {}",
      playlistId,
      clientId.asSafeClientIdForLogs(),
      tracks,
    )

    uris.chunked(100).map {
      spotifyRestService.doPost<Any>(
        PLAYLIST_TRACKS_URL,
        body = mapOf("uris" to it),
        params = mapOf("id" to playlistId),
        clientId = clientId,
        beforeAttempt = beforeMutation,
      )
    }
    logger.debug(
      "addTracksToPlaylist {} {} -> added {}",
      playlistId,
      clientId.asSafeClientIdForLogs(),
      tracks.size,
    )
  }

  private fun getDiff(old: List<String>, new: List<String>): ArrayList<String> {
    return ArrayList(old.toSet() - new.toSet())
  }

  fun replacePlaylistTracks(
    id: String,
    trackList: List<String>,
    clientId: String,
    beforeMutation: () -> Unit = NO_MUTATION_CHECK,
  ) {
    require(trackList.size <= 100) { "Spotify can atomically replace at most 100 playlist items" }
    val uris = trackList.map(::trackUri)
    logger.debug(
      "replacePlaylistTracks {} {} {}",
      id,
      clientId.asSafeClientIdForLogs(),
      trackList.size,
    )
    logger.info(
      "replacePlaylistTracks: {} {} {}",
      id,
      clientId.asSafeClientIdForLogs(),
      trackList.size,
    )

    spotifyRestService.doPut<Any>(
      PLAYLIST_TRACKS_URL,
      body = mapOf("uris" to uris),
      params = mapOf("id" to id),
      clientId = clientId,
      beforeAttempt = beforeMutation,
    )
    logger.debug(
      "replacePlaylistTracks {} {} -> replaced {}",
      id,
      clientId.asSafeClientIdForLogs(),
      trackList.size,
    )
  }

  fun deduplicatePlaylist(
    id: String,
    clientId: String,
    beforeMutation: () -> Unit = NO_MUTATION_CHECK,
  ) {
    val items = getPlaylistItems(id, clientId)
    val tracks = items.mapNotNull { it.track?.id }
    val distinct = tracks.distinct()
    if (tracks.size == distinct.size) return

    // Replacing then appending loses the tail if an append fails. Only a single atomic
    // replacement is safe, and it must never discard episodes, local, or unavailable items.
    check(distinct.size <= 100 && items.size == tracks.size) {
      "Automatic deduplication needs a playlist with at most 100 unique Spotify tracks " +
        "and no local, unavailable, or non-track items. The playlist was left unchanged."
    }
    replacePlaylistTracks(id, distinct, clientId, beforeMutation)
  }

  fun modifyPlaylist(
    id: String,
    trackList: List<String>,
    clientId: String,
    beforeMutation: () -> Unit = NO_MUTATION_CHECK,
  ): Map<String, List<String>> {
    logger.debug("modifyPlaylist {} {} {}", id, clientId.asSafeClientIdForLogs(), trackList.size)
    logger.info("modifyPlaylist: {} {} {}", id, clientId.asSafeClientIdForLogs(), trackList.size)

    val old = getPlaylistTrackIds(id, clientId).orEmpty()
    val oldSet = old.toSet()
    val newSet = trackList.map { trackUri(it).removePrefix("spotify:track:") }.toSet()

    val tracksToRemove = (oldSet - newSet).toList()
    val tracksToAdd = (newSet - oldSet).toList()

    // Preserve every existing track until all additions have succeeded. A retry then
    // reconciles against the current playlist, including any successfully added chunks.
    if (tracksToAdd.isNotEmpty()) {
      addTracksToPlaylist(id, tracksToAdd, clientId, beforeMutation)
    }
    if (tracksToRemove.isNotEmpty()) {
      deleteTracksFromPlaylist(id, tracksToRemove, clientId, beforeMutation)
    }

    val result = mapOf("added" to tracksToAdd, "removed" to tracksToRemove)
    logger.debug(
      "modifyPlaylist {} {} -> added {} removed {}",
      id,
      clientId.asSafeClientIdForLogs(),
      tracksToAdd.size,
      tracksToRemove.size,
    )
    return result
  }

  fun createPlaylist(
    name: String,
    clientId: String,
    public: Boolean = true,
    beforeMutation: () -> Unit = NO_MUTATION_CHECK,
  ): Playlist {
    logger.debug("createPlaylist {} {} public={}", name, clientId.asSafeClientIdForLogs(), public)
    logger.info("createPlaylist: {} {} public={}", name, clientId.asSafeClientIdForLogs(), public)

    return spotifyRestService.doPost<Playlist>(
      USER_PLAYLISTS_URL,
      body = mapOf("name" to name, "public" to public),
      clientId = clientId,
      beforeAttempt = beforeMutation,
    )
  }

  fun getOrCreatePlaylist(
    playlistName: String,
    clientId: String,
    public: Boolean = true,
  ): Playlist {
    logger.debug(
      "getOrCreatePlaylist {} {} public={}",
      playlistName,
      clientId.asSafeClientIdForLogs(),
      public,
    )
    logger.info(
      "getOrCreatePlaylist: {} {} public={}",
      playlistName,
      clientId.asSafeClientIdForLogs(),
      public,
    )

    return synchronized(PlaylistCreationLocks.forPlaylist(clientId, playlistName)) {
      getCurrentUserPlaylists(clientId).firstOrNull { it.name == playlistName }
        ?: createPlaylist(playlistName, clientId, public)
    }
  }

  fun hasRequiredScopes(clientId: String, requiredScopes: Set<String>): Boolean {
    val grantedScopes =
      spotifyRestService.spotifyAuthenticationService
        .getAuthToken(clientId)
        ?.scope
        .orEmpty()
        .split(" ")
        .filter { it.isNotBlank() }
        .toSet()
    return requiredScopes.all { it in grantedScopes }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(SpotifyPlaylistService::class.java)
    private const val PLAYLIST_TRACKS_URL = "https://api.spotify.com/v1/playlists/{id}/items"
    private const val USER_PLAYLISTS_URL = "https://api.spotify.com/v1/me/playlists"
  }
}
