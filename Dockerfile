ARG JAVA_VERSION=21

FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS builder
WORKDIR /app
COPY gradlew build.gradle.kts settings.gradle gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/spotify-web-api-demo.jar spotify-web-api-demo.jar
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "spotify-web-api-demo.jar"]
