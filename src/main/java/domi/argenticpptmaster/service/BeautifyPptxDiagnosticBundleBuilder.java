package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Whitelist-only diagnostic ZIP builder for beautify-pptx jobs.
 * <p>
 * Excludes source PPTX, images, identity/inventory/plan full content, SVG, exports, OOXML,
 * absolute paths, and unbounded process output. Enforces per-entry and total size limits.
 * </p>
 */
@Component
public class BeautifyPptxDiagnosticBundleBuilder {

    static final int MAX_ENTRY_BYTES = 256 * 1024;
    static final int MAX_ZIP_BYTES = 2 * 1024 * 1024;
    static final int MAX_EVENT_MESSAGE_CHARS = 240;
    static final String DIAGNOSTICS_DIR = "diagnostics";
    static final String BUNDLE_FILE = "beautify-pptx-diagnostics.zip";
    static final String READINESS_MARKER = "beautify-pptx/readiness-marker.txt";

    private final ObjectMapper objectMapper;
    private final BeautifyPptxLifecycleStore lifecycleStore;

    @org.springframework.beans.factory.annotation.Autowired
    public BeautifyPptxDiagnosticBundleBuilder(BeautifyPptxLifecycleStore lifecycleStore) {
        this(lifecycleStore, new ObjectMapper());
    }

    BeautifyPptxDiagnosticBundleBuilder(BeautifyPptxLifecycleStore lifecycleStore, ObjectMapper objectMapper) {
        this.lifecycleStore = lifecycleStore;
        this.objectMapper = objectMapper;
    }

    public record DiagnosticBundle(Path path, byte[] bytes) {
    }

    public DiagnosticBundle build(PptJob job, PptMasterProperties properties) {
        if (job.workflowMode() != PptWorkflowMode.BEAUTIFY_PPTX) {
            throw new PptJobStateException("job is not a beautify-pptx workflow");
        }
        Path workspace = normalize(job.workspacePath());
        rejectSymlink(workspace, "job workspace");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        addLifecycleSummary(workspace, entries);
        addReadinessMarker(entries);
        addServiceMeta(entries);
        addAnalysisSummary(job, entries);
        addPlanMeta(job, workspace, entries);
        addReadbackSummary(job, workspace, entries);
        addEventsSummary(job, entries);
        byte[] zipBytes = zipEntries(entries);
        Path bundlePath = persistBundle(workspace, zipBytes);
        return new DiagnosticBundle(bundlePath, zipBytes);
    }

    private void addLifecycleSummary(Path workspace, Map<String, byte[]> entries) {
        lifecycleStore.read(workspace).ifPresent(manifest -> {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("schemaVersion", manifest.schemaVersion());
            summary.put("jobId", manifest.jobId().toString());
            summary.put("createdAt", manifest.createdAt().toString());
            if (manifest.terminalAt() != null) {
                summary.put("terminalAt", manifest.terminalAt().toString());
            }
            if (manifest.retentionDeadline() != null) {
                summary.put("retentionDeadline", manifest.retentionDeadline().toString());
            }
            summary.put("cleanupState", manifest.cleanupState().name());
            ObjectNode counts = summary.putObject("artifactCounts");
            manifest.artifactCounts().forEach(counts::put);
            putEntry(entries, "lifecycle/lifecycle-summary.json", summary);
        });
    }

    private void addReadinessMarker(Map<String, byte[]> entries) {
        try {
            ClassPathResource resource = new ClassPathResource(READINESS_MARKER);
            if (!resource.exists()) {
                return;
            }
            byte[] bytes = resource.getInputStream().readAllBytes();
            putRawEntry(entries, "readiness/readiness-marker.txt", bytes);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read readiness marker");
        }
    }

