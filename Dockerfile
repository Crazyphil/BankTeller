# Runtime image for BankTeller.
#
# The fat JAR must be built on the host first, either via:
#   scripts/build-image.sh        (recommended: builds JAR + image + prunes)
# or:
#   ./gradlew :server:shadowJar
# Building the image without server/build/libs/server-all.jar present will fail.
FROM docker.io/eclipse-temurin:21-jre-alpine
WORKDIR /app

# Container-aware JVM defaults: size the heap relative to the container's
# memory limit instead of host RAM. Override by setting JAVA_TOOL_OPTIONS.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

COPY server/build/libs/server-all.jar /app/bankteller.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bankteller.jar"]
