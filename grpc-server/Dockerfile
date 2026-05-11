# syntax=docker/dockerfile:1.6
FROM docker.io/library/gradle:8.14-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY proto ./proto
COPY grpc-server ./grpc-server
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :grpc-server:shadowJar --no-daemon -x test

FROM docker.io/library/eclipse-temurin:17-jre-jammy
RUN groupadd -r app && useradd -r -g app app
USER app
WORKDIR /app
COPY --from=build /src/grpc-server/build/libs/grpc-server-all.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
