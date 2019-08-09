package com.lis.spotify.config


import com.mongodb.MongoClient
import com.mongodb.MongoClientOptions
import com.mongodb.MongoCredential
import com.mongodb.ServerAddress
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.config.AbstractMongoConfiguration
import java.net.URI

@Configuration
class MongoConfig : AbstractMongoConfiguration() {
    override fun getDatabaseName(): String {
        return "heroku_jzmktc4h"
    }

    @Bean
    override fun mongoClient(): MongoClient {
        val s1 = System.getenv()["MONGODB_URI"]
        if (s1.isNullOrEmpty()) {
            return MongoClient()
        } else {
            val s = URI.create(s1)
            val userInfo = s.userInfo.split(":")
            return MongoClient(ServerAddress(s.host, s.port), MongoCredential.createCredential(userInfo[0], databaseName, userInfo[1].toCharArray()), MongoClientOptions.builder().build())
        }
    }
}