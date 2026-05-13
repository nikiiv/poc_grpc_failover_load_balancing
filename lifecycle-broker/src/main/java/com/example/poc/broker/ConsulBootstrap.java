package com.example.poc.broker;

import com.example.poc.lifecycle.NodeInfo;
import com.example.poc.lifecycle.NodeKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * On broker startup, queries Consul for the current set of healthy nodes (`?passing`)
 * and returns them as {@link NodeInfo} records. The broker pre-seeds its in-memory map
 * with these before accepting any new {@code Subscribe} call — so a fresh subscriber
 * connecting immediately after a broker restart sees the full cluster, not an empty
 * snapshot.
 *
 * <p>Best-effort: if Consul is unreachable, returns an empty list and logs a warning;
 * the broker still starts and behaves like before (relies on nodes re-announcing).
 */
final class ConsulBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulBootstrap.class);

    private final String consulTarget;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    ConsulBootstrap(String consulTarget) {
        this.consulTarget = consulTarget;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** Pulls both service catalogs in turn ('echo-server' and 'bff') and merges results. */
    List<NodeInfo> seed() {
        List<NodeInfo> out = new ArrayList<>();
        out.addAll(fetch("echo-server", NodeKind.SERVER));
        out.addAll(fetch("bff",          NodeKind.BFF));
        LOG.info("Consul bootstrap: {} nodes seeded ({} SERVER, {} BFF)",
                out.size(),
                out.stream().filter(n -> n.getKind() == NodeKind.SERVER).count(),
                out.stream().filter(n -> n.getKind() == NodeKind.BFF).count());
        return out;
    }

    private List<NodeInfo> fetch(String serviceName, NodeKind kind) {
        URI uri = URI.create("http://" + consulTarget
                + "/v1/health/service/" + serviceName + "?passing");
        List<NodeInfo> out = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warn("Consul {} returned HTTP {} — skipping bootstrap for this kind",
                        uri, resp.statusCode());
                return out;
            }
            JsonNode entries = json.readTree(resp.body());
            for (JsonNode e : entries) {
                JsonNode svc = e.get("Service");
                if (svc == null) continue;
                String id = text(svc, "ID");
                String addr = text(svc, "Address");
                int port = svc.has("Port") ? svc.get("Port").asInt() : 0;
                String role = "";
                JsonNode tags = svc.get("Tags");
                if (tags != null && tags.isArray() && tags.size() > 0) {
                    role = tags.get(0).asText();
                }
                if (id.isEmpty() || addr.isEmpty() || port == 0) continue;
                out.add(NodeInfo.newBuilder()
                        .setKind(kind)
                        .setRole(role)
                        .setNodeId(id)
                        .setAddress(addr + ":" + port)
                        .build());
            }
        } catch (Exception e) {
            LOG.warn("Consul bootstrap fetch of {} failed: {} — broker will start empty for this kind",
                    serviceName, e.getMessage());
        }
        return out;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return (n == null || n.isNull()) ? "" : n.asText();
    }
}
