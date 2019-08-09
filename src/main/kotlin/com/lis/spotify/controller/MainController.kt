package com.lis.spotify.controller

import com.lis.spotify.service.SpotifyAuthenticationService
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping

@Controller
class MainController(val spotifyAuthenticationService: SpotifyAuthenticationService) {
    @GetMapping("/")
    fun main(@CookieValue("clientId", defaultValue = "") clientId: String): String {
        if (clientId.isNotEmpty() && spotifyAuthenticationService.getAuthToken(clientId) != null) {
            spotifyAuthenticationService.refreshToken(clientId)
            return "forward:/index.html"
        } else {
            return "redirect:/authorize"
        }
    }

}