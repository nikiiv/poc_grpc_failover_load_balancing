package com.example.poc.bff.registry;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Singleton
public final class EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    // onBackpressureBuffer with generous capacity. directBestEffort dropped most events
    // under bursty load. A buffered multicast handles bursts well; the side effect — the
    // first subscriber sees a brief replay of pre-subscribe events — is harmless because
    // EventsController also emits a fresh `snapshot` at subscribe time and the UI applies
    // events idempotently.
    private final Sinks.Many<RegistryEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(1024, false);

    public Flux<RegistryEvent> asFlux() {
        return sink.asFlux();
    }

    /**
     * Fire-and-forget event emission. {@code synchronized} avoids {@code FAIL_NON_SERIALIZED}
     * races between concurrent emitters (gRPC executor threads + HTTP request threads).
     * Uses {@code tryEmitNext} (never throws) so a slow SSE subscriber can't poison the
     * controller's response — overflow / no-subscriber just drops the event.
     */
    public synchronized void emit(RegistryEvent event) {
        Sinks.EmitResult r = sink.tryEmitNext(event);
        if (r.isFailure() && r != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            LOG.debug("Dropped event {} ({})", event.type(), r);
        }
    }
}
