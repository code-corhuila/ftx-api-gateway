package com.futbolix.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where each Futbolix service lives inside the platform network. The services keep their ports
 * private to the network in production; only the gateway (8080) is public (ADR-004).
 */
@ConfigurationProperties(prefix = "gateway.upstreams")
public record Upstreams(String customers, String courts, String schedules, String reservations) {
}
