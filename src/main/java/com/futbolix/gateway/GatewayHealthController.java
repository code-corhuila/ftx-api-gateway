package com.futbolix.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * GET /health of api-gateway.yaml: "ok" when every upstream answers its /health/readiness,
 * "degraded" otherwise, with the state of each one. The gateway itself keeps routing either way,
 * so a dead service does not take the others down.
 */
@RestController
public class GatewayHealthController {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final WebClient client;
    private final Map<String, String> upstreams = new LinkedHashMap<>();

    public GatewayHealthController(WebClient.Builder builder, Upstreams upstreams) {
        this.client = builder.build();
        this.upstreams.put("ftx-customers-api", upstreams.customers());
        this.upstreams.put("ftx-courts-api", upstreams.courts());
        this.upstreams.put("ftx-schedules-api", upstreams.schedules());
        this.upstreams.put("ftx-reservations-api", upstreams.reservations());
    }

    @GetMapping("/health")
    public Mono<Health> health() {
        return Flux.fromIterable(upstreams.entrySet())
                .flatMap(e -> check(e.getValue()).map(state -> Map.entry(e.getKey(), state)))
                .collectList()
                .map(states -> {
                    Map<String, String> byName = new LinkedHashMap<>();
                    upstreams.keySet().forEach(name -> states.stream()
                            .filter(s -> s.getKey().equals(name)).findFirst()
                            .ifPresent(s -> byName.put(name, s.getValue())));
                    boolean allOk = byName.values().stream().allMatch("ok"::equals);
                    return new Health(allOk ? "ok" : "degraded", Instant.now(), byName);
                });
    }

    private Mono<String> check(String baseUrl) {
        return client.get().uri(baseUrl + "/health/readiness")
                .retrieve()
                .toBodilessEntity()
                .map(response -> "ok")
                .timeout(TIMEOUT)
                .onErrorReturn("down");
    }

    public record Health(String status, Instant timestamp, Map<String, String> upstreams) {
    }
}
