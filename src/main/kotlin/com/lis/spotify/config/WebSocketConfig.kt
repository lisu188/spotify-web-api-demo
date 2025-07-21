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

package com.lis.spotify.config

import java.lang.reflect.Field
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.websocket.HandshakeResponse
import javax.websocket.server.HandshakeRequest
import javax.websocket.server.ServerEndpointConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.socket.server.standard.ServerEndpointExporter

@Configuration
class WebSocketConfig {
  companion object {
    private val logger = LoggerFactory.getLogger(WebSocketConfig::class.java)
  }

  @Bean
  fun serverEndpointExporter(): ServerEndpointExporter {
    logger.debug("Creating ServerEndpointExporter bean")
    return ServerEndpointExporter()
  }
}

@Component
class WebsocketSpringConfigurator : ServerEndpointConfig.Configurator(), ApplicationContextAware {
  companion object {
    private var context: BeanFactory? = null
    private val logger = LoggerFactory.getLogger(WebsocketSpringConfigurator::class.java)
  }

  override fun modifyHandshake(
    config: ServerEndpointConfig,
    request: HandshakeRequest,
    response: HandshakeResponse,
  ) {
    // TODO: handle nulls
    config.userProperties["clientId"] =
      getRequest(request).cookies.findLast { cookie: Cookie? -> cookie?.name == "clientId" }?.value
    logger.debug("Handshake modify: setting clientId={}", config.userProperties["clientId"])
  }

  private fun getRequest(request: HandshakeRequest): HttpServletRequest {
    val field: Field = request.javaClass.getDeclaredField("request")
    field.isAccessible = true
    return field.get(request) as HttpServletRequest
  }

  override fun <T> getEndpointInstance(clazz: Class<T>): T? {
    val instance = context?.getBean(clazz)
    logger.debug("Providing endpoint instance of {} -> {}", clazz.simpleName, instance != null)
    return instance
  }

  override fun setApplicationContext(applicationContext: ApplicationContext) {
    context = applicationContext
  }
}
