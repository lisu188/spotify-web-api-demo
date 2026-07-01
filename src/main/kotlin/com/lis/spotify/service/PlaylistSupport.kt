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
import com.lis.spotify.domain.Song
import com.lis.spotify.domain.Track
import java.util.concurrent.ConcurrentHashMap

internal const val SECONDS_PER_DAY = 86_400L

internal fun Track.toSong(): Song {
  return Song(artist = artists.firstOrNull()?.name.orEmpty(), title = name)
}

internal fun String.normalizeToken(): String {
  return trim().lowercase().replace("\\s+".toRegex(), " ")
}

internal fun Song.normalizedKey(): Pair<String, String> {
  return artist.normalizeToken() to title.normalizeToken()
}

/**
 * Resolves the Spotify playlist for a given name, creating it if necessary. Creation is serialized
 * per `clientId|playlistName` so concurrent jobs cannot create duplicate playlists, and results are
 * memoized in the caller-supplied [existingPlaylists] cache.
 */
internal class PlaylistProvisioner(private val spotifyPlaylistService: SpotifyPlaylistService) {
  private val creationLocks = ConcurrentHashMap<String, Any>()

  fun getOrCreate(
    playlistName: String,
    clientId: String,
    existingPlaylists: ConcurrentHashMap<String, Playlist>,
    public: Boolean = true,
  ): Playlist {
    val lock = creationLocks.computeIfAbsent("$clientId|$playlistName") { Any() }
    synchronized(lock) {
      val existing = existingPlaylists[playlistName]
      if (existing != null) {
        return existing
      }

      val remoteExisting =
        spotifyPlaylistService.getCurrentUserPlaylists(clientId).firstOrNull {
          it.name == playlistName
        }
      if (remoteExisting != null) {
        existingPlaylists.putIfAbsent(playlistName, remoteExisting)
        return remoteExisting
      }

      val created = spotifyPlaylistService.createPlaylist(playlistName, clientId, public)
      val previous = existingPlaylists.putIfAbsent(playlistName, created)
      return previous ?: created
    }
  }
}
