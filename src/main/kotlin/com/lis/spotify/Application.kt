package com.lis.spotify

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.socket.config.annotation.EnableWebSocket

@SpringBootApplication
@EnableWebSocket
class Application {
    private val log = LoggerFactory.getLogger(Application::class.java)
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
