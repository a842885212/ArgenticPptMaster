package domi.argenticpptmaster.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Readiness checks for template-fill.
 * <p>
 * Production template-fill is Java-authoritative; Python scripts are not required at runtime.
 * Responses include only component, status, expectedVersion and reasonCode — never absolute paths
 * or command output.
 * </p>
 */
@Component("templateFillReadiness")
public class TemplateFillReadinessContributor implements HealthIndicator {

    public static final String EXPECTED_UPSTREAM_VERSION = "ppt-master-stage4-2026.07";
    private static final String READINESS_MARKER = "template-fill/readiness-marker.txt";
    private static final String REASON_OK = "OK";
    private static final String REASON_READINESS_MARKER_MISSING = "READINESS_MARKER_MISSING";

    private final String readinessMarker;

    public TemplateFillReadinessContributor() {
        this(READINESS_MARKER);
    }

    /** Package-private for tests that need a missing marker resource. */
    TemplateFillReadinessContributor(String readinessMarker) {
        this.readinessMarker = readinessMarker == null || readinessMarker.isBlank()
                ? READINESS_MARKER
                : readinessMarker;
    }

    @Override
    public Health health() {
        Map<String, String> marker = checkReadinessMarker();
        boolean ready = isUp(marker);
        Health.Builder builder = ready ? Health.up() : Health.down();
        builder.withDetail(marker.get("component"), marker);
        return builder.build();
    }

    private Map<String, String> checkReadinessMarker() {
        ClassPathResource marker = new ClassPathResource(readinessMarker);
        if (!marker.exists()) {
            return component("readiness_marker", "DOWN", REASON_READINESS_MARKER_MISSING);
        }
        return component("readiness_marker", "UP", REASON_OK);
    }

    private static Map<String, String> component(String name, String status, String reasonCode) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("component", name);
        details.put("status", status);
        details.put("expectedVersion", EXPECTED_UPSTREAM_VERSION);
        details.put("reasonCode", reasonCode);
        return Map.copyOf(details);
    }

    private boolean isUp(Map<String, String> component) {
        return "UP".equals(component.get("status"));
    }
}
