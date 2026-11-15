package com.futbolix.gateway;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.CONNECT_TIMEOUT_ATTR;
import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routing by path (ADR-004). The gateway holds no business rule: authorization of /admin/** stays
 * in each service, which checks the administrator token itself. The Authorization header is
 * forwarded unchanged.
 *
 * CORS for the web app is configured in application.yml (globalcors), so the gateway's own
 * handler mapping answers the preflight requests.
 *
 * Order matters: the schedule paths under /courts/{id}/ go to ftx-schedules-api before the general
 * /courts/** route of ftx-courts-api catches them.
 */
@Configuration
@EnableConfigurationProperties(Upstreams.class)
public class RoutesConfiguration {

    static final int CONNECT_TIMEOUT_MS = 2_000;
    static final int RESPONSE_TIMEOUT_MS = 5_000;

    @Bean
    RouteLocator futbolixRoutes(RouteLocatorBuilder routes, Upstreams upstreams) {
        return routes.routes()
                .route("customers-auth", r -> r.order(1)
                        .path("/api/v1/auth/**")
                        .metadata(CONNECT_TIMEOUT_ATTR, CONNECT_TIMEOUT_MS)
                        .metadata(RESPONSE_TIMEOUT_ATTR, RESPONSE_TIMEOUT_MS)
                        .uri(upstreams.customers()))
                .route("schedules", r -> r.order(2)
                        .path("/api/v1/courts/*/schedules", "/api/v1/courts/*/schedules/**",
                                "/api/v1/courts/*/bookable-slots", "/api/v1/admin/courts/*/schedules/**")
                        .metadata(CONNECT_TIMEOUT_ATTR, CONNECT_TIMEOUT_MS)
                        .metadata(RESPONSE_TIMEOUT_ATTR, RESPONSE_TIMEOUT_MS)
                        .uri(upstreams.schedules()))
                .route("courts", r -> r.order(3)
                        .path("/api/v1/courts", "/api/v1/courts/**", "/api/v1/admin/courts/**")
                        .metadata(CONNECT_TIMEOUT_ATTR, CONNECT_TIMEOUT_MS)
                        .metadata(RESPONSE_TIMEOUT_ATTR, RESPONSE_TIMEOUT_MS)
                        .uri(upstreams.courts()))
                .route("reservations", r -> r.order(4)
                        .path("/api/v1/availability", "/api/v1/reservations", "/api/v1/reservations/**",
                                "/api/v1/admin/reservations", "/api/v1/admin/reservations/**")
                        .metadata(CONNECT_TIMEOUT_ATTR, CONNECT_TIMEOUT_MS)
                        .metadata(RESPONSE_TIMEOUT_ATTR, RESPONSE_TIMEOUT_MS)
                        .uri(upstreams.reservations()))
                .build();
    }
}
