package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * Whitelist-only diagnostic ZIP builder for native-enhance-pptx jobs.
 * <p>
 * Excludes source PPTX, audio/media bytes, notes bodies, absolute paths, command stdout,
 * and OOXML. Enforces per-entry and total size limits.
 * </p>
 */
@Component
public class NativeEnhancePptxDiagnosticBundleBuilder {

    static final int MAX_ENTRY_BYTES = 256 * 1024;
    static final int MAX_ZIP_BYTES = 2 * 1024 * 1024;
    static final int MAX_EVENT_MESSAGE_CHARS = 240;
    static final String DIAGNOSTICS_DIR = "diagnostics";
    static final String BUNDLE_FILE = "native-enhance-pptx-diagnostics.zip";

    private final ObjectMapper objectMapper;
    private final NativeEnhancePptxLifecycleStore lifecycleStore;

    @org.springframework.beans.factory.annotation.Autowired
    public NativeEnhancePptxDiagnosticBundleBuilder(NativeEnhancePptxLifecycleStore lifecycleStore) {
        this(lifecycleStore, new ObjectMapper());
    }

    NativeEnhancePptxDiagnosticBundleBuilder(NativeEnhancePptxLifecycleStore lifecycleStore, ObjectMapper objectMapper) {
        this.lifecycleStore = lifecycleStore;
        this.objectMapper = objectMapper;
    }

    public record DiagnosticBundle(Path path, byte[] bytes) {
    }

    public DiagnosticBundle build(PptJob job, PptMasterProperties properties) {
        if (job.workflowMode() != PptWorkflowMode.NATIVE_ENHANCE_PPTX) {
            throw new PptJobStateException("job is not a native-enhance-pptx workflow");
        }
        Path workspace = normalize(job.workspacePath());
        rejectSymlink(workspace, "job workspace");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        addLifecycleSummary(workspace, entries);
        addServiceMeta(entries);
        addNodeSummaries(job, entries);
        addReadbackMeta(job, workspace, entries);
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

    private void addServiceMeta(Map<String, byte[]> entries) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("expectedUpstreamVersion", NativeEnhanceReadinessContributor.EXPECTED_UPSTREAM_VERSION);
        meta.put("compatibilityMatrix", "native-enhance-pptx-compatibility-matrix");
        meta.put("thresholdsVersion", "2026.08-stage5-native-enhance");
        putEntry(entries, "service/service-meta.json", meta);
    }

    private void addNodeSummaries(PptJob job, Map<String, byte[]> entries) {
        ObjectNode nodes = objectMapper.createObjectNode();
        for (PptJobNode node : new PptJobNode[] {
                PptJobNode.NATIVE_ENHANCE_INITIALIZED,
                PptJobNode.ENHANCEMENT_PLAN_DRAFTED,
                PptJobNode.ENHANCEMENT_PLAN_CONFIRMED,
                PptJobNode.NOTES_GENERATED,
                PptJobNode.AUDIO_VOICE_CONFIRMED,
                PptJobNode.AUDIO_GENERATED,
                PptJobNode.NATIVE_ENHANCE_VALIDATED,
                PptJobNode.PPT_EXPORTED,
                PptJobNode.NATIVE_ENHANCE_OUTPUT_VALIDATED
        }) {
            var execution = job.nodeExecution(node);
            if (execution == null) {
                continue;
            }
            ObjectNode item = nodes.putObject(node.name());
            item.put("status", execution.status() == null ? "" : execution.status().name());
            if (execution.summary() != null) {
                execution.summary().forEach((k, v) -> {
                    if (v != null && isSafeSummaryKey(k)) {
                        item.put(k, String.valueOf(v));
                    }
                });
            }
        }
        putEntry(entries, "nodes/node-summaries.json", nodes);
    }

    private void addReadbackMeta(PptJob job, Path workspace, Map<String, byte[]> entries) {
        Path report = workspace.resolve("validation").resolve("native-enhance-readback.json");
        if (!Files.isRegularFile(report) || Files.isSymbolicLink(report)) {
            return;
        }
        try {
            ObjectNode meta = objectMapper.createObjectNode();
            meta.put("present", true);
            meta.put("bytes", Files.size(report));
            // Do not embed report body — may contain content excerpts.
            putEntry(entries, "validation/readback-meta.json", meta);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to inspect readback report");
        }
    }

    private void addEventsSummary(PptJob job, Map<String, byte[]> entries) {
        ObjectNode root = objectMapper.createObjectNode();
        var array = root.putArray("events");
        for (PptJobEvent event : job.events()) {
            ObjectNode item = array.addObject();
            item.put("type", event.type() == null ? "" : event.type().name());
            String message = event.message() == null ? "" : event.message();
            if (message.length() > MAX_EVENT_MESSAGE_CHARS) {
                message = message.substring(0, MAX_EVENT_MESSAGE_CHARS);
            }
            item.put("message", redactPaths(message));
            if (event.data() != null) {
                event.data().forEach((k, v) -> {
                    if (v != null && isSafeSummaryKey(k)) {
                        item.put(k, redactPaths(String.valueOf(v)));
                    }
                });
            }
        }
        putEntry(entries, "events/events-summary.json", root);
    }

    private static boolean isSafeSummaryKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase();
        return !(lower.contains("path")
                || lower.contains("token")
                || lower.contains("stdout")
                || lower.contains("stderr")
                || lower.contains("note")
                || lower.contains("audio")
                || lower.contains("transcript")
                || lower.contains("subject")
                || lower.contains("tenant"));
    }

    private static String redactPaths(String value) {
        return value.replaceAll("(/[^\\s]+)+", "[path]");
    }

    private void putEntry(Map<String, byte[]> entries, String name, ObjectNode node) {
        try {
            byte[] bytes = (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            putRawEntry(entries, name, bytes);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to serialize diagnostic entry");
        }
    }

    private void putRawEntry(Map<String, byte[]> entries, String name, byte[] bytes) {
        if (bytes.length > MAX_ENTRY_BYTES) {
            throw new PptJobStateException("diagnostic entry exceeds size limit: " + name);
        }
        entries.put(name, bytes);
    }

    private byte[] zipEntries(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    ZipEntry zipEntry = new ZipEntry(entry.getKey());
                    zip.putNextEntry(zipEntry);
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
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
        Path diagnostics = workspace.resolve(DIAGNOSTICS_DIR).normalize();
        ensureInsideWorkspace(workspace, diagnostics);
        rejectSymlink(diagnostics, "diagnostics directory");
        Path target = diagnostics.resolve(BUNDLE_FILE).normalize();
        ensureInsideWorkspace(workspace, target);
        rejectSymlink(target, "diagnostics bundle");
        Path temporary = diagnostics.resolve(BUNDLE_FILE + ".tmp").normalize();
        try {
            Files.createDirectories(diagnostics);
            Files.write(temporary, zipBytes);
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // preserve original failure
            }
            throw new PptJobStateException("failed to persist diagnostic bundle");
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void ensureInsideWorkspace(Path workspace, Path target) {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedWorkspace)) {
            throw new PptJobStateException("diagnostic path escapes job workspace");
        }
    }

    private static void rejectSymlink(Path path, String label) {
        if (Files.exists(path) && Files.isSymbolicLink(path)) {
            throw new PptJobStateException(label + " must not be a symbolic link");
        }
    }
}