    private void addServiceMeta(Map<String, byte[]> entries) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("expectedUpstreamVersion", BeautifyPptxReadinessContributor.EXPECTED_UPSTREAM_VERSION);
        meta.put("compatibilityMatrix", "beautify-pptx-compatibility-matrix");
        putEntry(entries, "service/service-meta.json", meta);
    }

    private void addAnalysisSummary(PptJob job, Map<String, byte[]> entries) {
        var analyzed = job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED);
        if (analyzed == null || analyzed.summary() == null || analyzed.summary().isEmpty()) {
            return;
        }
        Map<String, Object> summary = analyzed.summary();
        ObjectNode safe = objectMapper.createObjectNode();
        copyNumber(summary, safe, "sourcePageCount");
        copyNumber(summary, safe, "imageCount");
        copyNumber(summary, safe, "tableCount");
        copyNumber(summary, safe, "chartCount");
        copyNumber(summary, safe, "complexObjectAlertCount");
        if (summary.get("resolvedFormat") instanceof String format && !format.isBlank()) {
            safe.put("resolvedFormat", redactText(format));
        }
        if (summary.get("imageManifestReady") instanceof Boolean ready) {
            safe.put("imageManifestReady", ready);
        }
        if (safe.isEmpty()) {
            return;
        }
        putEntry(entries, "analysis/analysis-summary.json", safe);
    }

    private void addPlanMeta(PptJob job, Path workspace, Map<String, byte[]> entries) {
        Path planPath = resolvePlanPath(job, workspace);
        if (planPath == null || !Files.isRegularFile(planPath)) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("status", redactText(job.beautifyPlanStatus() == null ? "" : job.beautifyPlanStatus()));
            if (!fallback.path("status").asText().isBlank()) {
                putEntry(entries, "analysis/beautify_plan.meta.json", fallback);
            }
            return;
        }
        rejectSymlink(planPath, "beautify plan");
        ensureInsideWorkspace(workspace, planPath);
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(planPath));
            ObjectNode safe = objectMapper.createObjectNode();
            safe.put("version", root.path("version").asInt());
            safe.put("digest", redactText(root.path("digest").asText("")));
            safe.put("status", redactText(root.path("status").asText("")));
            safe.put("sourcePageCount", root.path("sourcePageCount").asInt());
            putEntry(entries, "analysis/beautify_plan.meta.json", safe);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read beautify plan meta");
        }
    }

    private Path resolvePlanPath(PptJob job, Path workspace) {
        return job.projectPath()
                .map(project -> project.resolve("analysis/beautify_plan.draft.json").normalize())
                .filter(path -> path.startsWith(normalize(workspace)))
                .orElse(null);
    }

    private void addReadbackSummary(PptJob job, Path workspace, Map<String, byte[]> entries) {
        Path reportPath = workspace.resolve("validation/beautify-readback.json").normalize();
        if (!Files.isRegularFile(reportPath) && job.projectPath().isPresent()) {
            Path projectReport = job.projectPath().get().resolve("validation/beautify-readback.json").normalize();
            if (Files.isRegularFile(projectReport)) {
                reportPath = projectReport;
            }
        }
        if (!Files.isRegularFile(reportPath)) {
            return;
        }
        rejectSymlink(reportPath, "readback report");
        ensureInsideWorkspace(workspace, reportPath);
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(reportPath));
            ObjectNode safe = objectMapper.createObjectNode();
            safe.put("schema", redactText(root.path("schema").asText("")));
            safe.put("status", redactText(root.path("status").asText("")));
            safe.put("planVersion", root.path("planVersion").asInt());
            safe.put("planDigest", redactText(root.path("planDigest").asText("")));
            copyIfPresent(root, safe, "sourcePageCount", "exportPageCount", "warningCount", "errorCount");
            safe.set("warnings", boundedCodes(root.path("warnings")));
            safe.set("errors", boundedCodes(root.path("errors")));
            putEntry(entries, "validation/beautify-readback-summary.json", safe);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read readback report");
        }
    }

    private void addEventsSummary(PptJob job, Map<String, byte[]> entries) {
        List<PptJobEvent> events = job.events();
        if (events.isEmpty()) {
            return;
        }
        ArrayNode array = objectMapper.createArrayNode();
        for (PptJobEvent event : events) {
            ObjectNode item = array.addObject();
            item.put("type", event.type().name());
            item.put("message", redactText(truncate(event.message(), MAX_EVENT_MESSAGE_CHARS)));
        }
        putEntry(entries, "events-summary.json", array);
    }

    private ArrayNode boundedCodes(JsonNode array) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode node : array) {
            ObjectNode item = out.addObject();
            item.put("code", redactText(node.path("code").asText("UNKNOWN")));
        }
        return out;
    }

    private static void copyNumber(Map<String, Object> source, ObjectNode target, String field) {
        Object value = source.get(field);
        if (value instanceof Number number) {
            target.put(field, number.intValue());
        }
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field) && source.get(field).canConvertToInt()) {
                target.put(field, source.get(field).asInt());
            }
        }
    }

    private byte[] zipEntries(Map<String, byte[]> entries) {
        if (entries.isEmpty()) {
            throw new PptJobStateException("diagnostic bundle has no allowed entries");
        }
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            byte[] bytes = buffer.toByteArray();
            if (bytes.length > MAX_ZIP_BYTES) {
                throw new PptJobStateException("diagnostic bundle exceeds total size limit");
            }
            return bytes;
        } catch (IOException ex) {
            throw new PptJobStateException("failed to build diagnostic bundle");
        }
    }

    private Path persistBundle(Path workspace, byte[] zipBytes) {
        Path diagnosticsDir = workspace.resolve(DIAGNOSTICS_DIR).normalize();
        ensureInsideWorkspace(workspace, diagnosticsDir);
        rejectSymlink(diagnosticsDir, "diagnostics directory");
        try {
            Files.createDirectories(diagnosticsDir);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to create diagnostics directory");
        }
        Path bundlePath = diagnosticsDir.resolve(BUNDLE_FILE).normalize();
        ensureInsideWorkspace(workspace, bundlePath);
        rejectSymlink(bundlePath, "diagnostic bundle");
        atomicWrite(bundlePath, zipBytes);
        return bundlePath;
    }

    private void putEntry(Map<String, byte[]> entries, String name, JsonNode node) {
        try {
            byte[] bytes = (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            putRawEntry(entries, name, bytes);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to serialize diagnostic entry: " + name);
        }
    }

    private void putRawEntry(Map<String, byte[]> entries, String name, byte[] bytes) {
        if (bytes.length > MAX_ENTRY_BYTES) {
            throw new PptJobStateException("diagnostic entry exceeds size limit: " + name);
        }
        String rendered = new String(bytes, StandardCharsets.UTF_8);
        if (containsAbsolutePath(rendered)) {
            throw new PptJobStateException("diagnostic entry contains absolute paths: " + name);
        }
        entries.put(name, bytes);
    }

    static String redactText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = value.replace('\\', '/');
        if (containsAbsolutePath(sanitized)) {
            return "[REDACTED_PATH]";
        }
        return truncate(sanitized, MAX_EVENT_MESSAGE_CHARS);
    }

    static boolean containsAbsolutePath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.matches("(?i)[a-z]:/.*")) {
            return true;
        }
        return value.contains("/home/")
                || value.contains("/Users/")
                || value.contains("/var/")
                || value.contains("/tmp/")
                || value.contains("beautify_identity")
                || value.contains("beautify_inventory")
                || value.contains("beautify_plan.draft.json")
                || value.contains("/images/")
                || value.contains("/sources/")
                || value.contains("/pages/")
                || value.endsWith(".pptx")
                || value.endsWith(".svg");
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    static void ensureInsideWorkspace(Path workspace, Path target) {
        Path normalizedWorkspace = normalize(workspace);
        Path normalizedTarget = normalize(target);
        if (!normalizedTarget.startsWith(normalizedWorkspace)) {
            throw new PptJobStateException("diagnostic path escapes job workspace");
        }
    }

    static void rejectSymlink(Path path, String label) {
        if (Files.exists(path) && Files.isSymbolicLink(path)) {
            throw new PptJobStateException(label + " must not be a symbolic link");
        }
    }

    private void atomicWrite(Path target, byte[] bytes) {
        Path parent = target.getParent();
        Path temporaryPath = parent.resolve(target.getFileName() + ".tmp").normalize();
        try {
            Files.write(temporaryPath, bytes);
            try {
                Files.move(temporaryPath, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException ignored) {
                // preserve original failure
            }
            throw new PptJobStateException("failed to store diagnostic bundle");
        }
    }
}
