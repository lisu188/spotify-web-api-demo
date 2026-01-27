ARG JAVA_VERSION=17

# Build stage
FROM gradle:8.10-jdk${JAVA_VERSION} AS builder
WORKDIR /app
COPY . .
RUN chmod +x gradlew && \
    ./gradlew bootJar

# Runtime stage
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine
RUN apk add --no-cache tomcat-native
WORKDIR /app
COPY --from=builder /app/build/libs/spotify-web-api-demo.jar spotify-web-api-demo.jar

# Create a non-root user and switch to it
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "spotify-web-api-demo.jar"]
