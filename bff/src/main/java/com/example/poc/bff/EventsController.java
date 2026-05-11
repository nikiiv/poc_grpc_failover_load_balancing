package com.example.poc.bff;

import com.example.poc.bff.registry.EventBus;
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

    public EventsController(EventBus events, ServerRegistry registry) {
        this.events = events;
        this.registry = registry;
    }

    @Get
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<RegistryEvent>> stream() {
        return Flux.defer(() -> {
            List<ServerView> snap = registry.snapshot().stream()
                    .map(ServerView::of)
                    .sorted(Comparator.comparing(ServerView::id))
                    .toList();
            return Flux.concat(
                    Flux.just(RegistryEvent.snapshot(snap)),
                    events.asFlux()
            );
        }).map(Event::of);
    }
}
