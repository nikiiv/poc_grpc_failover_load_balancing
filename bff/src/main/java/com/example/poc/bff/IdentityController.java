package com.example.poc.bff;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.serde.annotation.Serdeable;

@Controller("/api/identity")
public final class IdentityController {

    private final String role;
    private final String nodeId;

    public IdentityController(@Value("${poc.bff.role}") String role,
                              @Value("${poc.bff.nodeId}") String nodeId) {
        this.role = role;
        this.nodeId = nodeId;
    }

    @Get
    public Identity who() {
        return new Identity(nodeId, role);
    }

    @Serdeable
    public record Identity(String nodeId, String role) {}
}
