# syntax=docker/dockerfile:1.6
FROM docker.io/library/gradle:8.14-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY proto ./proto
COPY bff ./bff
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :bff:shadowJar --no-daemon -x test

FROM docker.io/library/eclipse-temurin:17-jre-jammy
RUN groupadd -r app && useradd -r -g app app
USER app
WORKDIR /app
COPY --from=build /src/bff/build/libs/bff-all.jar app.jar
# Short DNS TTL: when a backend container restarts on Docker/Podman, it gets a new IP.
# Without this, the JVM's default ~30 s positive-cache makes us connect to the stale IP for
# up to 30 s after the restart, and gRPC channels fail with "UNAVAILABLE: io exception".
#
# Note: `networkaddress.cache.ttl` is a SECURITY property (read from java.security), not
# a system property — `-D` doesn't always honor it. `sun.net.inetaddr.ttl` is the supported
# system-property override on OpenJDK.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dsun.net.inetaddr.ttl=2 -Dsun.net.inetaddr.negative.ttl=0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
