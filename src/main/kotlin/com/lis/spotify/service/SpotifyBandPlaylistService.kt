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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SpotifyBandPlaylistService(
  private val spotifyArtistService: SpotifyArtistService,
  private val spotifyPlaylistService: SpotifyPlaylistService,
) {
  private val logger = LoggerFactory.getLogger(SpotifyBandPlaylistService::class.java)

  fun createBandPlaylist(clientId: String, bandNames: List<String>): String? {
    val cleaned =
      bandNames.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    if (cleaned.isEmpty()) {
      logger.warn("createBandPlaylist called with no band names for {}", clientId)
      return null
    }

    logger.info("Creating band playlist for {} bands (clientId={})", cleaned.size, clientId)

    val trackIds =
      runBlocking(Dispatchers.IO) {
        val tracksByBand =
          cleaned
            .map { band ->
              async(Dispatchers.IO) {
                val artist = spotifyArtistService.searchArtist(band, clientId)
                if (artist == null) {
                  logger.warn("No Spotify artist match for '{}' (clientId={})", band, clientId)
                  emptyList()
                } else {
                  spotifyArtistService.getArtistTopTracks(artist.id, clientId).map { it.id }
                }
              }
            }
            .awaitAll()
        buildBalancedMix(tracksByBand)
      }

    if (trackIds.isEmpty()) {
      logger.warn("No tracks found for bands {} (clientId={})", cleaned, clientId)
      return null
    }

    val playlist = spotifyPlaylistService.getOrCreatePlaylist(buildPlaylistName(cleaned), clientId)
    spotifyPlaylistService.modifyPlaylist(playlist.id, trackIds, clientId)
    logger.info("Band playlist ready {} -> {} tracks", playlist.id, trackIds.size)
    return playlist.id
  }

  private fun buildBalancedMix(tracksByBand: List<List<String>>): List<String> {
    val result = LinkedHashSet<String>()
    var trackIndex = 0

    while (result.size < targetTrackCount) {
      var hadCandidate = false
      for (tracks in tracksByBand) {
        if (trackIndex < tracks.size) {
          hadCandidate = true
          result.add(tracks[trackIndex])
          if (result.size >= targetTrackCount) {
            break
          }
        }
      }
      if (!hadCandidate) {
        break
      }
      trackIndex += 1
    }

    return result.toList()
  }

  private fun buildPlaylistName(bands: List<String>): String {
    val joined = bands.joinToString(", ")
    val name = "Band Mix: $joined"
    return if (name.length <= maxPlaylistNameLength) {
      name
    } else {
      if (bands.size > 2) {
        "Band Mix: ${bands.take(2).joinToString(", ")} +${bands.size - 2} more"
      } else {
        "Band Mix: ${bands.take(1).joinToString(", ")}"
      }
    }
  }

  companion object {
    private const val targetTrackCount = 50
    private const val maxPlaylistNameLength = 100
  }
}
