package com.example.poc.bff.registry;

import io.micronaut.serde.annotation.Serdeable;

/** Passive record of any node the broker has told us about. */
@Serdeable
public record KnownNode(
        String nodeId,
        String kind,        // "BFF" or "SERVER"
        String role,
        String address,
        long firstSeenMs,
        long lastSeenMs) {}
