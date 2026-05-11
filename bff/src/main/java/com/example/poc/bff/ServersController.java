package com.example.poc.bff;

import com.example.poc.bff.registry.ServerEntry;
import com.example.poc.bff.registry.ServerRegistry;
import com.example.poc.bff.registry.ServerView;
import com.example.poc.registry.DrainRequest;
import com.example.poc.registry.DrainResponse;
import com.example.poc.registry.DrainServiceGrpc;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.serde.annotation.Serdeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Controller("/api/servers")
public final class ServersController {

    private static final Logger LOG = LoggerFactory.getLogger(ServersController.class);

    private final ServerRegistry registry;

    public ServersController(ServerRegistry registry) {
        this.registry = registry;
    }

    @Get
    public List<ServerView> list() {
        return registry.snapshot().stream()
                .map(ServerView::of)
                .sorted(Comparator.comparing(ServerView::id))
                .toList();
    }

    @Post("/{id}/drain")
    public HttpResponse<DrainReply> drain(@PathVariable String id) {
        Optional<ServerEntry> e = registry.get(id);
        if (e.isEmpty()) {
            return HttpResponse.notFound();
        }
        ServerEntry entry = e.get();
        // Fire the drain RPC off-thread so we return quickly. The backend will flip its
        // health to NOT_SERVING → HealthWatcher will update the card → eventually backend
        // exits and the channel closes → entry is removed.
        CompletableFuture.runAsync(() -> {
            try {
                DrainResponse resp = DrainServiceGrpc.newBlockingStub(entry.channel())
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .requestDrain(DrainRequest.newBuilder()
                                .setDeadlineSeconds(3)
                                .build());
                LOG.info("Drain {}: accepted={} message={}", id, resp.getAccepted(), resp.getMessage());
            } catch (Exception ex) {
                LOG.warn("Drain {} failed: {}", id, ex.toString());
            }
        });
        return HttpResponse.ok(new DrainReply(id, "drain requested"));
    }

    @Serdeable
    public record DrainReply(String id, String status) {}
}
