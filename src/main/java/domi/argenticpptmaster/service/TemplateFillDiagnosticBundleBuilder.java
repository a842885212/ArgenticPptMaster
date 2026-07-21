package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.domain.TemplateFillAnalysisSummary;
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
 * Builds a whitelisted, redacted diagnostic ZIP for template-fill jobs.
 * <p>
 * Excludes uploads, exports, full plans, raw OOXML, identities, absolute paths
 * and unbounded process output. Enforces per-entry and total size limits.
 * </p>
 */
@Component
public class TemplateFillDiagnosticBundleBuilder {

    static final int MAX_ENTRY_BYTES = 256 * 1024;
    static final int MAX_ZIP_BYTES = 2 * 1024 * 1024;
    static final int MAX_EVENT_MESSAGE_CHARS = 240;
    static final String DIAGNOSTICS_DIR = "diagnostics";
    static final String BUNDLE_FILE = "template-fill-diagnostics.zip";
    static final String READINESS_MARKER = "template-fill/readiness-marker.txt";

    private final ObjectMapper objectMapper;
    private final TemplateFillLifecycleStore lifecycleStore;
    private final PptTemplateFillAnalysisReader analysisReader;

    @org.springframework.beans.factory.annotation.Autowired
    public TemplateFillDiagnosticBundleBuilder(
            TemplateFillLifecycleStore lifecycleStore,
            PptTemplateFillAnalysisReader analysisReader) {
        this(lifecycleStore, analysisReader, new ObjectMapper());
    }

    TemplateFillDiagnosticBundleBuilder(
            TemplateFillLifecycleStore lifecycleStore,
            PptTemplateFillAnalysisReader analysisReader,
            ObjectMapper objectMapper) {
        this.lifecycleStore = lifecycleStore;
        this.analysisReader = analysisReader;
        this.objectMapper = objectMapper;
    }

    public record DiagnosticBundle(Path path, byte[] bytes) {
    }

