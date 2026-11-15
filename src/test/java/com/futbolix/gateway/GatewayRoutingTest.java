package com.futbolix.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingTest {

    static final StubService COURTS = start("courts");
    static final StubService SCHEDULES = start("schedules");
    static final StubService RESERVATIONS = start("reservations");

    @Autowired WebTestClient web;

    static StubService start(String name) {
        try {
            return new StubService(name);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstreams.courts", COURTS::url);
        registry.add("gateway.upstreams.schedules", SCHEDULES::url);
        registry.add("gateway.upstreams.reservations", RESERVATIONS::url);
        // Nothing listens here: the customers service is "down" in these tests.
        registry.add("gateway.upstreams.customers", () -> "http://127.0.0.1:9");
    }

    @AfterAll
    static void stop() {
        COURTS.close();
        SCHEDULES.close();
        RESERVATIONS.close();
    }

    @Test
    void routesEachPathToItsService() {
        web.get().uri("/api/v1/courts").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("courts");
        web.get().uri("/api/v1/courts/c0000000-0000-4000-8000-000000000001/bookable-slots?date=2030-01-10")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("schedules");
        web.get().uri("/api/v1/availability?courtId=c0000000-0000-4000-8000-000000000001&date=2030-01-10")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("reservations");
        web.get().uri("/api/v1/admin/reservations").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("reservations");
        assertThat(RESERVATIONS.lastPath).isEqualTo("/api/v1/admin/reservations");
    }

    @Test
    void spoofedIdentityHeadersNeverReachAService_butTheTokenAndACorrelationIdDo() {
        web.get().uri("/api/v1/admin/reservations")
                .header("X-User-Id", "attacker").header("X-User-Role", "ADMINISTRATOR")
                .header("Authorization", "Bearer some.jwt.token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id");

        assertThat(RESERVATIONS.lastHeaders).doesNotContainKeys("x-user-id", "x-user-role");
        assertThat(RESERVATIONS.lastHeaders.get("authorization")).isEqualTo("Bearer some.jwt.token");
        assertThat(RESERVATIONS.lastHeaders.get("x-correlation-id")).isNotBlank();
    }

    @Test
    void aWellFormedCorrelationIdIsKept_aMaliciousOneIsReplaced() {
        web.get().uri("/api/v1/courts").header("X-Correlation-Id", "abc-123").exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "abc-123");
        assertThat(COURTS.lastHeaders.get("x-correlation-id")).isEqualTo("abc-123");

        web.get().uri("/api/v1/courts").header("X-Correlation-Id", "bad\" value <script>").exchange()
                .expectStatus().isOk();
        assertThat(COURTS.lastHeaders.get("x-correlation-id")).doesNotContain("<script>");
    }

    @Test
    void anUnknownPathIsAJson404() {
        web.get().uri("/api/v1/nothing-here").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("NOT_FOUND");
    }

    @Test
    void aServiceThatIsDownIsA503_andTheOthersKeepWorking() {
        web.post().uri("/api/v1/auth/login").bodyValue("{}").header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE");

        web.get().uri("/api/v1/courts").exchange().expectStatus().isOk();
    }

    @Test
    void healthReportsEachUpstream() {
        web.get().uri("/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("degraded")
                .jsonPath("$.upstreams.ftx-courts-api").isEqualTo("ok")
                .jsonPath("$.upstreams.ftx-customers-api").isEqualTo("down");
    }

    @Test
    void theWebAppOriginPassesCors() {
        web.options().uri("/api/v1/reservations")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Idempotency-Key,Content-Type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");

        web.options().uri("/api/v1/reservations")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden();
    }
}
