package com.example.poc.bff.registry;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ServerView(
        String id,
        String host,
        int port,
        String status,
        int inFlight,
        long totalHandled,
        long registeredAtMs,
        long lastSeenMs) {

    public static ServerView of(ServerEntry e) {
        return new ServerView(
                e.id(),
                e.host(),
                e.port(),
                e.status().name(),
                e.inFlight(),
                e.totalHandled(),
                e.registeredAt().toEpochMilli(),
                e.lastSeen().toEpochMilli());
    }
}
