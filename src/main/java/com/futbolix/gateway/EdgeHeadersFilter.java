package com.futbolix.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Runs first on every request:
 *
 * <ul>
 *   <li>Removes X-User-Id and X-User-Role sent by the client. 07-api/authentication.md: upstream
 *       services must never trust client-supplied identity headers (STRIDE row 7 of TB1).</li>
 *   <li>Keeps a well-formed X-Correlation-Id or creates one, sends it to the service and returns it
 *       to the client, so one request can be followed across every service log (AT-002).</li>
 * </ul>
 */
@Component
public class EdgeHeadersFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID = "X-Correlation-Id";
    static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID);
        String correlationId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove("X-User-Id");
                    h.remove("X-User-Role");
                    h.set(CORRELATION_ID, correlationId);
                })
                .build();
        exchange.getResponse().getHeaders().set(CORRELATION_ID, correlationId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
