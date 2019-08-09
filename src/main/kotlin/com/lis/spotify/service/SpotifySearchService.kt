package com.lis.spotify.service

import com.lis.spotify.domain.SearchResult
import com.lis.spotify.domain.Song
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

@Service
class SpotifySearchService(val spotifyRestService: SpotifyRestService) {
    companion object {
        val SEARCH_URL = "https://api.spotify.com/v1/search?q={q}&type={type}"
    }

    suspend fun doSearch(song: Song, clientId: String): SearchResult? {
        return spotifyRestService.doGet(SEARCH_URL, params = mapOf("q" to "track:${song.title} artist:${song.artist}", "type" to "track"), clientId = clientId)
    }

    suspend fun doSearch(values: List<Song>, clientId: String, progress: () -> Unit = {}): List<String> {
        LoggerFactory.getLogger(javaClass).info("doSearch: {} {}", clientId, values.size)
        lateinit var retVal: List<String>;
        val time = measureTimeMillis {
            retVal = values
                    .map {
                        GlobalScope.async {
                            val doSearch = doSearch(it, clientId)
                            progress()
                            doSearch
                        }
                    }
                    .mapNotNull { it.await() }
                    .map { it.tracks.items }
                    .mapNotNull { it.stream().findFirst().orElse(null) }
                    .map { it.id }
                    .distinct()
        }
        LoggerFactory.getLogger(javaClass).info("doSearch: {} {} took: {}", clientId, values.size, time)
        return retVal
    }

}