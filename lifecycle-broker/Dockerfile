# syntax=docker/dockerfile:1.6
FROM docker.io/library/gradle:8.14-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY proto ./proto
COPY lifecycle-broker ./lifecycle-broker
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :lifecycle-broker:shadowJar --no-daemon -x test

FROM docker.io/library/eclipse-temurin:17-jre-jammy
RUN groupadd -r app && useradd -r -g app app
USER app
WORKDIR /app
COPY --from=build /src/lifecycle-broker/build/libs/lifecycle-broker-all.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dsun.net.inetaddr.ttl=0 -Dsun.net.inetaddr.negative.ttl=0"
EXPOSE 7100
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
