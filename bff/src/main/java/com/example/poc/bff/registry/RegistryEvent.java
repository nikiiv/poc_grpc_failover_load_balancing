package com.example.poc.bff.registry;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * Wire-format event for the /api/events SSE stream. Flat record with nullable fields
 * keeps JSON shape simple for the UI; the {@code type} discriminator drives the switch.
 */
@Serdeable
public record RegistryEvent(
        String type,
        @Nullable List<ServerView> servers,
        @Nullable ServerView server,
        @Nullable String id,
        @Nullable String status,
        @Nullable String message,
        @Nullable Long handledAtMs,
        @Nullable List<KnownNode> nodes,
        @Nullable KnownNode node) {

    public static RegistryEvent snapshot(List<ServerView> servers, List<KnownNode> nodes) {
        return new RegistryEvent("snapshot", servers, null, null, null, null, null, nodes, null);
    }

    public static RegistryEvent serverAdded(ServerView server) {
        return new RegistryEvent("serverAdded", null, server, null, null, null, null, null, null);
    }

    public static RegistryEvent serverRemoved(String id) {
        return new RegistryEvent("serverRemoved", null, null, id, null, null, null, null, null);
    }

    public static RegistryEvent statusChanged(String id, String status) {
        return new RegistryEvent("statusChanged", null, null, id, status, null, null, null, null);
    }

    public static RegistryEvent requestRouted(String serverId, long handledAtMs, String message) {
        return new RegistryEvent("requestRouted", null, null, serverId, null, message, handledAtMs, null, null);
    }

    public static RegistryEvent nodeJoined(KnownNode node) {
        return new RegistryEvent("nodeJoined", null, null, null, null, null, null, null, node);
    }

    public static RegistryEvent nodeLeft(String id) {
        return new RegistryEvent("nodeLeft", null, null, id, null, null, null, null, null);
    }
}
