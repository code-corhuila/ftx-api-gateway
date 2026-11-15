package com.futbolix.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** STRIDE D of TB1: a flood of reservation requests is cut at the gateway. */
class RateLimitFilterTest {

    final Clock clock = Clock.fixed(Instant.parse("2030-01-10T15:20:10Z"), ZoneOffset.UTC);
    final RateLimitFilter filter = new RateLimitFilter(3, 5, 2, clock);
    final AtomicInteger forwarded = new AtomicInteger();
    final GatewayFilterChain chain = exchange -> {
        forwarded.incrementAndGet();
        return Mono.empty();
    };

    MockServerWebExchange create(String ip) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/reservations")
                .remoteAddress(new InetSocketAddress(ip, 40000)));
    }

    @Test
    void theFourthCreationInAMinuteIsRefusedWith429() {
        for (int i = 0; i < 3; i++) {
            filter.filter(create("10.0.0.1"), chain).block();
        }
        MockServerWebExchange fourth = create("10.0.0.1");
        filter.filter(fourth, chain).block();

        assertThat(forwarded.get()).isEqualTo(3);
        assertThat(fourth.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("50");
    }

    @Test
    void eachClientHasItsOwnLimit() {
        for (int i = 0; i < 3; i++) {
            filter.filter(create("10.0.0.1"), chain).block();
        }
        filter.filter(create("10.0.0.2"), chain).block();

        assertThat(forwarded.get()).isEqualTo(4);
    }

    @Test
    void codeGuessingIsLimitedToo_andOtherPathsAreNot() {
        for (int i = 0; i < 6; i++) {
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/reservations/AAAAAAAAA" + i)
                    .remoteAddress(new InetSocketAddress("10.0.0.3", 40000))), chain).block();
        }
        assertThat(forwarded.get()).isEqualTo(5);

        for (int i = 0; i < 50; i++) {
            filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/courts")
                    .remoteAddress(new InetSocketAddress("10.0.0.3", 40000))), chain).block();
        }
        assertThat(forwarded.get()).isEqualTo(55);
    }
}
