# Runtime image for BankTeller.
#
# The fat JAR must be built on the host first — either via
# `scripts/build-image.sh` or `./gradlew :server:shadowJar` — because this
# image only copies the pre-built artifact and does not run a Gradle build.
FROM docker.io/eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY server/build/libs/server-all.jar /app/bankteller.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bankteller.jar"]