package com.futbolix.gateway;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Errors produced by the gateway itself (no route, service down, timeout) use the same
 * ErrorResponse as the services (_shared.yaml), never a stack trace or an internal address.
 * Errors produced by a service pass through untouched.
 */
@Component
@Order(-2)   // before Spring Boot's DefaultErrorWebExceptionHandler
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(error);
        }
        HttpStatusCode status;
        String code;
        String message;
        if (error instanceof ResponseStatusException rse && rse.getStatusCode().value() == 404) {
            status = HttpStatus.NOT_FOUND;
            code = "NOT_FOUND";
            message = "The requested resource was not found";
        } else if (error instanceof ResponseStatusException rse && rse.getStatusCode().value() == 504
                || causedBy(error, TimeoutException.class)) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            code = "SERVICE_UNAVAILABLE";
            message = "The service did not answer in time";
        } else if (causedBy(error, ConnectException.class)
                || error instanceof ResponseStatusException rse && rse.getStatusCode().value() == 503) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            code = "SERVICE_UNAVAILABLE";
            message = "The service is temporarily unavailable";
        } else if (error instanceof ResponseStatusException rse && rse.getStatusCode().is4xxClientError()) {
            status = rse.getStatusCode();
            code = "BAD_REQUEST";
            message = "Invalid request";
        } else {
            status = HttpStatus.BAD_GATEWAY;
            code = "SERVICE_UNAVAILABLE";
            message = "The service is temporarily unavailable";
        }
        log.warn("Gateway error path={} status={} cause={}", exchange.getRequest().getPath().value(), status.value(),
                error.getClass().getSimpleName());
        String traceId = response.getHeaders().getFirst(EdgeHeadersFilter.CORRELATION_ID);
        String body = "{\"error\":\"" + code + "\",\"message\":\"" + message + "\""
                + (traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"") + "}";
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
