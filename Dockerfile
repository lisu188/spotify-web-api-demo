# Build stage
FROM gradle:8.10-jdk23-alpine AS builder
WORKDIR /app
COPY . .
EXPOSE 8080
ENTRYPOINT ["./gradlew", "bootRun"]