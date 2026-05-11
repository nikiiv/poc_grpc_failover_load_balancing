package com.example.poc.bff;

import com.example.poc.bff.registry.EventBus;
import com.example.poc.bff.registry.KnownNode;
import com.example.poc.bff.registry.KnownNodesRegistry;
import com.example.poc.bff.registry.RegistryEvent;
import com.example.poc.bff.registry.ServerRegistry;
import com.example.poc.bff.registry.ServerView;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.sse.Event;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.List;

@Controller("/api/events")
public final class EventsController {

    private final EventBus events;
    private final ServerRegistry registry;
    private final KnownNodesRegistry knownNodes;

    public EventsController(EventBus events, ServerRegistry registry, KnownNodesRegistry knownNodes) {
        this.events = events;
        this.registry = registry;
        this.knownNodes = knownNodes;
    }

    @Get
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<RegistryEvent>> stream() {
        return Flux.defer(() -> {
            List<ServerView> snap = registry.snapshot().stream()
                    .map(ServerView::of)
                    .sorted(Comparator.comparing(ServerView::id))
                    .toList();
            List<KnownNode> known = knownNodes.snapshot().stream()
                    .sorted(Comparator.comparing(KnownNode::nodeId))
                    .toList();
            return Flux.concat(
                    Flux.just(RegistryEvent.snapshot(snap, known)),
                    events.asFlux()
            );
        }).map(Event::of);
    }
}
