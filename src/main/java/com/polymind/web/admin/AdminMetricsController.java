package com.polymind.web.admin;

import com.polymind.observability.MetricsSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** {@code /v1/admin/metrics}: compact operational snapshot (admin only). */
@RestController
@RequestMapping("/v1/admin")
@Tag(name = "Admin: Metrics", description = "Operational metrics snapshot (admin only)")
public class AdminMetricsController {

    private final MetricsSnapshotService snapshots;

    public AdminMetricsController(MetricsSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/metrics")
    @Operation(summary = "Metrics snapshot (routing decisions, admission, JVM)")
    public Map<String, Object> metrics() {
        return snapshots.snapshot();
    }
}
