# Stage 1: Build
FROM docker.io/gradle:8.11-jdk21 AS build
WORKDIR /app
COPY . .
# Install libatomic1 required by Node.js (used by Kotlin/JS and wasmJs webpack tasks)
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
# Build SPA, copy static assets, and create fat JAR (copyWebDist is wired into processResources)
RUN ./gradlew :server:shadowJar --no-daemon

# Stage 2: Runtime
FROM docker.io/eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/server/build/libs/server-all.jar /app/bankteller.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bankteller.jar"]
