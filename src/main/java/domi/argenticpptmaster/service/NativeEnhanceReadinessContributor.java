package domi.argenticpptmaster.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness checks for native-enhance timings dependencies (ffprobe).
 * Responses include only component/status/reasonCode — never absolute paths or command output.
 */
@Component("nativeEnhancePptxReadiness")
public class NativeEnhanceReadinessContributor implements HealthIndicator {

    public static final String EXPECTED_UPSTREAM_VERSION = "ppt-master-native-enhance-phase4-2026.08";
    static final String REASON_OK = "OK";
    static final String REASON_FFPROBE_MISSING = "FFPROBE_MISSING";
    static final String REASON_FFPROBE_NOT_EXECUTABLE = "FFPROBE_NOT_EXECUTABLE";

    private final String ffprobeCommand;

    public NativeEnhanceReadinessContributor() {
        this("ffprobe");
    }

    NativeEnhanceReadinessContributor(String ffprobeCommand) {
        this.ffprobeCommand = ffprobeCommand == null || ffprobeCommand.isBlank() ? "ffprobe" : ffprobeCommand.trim();
    }

    @Override
    public Health health() {
        Map<String, String> ffprobe = checkFfprobe();
        boolean ready = isUp(ffprobe);
        Health.Builder builder = ready ? Health.up() : Health.down();
        builder.withDetail(ffprobe.get("component"), ffprobe);
        return builder.build();
    }

    /** True when ffprobe is available for timings-dependent stages. */
    public boolean isFfprobeAvailable() {
        return isUp(checkFfprobe());
    }

    Map<String, String> checkFfprobe() {
        Path candidate = Path.of(ffprobeCommand);
        if (candidate.isAbsolute() || ffprobeCommand.contains("/") || ffprobeCommand.contains("\\")) {
            if (!Files.isRegularFile(candidate)) {
                return component("ffprobe", "DOWN", REASON_FFPROBE_MISSING);
            }
            if (!Files.isExecutable(candidate)) {
                return component("ffprobe", "DOWN", REASON_FFPROBE_NOT_EXECUTABLE);
            }
            return component("ffprobe", "UP", REASON_OK);
        }
        if (isOnPath(ffprobeCommand)) {
            return component("ffprobe", "UP", REASON_OK);
        }
        return component("ffprobe", "DOWN", REASON_FFPROBE_MISSING);
    }

    private static Map<String, String> component(String name, String status, String reasonCode) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("component", name);
        details.put("status", status);
        details.put("expectedVersion", EXPECTED_UPSTREAM_VERSION);
        details.put("reasonCode", reasonCode);
        return Map.copyOf(details);
    }

    private static boolean isUp(Map<String, String> component) {
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
