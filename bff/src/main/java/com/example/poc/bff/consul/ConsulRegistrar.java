package com.example.poc.bff.consul;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Registers this BFF with Consul. Consul probes the /api/internal/health endpoint every
 * 2 s; failing probes mark the service critical; 30 s of failures triggers auto-deregister.
 * Independent observability layer alongside the broker — does not affect routing.
 */
@Context
public final class ConsulRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulRegistrar.class);

    private final String consulTarget;
    private final String role;
    private final String nodeId;
    private final String advertisedAddress;
    private final HttpClient http;

    private volatile boolean started;

    public ConsulRegistrar(@Value("${poc.bff.consulTarget:}") String consulTarget,
                           @Value("${poc.bff.role}") String role,
                           @Value("${poc.bff.nodeId}") String nodeId,
                           @Value("${poc.bff.advertisedAddress}") String advertisedAddress) {
        this.consulTarget = consulTarget;
        this.role = role;
        this.nodeId = nodeId;
        this.advertisedAddress = advertisedAddress;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @PostConstruct
    void start() {
        if (consulTarget.isBlank()) {
            LOG.info("CONSUL_TARGET not set — skipping Consul registration");
            return;
        }
        started = true;
        Thread t = new Thread(this::registerWithRetry, "consul-registrar");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    void stop() {
        if (!started) return;
        URI uri = URI.create("http://" + consulTarget + "/v1/agent/service/deregister/" + nodeId);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(1))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            LOG.info("Deregistered from Consul: {}", nodeId);
        } catch (Exception e) {
            LOG.info("Consul deregistration failed (best effort): {}", e.getMessage());
        }
    }

    private void registerWithRetry() {
        long delayMs = 250;
        long maxDelayMs = 5_000;
        String body = registrationBody();
        URI uri = URI.create("http://" + consulTarget + "/v1/agent/service/register");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    LOG.info("Registered with Consul {} as bff/{}", consulTarget, nodeId);
                    return;
                }
                LOG.info("Consul rejected registration: HTTP {} body={} — retrying",
                        resp.statusCode(), resp.body());
            } catch (Exception e) {
                LOG.info("Consul registration to {} failed ({}) — retrying in {} ms",
                        consulTarget, e.getMessage(), delayMs);
            }
            try { Thread.sleep(delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            delayMs = Math.min(maxDelayMs, delayMs * 2);
        }
    }

    private String registrationBody() {
        // advertisedAddress is "host:port"; split for the Consul payload.
        String[] hp = advertisedAddress.split(":");
        String host = hp[0];
        int port = Integer.parseInt(hp[1]);
        return """
                {
                  "ID": "%s",
                  "Name": "bff",
                  "Tags": ["%s"],
                  "Address": "%s",
                  "Port": %d,
                  "Check": {
                    "Name": "HTTP /api/internal/health",
                    "HTTP": "http://%s:%d/api/internal/health",
                    "Method": "GET",
                    "Interval": "2s",
                    "Timeout": "1s",
                    "DeregisterCriticalServiceAfter": "30s"
                  }
                }
                """.formatted(nodeId, role, host, port, host, port);
    }
}
