package com.futbolix.gateway;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * "Public creation and lookup are rate limited at the gateway" (reservation-service.yaml).
 * Limits per client address and per minute (fixed window, in memory) on the operations an attacker
 * would hammer: creating reservations (STRIDE D, a flood of PENDING reservations), guessing
 * reservation codes (STRIDE S/I) and the administrator login. Over the limit: 429
 * TOO_MANY_REQUESTS with Retry-After, and the request never reaches the service.
 *
 * In memory is enough for the single gateway instance of the MVP; with several instances the
 * counters must move to Redis (catalog: "Redis (token cache, rate limiting)").
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final int createPerMinute;
    private final int lookupPerMinute;
    private final int loginPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitFilter(@Value("${gateway.rate-limit.create-per-minute:10}") int createPerMinute,
                           @Value("${gateway.rate-limit.lookup-per-minute:30}") int lookupPerMinute,
                           @Value("${gateway.rate-limit.login-per-minute:10}") int loginPerMinute) {
        this(createPerMinute, lookupPerMinute, loginPerMinute, Clock.systemUTC());
    }

    RateLimitFilter(int createPerMinute, int lookupPerMinute, int loginPerMinute, Clock clock) {
        this.createPerMinute = createPerMinute;
        this.lookupPerMinute = lookupPerMinute;
        this.loginPerMinute = loginPerMinute;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        HttpMethod method = request.getMethod();
        String bucket;
        int limit;
        if (HttpMethod.POST.equals(method) && path.equals("/api/v1/reservations")) {
            bucket = "create";
            limit = createPerMinute;
        } else if (path.startsWith("/api/v1/reservations/")) {
            bucket = "lookup";
            limit = lookupPerMinute;
        } else if (HttpMethod.POST.equals(method) && path.equals("/api/v1/auth/login")) {
            bucket = "login";
            limit = loginPerMinute;
        } else {
            return chain.filter(exchange);
        }
        long minute = clock.millis() / 60_000;
        String key = bucket + "|" + client(request);
        Window window = windows.compute(key, (k, w) -> w == null || w.minute != minute ? new Window(minute) : w);
        if (window.count.incrementAndGet() <= limit) {
            if (windows.size() > 10_000) {
                windows.entrySet().removeIf(e -> e.getValue().minute != minute);
            }
            return chain.filter(exchange);
        }
        long retryAfter = Math.max(1, 60 - (clock.millis() / 1000) % 60);
        return tooManyRequests(exchange.getResponse(), retryAfter);
    }

    private static String client(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    private static Mono<Void> tooManyRequests(ServerHttpResponse response, long retryAfterSeconds) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", Long.toString(retryAfterSeconds));
        String traceId = response.getHeaders().getFirst(EdgeHeadersFilter.CORRELATION_ID);
        String body = "{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Too many requests, try again later\""
                + (traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"") + "}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;   // after EdgeHeadersFilter, before routing
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
