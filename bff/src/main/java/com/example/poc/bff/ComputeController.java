package com.example.poc.bff;

import com.example.poc.bff.lb.RoundRobinRouter;
import com.example.poc.bff.registry.EventBus;
import com.example.poc.bff.registry.RegistryEvent;
import com.example.poc.bff.registry.ServerEntry;
import com.example.poc.echo.ComputeRequest;
import com.example.poc.echo.ComputeResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.serde.annotation.Serdeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

@Controller("/api/compute")
public final class ComputeController {

    private static final Logger LOG = LoggerFactory.getLogger(ComputeController.class);

    private final RoundRobinRouter router;
    private final EventBus events;

    public ComputeController(RoundRobinRouter router, EventBus events) {
        this.router = router;
        this.events = events;
    }

    @Post
    public HttpResponse<ComputeReply> compute(@Body ComputeBody body) {
        int workMs = body.workMs() == null ? 200 : Math.max(1, body.workMs());
        Optional<ServerEntry> pick = router.pickHealthy();
        if (pick.isEmpty()) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        }
        ServerEntry entry = pick.get();
        entry.incrementInFlight();
        try {
            ComputeResponse r = entry.echoStub().compute(ComputeRequest.newBuilder()
                    .setWorkMs(workMs)
                    .build());
            entry.incrementHandled();
            entry.touch();
            long now = Instant.now().toEpochMilli();
            events.emit(RegistryEvent.requestRouted(r.getServerId(), now, "compute(" + workMs + "ms)"));
            return HttpResponse.ok(new ComputeReply(r.getServerId(), r.getElapsedMs()));
        } catch (Exception e) {
            LOG.warn("Compute to {} failed: {}", entry.id(), e.toString());
            return HttpResponse.status(HttpStatus.BAD_GATEWAY);
        } finally {
            entry.decrementInFlight();
        }
    }

    @Serdeable
    public record ComputeBody(Integer workMs) {}

    @Serdeable
    public record ComputeReply(String serverId, long elapsedMs) {}
}
