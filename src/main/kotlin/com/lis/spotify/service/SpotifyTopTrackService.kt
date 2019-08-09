package com.lis.spotify.service

import com.lis.spotify.domain.Track
import com.lis.spotify.domain.Tracks
import org.springframework.stereotype.Service


@Service
class SpotifyTopTrackService(var spotifyRestService: SpotifyRestService) {
    companion object {
        private val URL = "https://api.spotify.com/v1/me/top/tracks?limit={limit}&time_range={time_range}"
        private val SHORT_TERM = "short_term"
        private val MID_TERM = "medium_term"
        private val LONG_TERM = "long_term"
    }


    private suspend fun getTopTracks(term: String, clientId: String): Tracks {
        return spotifyRestService.doGet<Tracks>(URL, params = mapOf("limit" to 50, "time_range" to term), clientId = clientId)
    }

    suspend fun getTopTracksLongTerm(clientId: String): List<Track> {
        return getTopTracks(LONG_TERM, clientId).items
    }


    suspend fun getTopTracksMidTerm(clientId: String): List<Track> {
        return getTopTracks(MID_TERM, clientId).items
    }


    suspend fun getTopTracksShortTerm(clientId: String): List<Track> {
        return getTopTracks(SHORT_TERM, clientId).items
    }
}


