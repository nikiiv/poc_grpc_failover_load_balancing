package com.example.poc.bff;

import com.example.poc.bff.lb.RoundRobinRouter;
import com.example.poc.bff.registry.EventBus;
import com.example.poc.bff.registry.RegistryEvent;
import com.example.poc.bff.registry.ServerEntry;
import com.example.poc.echo.EchoRequest;
import com.example.poc.echo.EchoResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.serde.annotation.Serdeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Controller("/api/echo")
public final class EchoController {

    private static final Logger LOG = LoggerFactory.getLogger(EchoController.class);

    private final RoundRobinRouter router;
    private final EventBus events;
    private final AtomicInteger seq = new AtomicInteger();

    public EchoController(RoundRobinRouter router, EventBus events) {
        this.router = router;
        this.events = events;
    }

    @Post
    public HttpResponse<EchoReply> echo(@Body EchoBody body) {
        Optional<ServerEntry> pick = router.pickHealthy();
        if (pick.isEmpty()) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        }
        ServerEntry entry = pick.get();
        entry.incrementInFlight();
        try {
            EchoResponse r = entry.echoStub().echo(EchoRequest.newBuilder()
                    .setMessage(body.message())
                    .setClientSeq(seq.incrementAndGet())
                    .build());
            entry.incrementHandled();
            entry.touch();
            events.emit(RegistryEvent.requestRouted(r.getServerId(), r.getHandledAtMs(), body.message()));
            return HttpResponse.ok(new EchoReply(r.getMessage(), r.getServerId(), r.getHandledAtMs()));
        } catch (Exception e) {
            LOG.warn("Echo to {} failed: {}", entry.id(), e.toString());
            return HttpResponse.status(HttpStatus.BAD_GATEWAY);
        } finally {
            entry.decrementInFlight();
        }
    }

    @Serdeable
    public record EchoBody(String message) {}

    @Serdeable
    public record EchoReply(String message, String serverId, long handledAtMs) {}
}