    public DiagnosticBundle build(PptJob job, PptMasterProperties properties) {
        if (job.workflowMode() != PptWorkflowMode.TEMPLATE_FILL) {
            throw new PptJobStateException("job is not a template-fill workflow");
        }
        Path workspace = normalize(job.workspacePath());
        rejectSymlink(workspace, "job workspace");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        addLifecycleSummary(workspace, entries);
        addReadinessMarker(entries);
        addAnalysisSummary(job, workspace, entries);
        addPlanMeta(workspace, entries);
        addServiceMeta(workspace, entries);
        addReadbackSummary(workspace, entries);
        addCheckReportSummary(workspace, entries);
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

    private void addAnalysisSummary(PptJob job, Path workspace, Map<String, byte[]> entries) {
        Path slideLibrary = workspace.resolve("analysis/template.slide_library.json").normalize();
        ensureInsideWorkspace(workspace, slideLibrary);
        ObjectNode summary = objectMapper.createObjectNode();
        TemplateFillAnalysisSummary inMemory = job.templateAnalysisSummary().orElse(null);
        if (inMemory != null) {
            summary.put("templateSlideCount", inMemory.templateSlideCount());
            summary.put("widthPx", inMemory.widthPx());
            summary.put("heightPx", inMemory.heightPx());
            if (inMemory.formatLabel() != null) {
                summary.put("formatLabel", inMemory.formatLabel());
            }
            summary.put("textSlotCount", inMemory.textSlotCount());
            summary.put("tableCount", inMemory.tableCount());
            summary.put("chartCount", inMemory.chartCount());
            summary.put("analysisVersion", inMemory.analysisVersion());
        } else if (Files.isRegularFile(slideLibrary)) {
            rejectSymlink(slideLibrary, "slide library");
            TemplateFillAnalysisSummary derived = analysisReader.readSummary(slideLibrary);
            summary.put("templateSlideCount", derived.templateSlideCount());
            summary.put("widthPx", derived.widthPx());
            summary.put("heightPx", derived.heightPx());
            if (derived.formatLabel() != null) {
                summary.put("formatLabel", derived.formatLabel());
            }
            summary.put("textSlotCount", derived.textSlotCount());
            summary.put("tableCount", derived.tableCount());
            summary.put("chartCount", derived.chartCount());
            summary.put("analysisVersion", derived.analysisVersion());
        } else {
            return;
        }
        putEntry(entries, "analysis/analysis-summary.json", summary);
    }

    private void addPlanMeta(Path workspace, Map<String, byte[]> entries) {
        Path metaPath = workspace.resolve("analysis/fill_plan.meta.json").normalize();
        if (!Files.isRegularFile(metaPath)) {
            return;
        }
        rejectSymlink(metaPath, "fill plan meta");
        ensureInsideWorkspace(workspace, metaPath);
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(metaPath));
            ObjectNode safe = objectMapper.createObjectNode();
            safe.put("version", root.path("version").asInt());
            safe.put("digest", redactText(root.path("digest").asText("")));
            safe.put("status", redactText(root.path("status").asText("")));
            putEntry(entries, "analysis/fill_plan.meta.json", safe);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read fill plan meta");
        }
    }

    private void addServiceMeta(Path workspace, Map<String, byte[]> entries) {
        Path serviceMetaPath = workspace.resolve("analysis/fill_plan.service-meta.json").normalize();
        if (!Files.isRegularFile(serviceMetaPath)) {
            return;
        }
        rejectSymlink(serviceMetaPath, "fill plan service meta");
        ensureInsideWorkspace(workspace, serviceMetaPath);
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(serviceMetaPath));
            ObjectNode safe = objectMapper.createObjectNode();
            safe.put("schema", redactText(root.path("schema").asText("")));
            safe.put("version", root.path("version").asInt());
            if (root.has("constraints")) {
                safe.set("constraints", root.get("constraints").deepCopy());
            }
            ArrayNode decisions = safe.putArray("capacityDecisions");
            JsonNode sourceDecisions = root.path("capacityDecisions");
            if (sourceDecisions.isArray()) {
                for (JsonNode decision : sourceDecisions) {
                    ObjectNode item = decisions.addObject();
                    item.put("sourceSlide", decision.path("source_slide").asInt(decision.path("sourceSlide").asInt(0)));
                    item.put("slotId", redactText(decision.path("slot_id").asText(decision.path("slotId").asText(""))));
                    item.put("selected", redactText(decision.path("selected").asText("")));
                    ArrayNode evaluated = item.putArray("evaluated");
                    if (decision.path("evaluated").isArray()) {
                        for (JsonNode strategy : decision.path("evaluated")) {
                            evaluated.add(redactText(strategy.asText("")));
                        }
                    }
                }
            }
            putEntry(entries, "analysis/fill_plan.service-meta.json", safe);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read fill plan service meta");
        }
    }

    private void addReadbackSummary(Path workspace, Map<String, byte[]> entries) {
        Path reportPath = workspace.resolve("validation/template-fill-readback.json").normalize();
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
            copyIfPresent(root, safe, "plannedNotesCount", "actualNotesCount", "plannedChartMappings",
                    "chartPartCount", "plannedTableMappings", "tableMarkerCount", "plannedTransitions",
                    "transitionMarkerCount", "timingMarkerCount", "actualSlideCount");
            safe.set("warnings", boundedCodes(root.path("warnings")));
            safe.set("errors", boundedCodes(root.path("errors")));
            putEntry(entries, "validation/template-fill-readback-summary.json", safe);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read readback report");
        }
    }

    private void addCheckReportSummary(Path workspace, Map<String, byte[]> entries) {
        Path reportPath = workspace.resolve("analysis/check_report.json").normalize();
        if (!Files.isRegularFile(reportPath)) {
            return;
        }
        rejectSymlink(reportPath, "check report");
        ensureInsideWorkspace(workspace, reportPath);
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(reportPath));
            ObjectNode safe = objectMapper.createObjectNode();
            JsonNode summary = root.path("summary");
            if (summary.isObject()) {
                ObjectNode bounded = safe.putObject("summary");
                copyIfPresent(summary, bounded, "warn", "error", "info");
            }
            putEntry(entries, "validation/check-report-summary.json", safe);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read check report");
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
                || value.matches(".*/uploads/(template|content)/.*")
                || value.contains("fill_plan.json")
                || value.endsWith(".pptx");
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
