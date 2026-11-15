package com.futbolix.gateway;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CORS for the web app (ftx-front runs on another origin in development, http://localhost:5173).
 * Written explicitly so the rule is visible and testable:
 *
 * <ul>
 *   <li>Preflight (OPTIONS + Access-Control-Request-Method) from an allowed origin: 200 with the
 *       allowed methods and headers, and it never reaches a service.</li>
 *   <li>Preflight from any other origin: 403.</li>
 *   <li>Real requests from an allowed origin get Access-Control-Allow-Origin; from another origin
 *       they get nothing, so the browser blocks the response.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter implements WebFilter {

    static final String METHODS = "GET, POST, PUT, PATCH, DELETE, OPTIONS";
    static final String HEADERS = "Authorization, Content-Type, Idempotency-Key, X-Correlation-Id";
    static final String EXPOSED = "X-Correlation-Id, Retry-After";

    private final Set<String> allowedOrigins;

    public CorsFilter(@Value("${gateway.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String origin = request.getHeaders().getOrigin();
        if (origin == null || !request.getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }
        boolean allowed = allowedOrigins.contains(origin);
        boolean preflight = HttpMethod.OPTIONS.equals(request.getMethod())
                && request.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();
        headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        if (preflight) {
            if (!allowed) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return response.setComplete();
            }
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, METHODS);
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, HEADERS);
            headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
            response.setStatusCode(HttpStatus.OK);
            return response.setComplete();
        }
        if (allowed) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, EXPOSED);
        }
        return chain.filter(exchange);
    }
}
