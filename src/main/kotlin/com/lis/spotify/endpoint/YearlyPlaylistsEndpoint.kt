package com.lis.spotify.endpoint


import com.lis.spotify.config.WebsocketSpringConfigurator
import com.lis.spotify.service.SpotifyTopPlaylistsService
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay


import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import javax.websocket.*
import javax.websocket.server.PathParam
import javax.websocket.server.ServerEndpoint

@Component
@ServerEndpoint("/socket/{login}"
        , configurator = WebsocketSpringConfigurator::class
)
class YearlyPlaylistsEndpoint(var spotifyTopPlaylistsService: SpotifyTopPlaylistsService) {

    var progressMap = mutableMapOf<Int, Int>()

    @OnOpen
    fun onOpen(session: Session, config: EndpointConfig, @PathParam("login") login: String) {
        GlobalScope.launch {
            val launch = launch {
                while (true) {
                    delay(1000)
                    reportProgress(session)
                }
            }
            spotifyTopPlaylistsService.updateYearlyPlaylists(login, config.userProperties["clientId"] as String) {
                updateProgress(it)
            }
            launch.cancel()
            launch.join()
            reportProgress(session)
        }
    }

    private fun updateProgress(it: Pair<Int, Int>) {
        synchronized(this@YearlyPlaylistsEndpoint) {
            progressMap[it.first] = it.second
        }
    }

    private fun reportProgress(session: Session) {
        synchronized(this@YearlyPlaylistsEndpoint) {
            session.basicRemote.sendText((progressMap.values.sum() / progressMap.size).toString())
        }
    }

    @OnMessage
    fun onMessage(session: Session, payload: String) {
    }

    @OnClose
    fun onClose(session: Session) {
    }

    @OnError
    fun onError(session: Session, throwable: Throwable) {

    }
}