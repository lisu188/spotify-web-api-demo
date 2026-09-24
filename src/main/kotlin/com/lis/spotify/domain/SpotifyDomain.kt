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

package com.lis.spotify.domain

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

@JsonDeserialize(using = PlaylistTrackDeserializer::class)
class PlaylistTrack(var track: Track? = null)

/** Playlist pages may contain removed tracks, local files, or non-track items. */
class PlaylistTrackDeserializer : StdDeserializer<PlaylistTrack>(PlaylistTrack::class.java) {
  override fun deserialize(parser: JsonParser, context: DeserializationContext): PlaylistTrack {
    val row = parser.codec.readTree<JsonNode>(parser)
    val item = row.get("item") ?: row.get("track")
    if (
      item == null ||
        !item.isObject ||
        item.path("type").asText("track") != "track" ||
        row.path("is_local").asBoolean(false) ||
        item.path("is_local").asBoolean(false) ||
        item.path("id").asText("").isBlank()
    ) {
      return PlaylistTrack()
    }
    return PlaylistTrack(parser.codec.treeToValue(item, Track::class.java))
  }
}

class PlaylistTracks(var items: List<PlaylistTrack>, var next: String?)

class SearchResult(var tracks: SearchResultInternal)

class SearchResultInternal(var items: List<Track>)

class Tracks(var items: List<Track>)

class Track(var id: String, var name: String, var artists: List<Artist>, var album: Album)

class Artist(var id: String, var name: String)

class Album(var id: String, var name: String, var artists: List<Artist>)

class User(var id: String)

class Playlist(var id: String, var name: String)

class Playlists(var items: List<Playlist>, var next: String?)

class Artists(var items: List<Artist>, var next: String?)

class ArtistSearchResult(var artists: Artists)

class ArtistTopTracks(var tracks: List<Track>)

class LastFmLogin(val spotifyClientId: String, val lastFmLogin: String)
