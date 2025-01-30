# Build stage
FROM gradle:8.2.1-jdk17-alpine AS builder
WORKDIR /app

# Copy all files from the repository
COPY . .

# Make gradlew executable (in case it's not in the repo)
RUN chmod +x gradlew

# Build application
RUN ./gradlew build --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

# Create a non-root user and switch to it
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]