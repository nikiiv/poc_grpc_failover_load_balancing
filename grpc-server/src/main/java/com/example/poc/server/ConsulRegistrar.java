package com.example.poc.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Registers this backend with Consul using its HTTP API, including a gRPC health check
 * that points at the standard {@code grpc.health.v1.Health} service the backend already
 * exposes. Consul probes every 2 s; failed probes mark the service critical; missing
 * probes for 30 s cause Consul to auto-deregister.
 *
 * <p>This is an independent observability layer alongside the broker — the BFF still
 * uses its own Health.Watch streams for routing decisions. Consul gives us a separate
 * "dashboard" view of cluster health (UI at http://localhost:8500/ui/).
 */
final class ConsulRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulRegistrar.class);

    private final String consulTarget;
    private final String serviceId;
    private final String serviceName;
    private final String role;
    private final String host;
    private final int port;
    private final HttpClient http;

    ConsulRegistrar(String consulTarget, String serviceId, String serviceName,
                    String role, String host, int port) {
        this.consulTarget = consulTarget;
        this.serviceId = serviceId;
        this.serviceName = serviceName;
        this.role = role;
        this.host = host;
        this.port = port;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    void registerWithRetry() {
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
                    LOG.info("Registered with Consul {} as {}/{}", consulTarget, serviceName, serviceId);
                    return;
                }
                LOG.info("Consul rejected registration: HTTP {} body={} — retrying", resp.statusCode(), resp.body());
            } catch (Exception e) {
                LOG.info("Consul registration to {} failed ({}) — retrying in {} ms",
                        consulTarget, e.getMessage(), delayMs);
            }
            try { Thread.sleep(delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            delayMs = Math.min(maxDelayMs, delayMs * 2);
        }
    }

    void deregister() {
        URI uri = URI.create("http://" + consulTarget + "/v1/agent/service/deregister/" + serviceId);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(1))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            LOG.info("Deregistered from Consul: {}", serviceId);
        } catch (Exception e) {
            LOG.info("Consul deregistration failed (best effort): {}", e.getMessage());
        }
    }

    private String registrationBody() {
        // Hand-built JSON — the body is small and known-safe (all fields come from env vars
        // that we validate, no user input). Saves pulling in Jackson for one method.
        return """
                {
                  "ID": "%s",
                  "Name": "%s",
                  "Tags": ["%s"],
                  "Address": "%s",
                  "Port": %d,
                  "Check": {
                    "Name": "gRPC health on %s:%d",
                    "GRPC": "%s:%d",
                    "GRPCUseTLS": false,
                    "Interval": "2s",
                    "Timeout": "1s",
                    "DeregisterCriticalServiceAfter": "30s"
                  }
                }
                """.formatted(
                serviceId, serviceName, role,
                host, port,
                host, port,
                host, port);
    }
}
