package com.example.poc.bff.registry;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

@Singleton
public final class EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    // directBestEffort: live broadcast; no replay of events from before a subscriber connected.
    // Subscribers receive a fresh `snapshot` at connection time (see EventsController).
    private final Sinks.Many<RegistryEvent> sink =
            Sinks.many().multicast().directBestEffort();

    // busy-loop briefly on FAIL_NON_SERIALIZED so concurrent emissions (from gRPC executor
    // threads) don't drop events.
    private final Sinks.EmitFailureHandler retryOnRace =
            Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(50));

    public Flux<RegistryEvent> asFlux() {
        return sink.asFlux();
    }

    public void emit(RegistryEvent event) {
        try {
            sink.emitNext(event, retryOnRace);
        } catch (Exception e) {
            LOG.warn("Failed to emit {}: {}", event.type(), e.toString());
        }
    }
}
