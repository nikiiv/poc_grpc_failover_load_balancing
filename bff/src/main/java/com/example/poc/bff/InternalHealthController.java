package com.example.poc.bff;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

/**
 * Probed by Consul. Returns 200 OK as long as the BFF process is alive and the HTTP
 * server is reachable. Not exposed through nginx — Consul talks to BFFs directly via
 * the compose network.
 */
@Controller("/api/internal/health")
public final class InternalHealthController {

    @Get
    public HttpResponse<String> health() {
        return HttpResponse.ok("OK");
    }
}
