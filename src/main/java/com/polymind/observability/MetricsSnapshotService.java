package com.polymind.observability;

import com.polymind.admission.AdmissionControl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates a compact metrics snapshot for {@code /v1/admin/metrics}: JVM/HTTP highlights plus
 * Polymind-specific routing decisions and live admission stats. The full Prometheus scrape remains
 * at {@code /actuator/prometheus}.
 */
@Service
public class MetricsSnapshotService {

    private final MeterRegistry registry;
    private final AdmissionControl admission;

    public MetricsSnapshotService(MeterRegistry registry, AdmissionControl admission) {
        this.registry = registry;
        this.admission = admission;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> adm = new LinkedHashMap<>();
        adm.put("inFlight", admission.inFlight());
        adm.put("waiting", admission.waiting());
        adm.put("availablePermits", admission.availablePermits());
        out.put("admission", adm);

        Map<String, Double> routing = new LinkedHashMap<>();
        Search.in(registry).name("polymind.routing.decisions").counters()
                .forEach(c -> routing.merge(
                        c.getId().getTag("model") + "/" + c.getId().getTag("category"),
                        c.count(), Double::sum));
        out.put("routingDecisions", routing);

        out.put("jvmThreadsLive", gauge("jvm.threads.live"));
        out.put("processCpuUsage", gauge("process.cpu.usage"));
        out.put("httpRequestsTotal", httpRequestCount());
        return out;
    }

    private Double gauge(String name) {
        var g = registry.find(name).gauge();
        return g == null ? null : g.value();
    }

    private double httpRequestCount() {
        return registry.find("http.server.requests").timers().stream()
                .mapToDouble(io.micrometer.core.instrument.Timer::count).sum();
    }
}
