package com.lis.spotify.controller

import com.lis.spotify.service.LastFmService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class LastFmController(val lastFmService: LastFmService) {
    @PostMapping("/verifyLastFmId/{lastFmLogin}")
    fun verifyLastFmId(@PathVariable("lastFmLogin") lastFmLogin: String): Boolean {
        return lastFmService.globalChartlist(lastFmLogin).size > 0
    }
}