# Build stage
FROM gradle:8.10-jdk23-alpine AS builder
WORKDIR /app
COPY . .
RUN chmod +x gradlew && \
    ./gradlew bootJar

# Runtime stage
FROM eclipse-temurin:23-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/spotify-web-api-demo-1.0.0-SNAPSHOT.jar spotify-web-api-demo-1.0.0-SNAPSHOT.jar

# Create a non-root user and switch to it
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/spotify-web-api-demo-1.0.0-SNAPSHOT.jar"]