package com.lis.spotify.domain

class PlaylistTrack(var track: Track)

class PlaylistTracks(var items: List<PlaylistTrack>,
                     var next: String?)

class SearchResult(var tracks: SearchResultInternal)

class SearchResultInternal(var items: List<Track>)


class Tracks(var items: List<Track>)

class Track(var id: String,
            var name: String,
            var artists: List<Artist>,
            var album: Album)

class Artist(var id: String,
             var name: String)

class Album(var id: String,
            var name: String,
            var artists: List<Artist>)

class User(var id: String)

class Playlist(var id: String,
               var name: String)

class Playlists(var items: List<Playlist>,
                var next: String?)