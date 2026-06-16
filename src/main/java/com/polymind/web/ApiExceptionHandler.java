package com.polymind.web;

import com.polymind.admission.BackpressureException;
import com.polymind.inference.EngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps failures to OpenAI-shaped error envelopes ({@code {"error": {...}}}). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EngineException.class)
    public ResponseEntity<Map<String, Object>> engine(EngineException e) {
        return error(HttpStatus.BAD_GATEWAY, "engine_error", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request_error", e.getMessage());
    }

    @ExceptionHandler(BackpressureException.class)
    public ResponseEntity<Map<String, Object>> backpressure(BackpressureException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "overloaded", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String type, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", Map.of("message", message == null ? status.getReasonPhrase() : message,
                        "type", type)));
    }
}
