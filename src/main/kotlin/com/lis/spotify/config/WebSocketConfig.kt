package com.lis.spotify.config

import org.springframework.beans.factory.BeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.server.standard.ServerEndpointExporter
import java.lang.reflect.Field
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.websocket.HandshakeResponse
import javax.websocket.server.HandshakeRequest
import javax.websocket.server.ServerEndpointConfig


@Configuration
class WebSocketConfig {
    @Bean
    fun serverEndpointExporter(): ServerEndpointExporter {
        return ServerEndpointExporter()
    }
}

@Component
class WebsocketSpringConfigurator : ServerEndpointConfig.Configurator(), ApplicationContextAware {
    companion object {
        private var context: BeanFactory? = null
    }

    override fun modifyHandshake(config: ServerEndpointConfig,
                                 request: HandshakeRequest,
                                 response: HandshakeResponse) {
        //TODO: handle nulls
        config.userProperties["clientId"] = getRequest(request).cookies.findLast { cookie: Cookie? -> cookie?.name == "clientId" }?.value
    }

    private fun getRequest(request: HandshakeRequest): HttpServletRequest {
        val field: Field = request.javaClass.getDeclaredField("request")
        field.isAccessible = true
        return field.get(request) as HttpServletRequest
    }

    override fun <T> getEndpointInstance(clazz: Class<T>): T? {
        return context?.getBean(clazz)
    }

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        context = applicationContext
    }
}