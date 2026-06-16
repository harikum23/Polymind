package com.polymind.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Lightweight {@code GET /v1/health} liveness endpoint (separate from actuator). */
@RestController
@RequestMapping("/v1")
@Tag(name = "Health", description = "Service liveness")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Liveness probe")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "polymind", "version", "0.1.0");
    }
}
