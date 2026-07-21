package domi.argenticpptmaster.service;

import domi.argenticpptmaster.config.PptMasterProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Readiness checks for template-fill upstream dependencies.
 * <p>
 * Responses include only component, status, expectedVersion and reasonCode — never absolute paths
 * or command output.
 * </p>
 */
@Component("templateFillReadiness")
public class TemplateFillReadinessContributor implements HealthIndicator {

    public static final String EXPECTED_UPSTREAM_VERSION = "ppt-master-stage4-2026.07";
    private static final String READINESS_MARKER = "template-fill/readiness-marker.txt";
    private static final String REASON_OK = "OK";
    private static final String REASON_PYTHON_MISSING = "PYTHON_MISSING";
    private static final String REASON_PYTHON_NOT_EXECUTABLE = "PYTHON_NOT_EXECUTABLE";
    private static final String REASON_SCRIPT_MISSING = "SCRIPT_MISSING";
    private static final String REASON_READINESS_MARKER_MISSING = "READINESS_MARKER_MISSING";

    private final PptMasterProperties properties;

    public TemplateFillReadinessContributor(PptMasterProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        List<Map<String, String>> components = new ArrayList<>();
        components.add(checkPython());
        components.add(checkScript("project_manager"));
        components.add(checkScript("template_fill_pptx"));
        components.add(checkReadinessMarker());

        boolean ready = components.stream().allMatch(this::isUp);
        Health.Builder builder = ready ? Health.up() : Health.down();
        for (Map<String, String> component : components) {
            builder.withDetail(component.get("component"), component);
        }
        return builder.build();
    }

    private Map<String, String> checkPython() {
        String command = properties.pythonCommand();
        if (command == null || command.isBlank()) {
            return component("python", "DOWN", REASON_PYTHON_MISSING);
        }
        Path candidate = Path.of(command);
        if (Files.exists(candidate)) {
            if (!Files.isExecutable(candidate)) {
                return component("python", "DOWN", REASON_PYTHON_NOT_EXECUTABLE);
            }
            return component("python", "UP", REASON_OK);
        }
        if (isOnPath(command)) {
            return component("python", "UP", REASON_OK);
        }
        return component("python", "DOWN", REASON_PYTHON_MISSING);
    }

    private Map<String, String> checkScript(String scriptBaseName) {
        Path script = properties.repoPath()
                .resolve("skills/ppt-master/scripts/" + scriptBaseName + ".py");
        if (!Files.isRegularFile(script)) {
            return component(scriptBaseName, "DOWN", REASON_SCRIPT_MISSING);
        }
        return component(scriptBaseName, "UP", REASON_OK);
    }

    private Map<String, String> checkReadinessMarker() {
        ClassPathResource marker = new ClassPathResource(READINESS_MARKER);
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

    private static boolean isOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry.trim(), command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
