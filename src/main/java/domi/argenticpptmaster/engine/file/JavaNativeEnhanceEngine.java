// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.NativeEnhanceEngine;
import domi.argenticpptmaster.engine.ooxml.MarkdownNotes;
import domi.argenticpptmaster.engine.ooxml.NotesApplier;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Java native-enhance subset focused on the notes material path.
 *
 * <p>Supports notes-only {@code apply} and notes-material readiness for {@code validate}.
 * {@code init} and apply paths that include audio / timings / transitions are not implemented;
 * production wiring must keep Python as the authority via {@code RoutingNativeEnhanceEngine}.</p>
 */
public final class JavaNativeEnhanceEngine implements NativeEnhanceEngine {

    public static final String SKIP_PREFIX = "[JAVA-SUBSET] skip:";
    public static final String SUBSET_HEADER = "[JAVA-SUBSET] native-enhance notes";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> WRITABLE_MODULES = Set.of("notes", "audio", "timings", "transitions");

    @Override
    public EngineResult init(
            Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
        return new EngineResult(0, SKIP_PREFIX + " init not implemented in Java subset", false);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        try {
            JsonNode projectJson = readJson(project.resolve("project.json"));
            if (projectJson == null || !isNativeProject(projectJson)) {
                return new EngineResult(1, "error: not a native PPTX enhancement project: " + project, false);
            }
            Set<String> modules = enabledModules(readPlan(project));
            if (!isNotesOnly(modules)) {
                return new EngineResult(0, SKIP_PREFIX + " validate beyond notes-only materials", false);
            }
            if (!hasRequiredProjectPaths(projectJson)) {
                return new EngineResult(0, SKIP_PREFIX + " project.json missing source/notes paths", false);
            }
            // Notes-material readiness only (safe subset of validate --materials notes).
            Path sourcePptx = project.resolve(text(projectJson, "source_pptx"));
            Path notesDir = project.resolve(text(projectJson, "notes_dir"));
            if (!Files.isRegularFile(sourcePptx)) {
                return new EngineResult(0, SKIP_PREFIX + " source pptx missing for dual-run", false);
            }
            Path extractDir = Files.createTempDirectory("java-native-enhance-validate-");
            try {
                PptxPackage.extract(sourcePptx, extractDir);
                List<SlidePartRef> slides = SlideParts.read(extractDir);
                List<String> missing = new ArrayList<>();
                List<String> invalid = new ArrayList<>();
                int notesCount = 0;
                for (SlidePartRef slide : slides) {
                    Optional<Path> note = NotesApplier.resolveNotePath(notesDir, slide.index());
                    if (note.isEmpty()) {
                        missing.add(String.valueOf(slide.index()));
                        continue;
                    }
                    String plain = MarkdownNotes.toPlainText(Files.readString(note.get()));
                    if (plain.isEmpty()) {
                        invalid.add("slide " + slide.index() + ": " + note.get().getFileName() + " has no spoken text");
                    } else {
                        notesCount++;
                    }
                }
                StringBuilder report = new StringBuilder(SUBSET_HEADER).append('\n');
                report.append("notes_count=").append(notesCount).append('\n');
                if (!missing.isEmpty()) {
                    report.append("missing notes for slide(s): ").append(String.join(", ", missing)).append('\n');
                    return new EngineResult(2, report.toString(), false);
                }
                if (!invalid.isEmpty()) {
                    report.append("invalid notes: ").append(String.join("; ", invalid)).append('\n');
                    return new EngineResult(2, report.toString(), false);
                }
                report.append("status=passed");
                return new EngineResult(0, report.toString(), false);
            } finally {
                deleteRecursively(extractDir);
            }
        } catch (Exception ex) {
            return new EngineResult(1, SUBSET_HEADER + " validate failed: " + ex.getMessage(), false);
        }
    }

    @Override
    public EngineResult apply(List<String> arguments, Duration timeout) {
        try {
            ApplyRequest request = ApplyRequest.parse(arguments);
            if (request.project() == null) {
                return new EngineResult(1, "error: apply requires a project path", false);
            }
            JsonNode projectJson = readJson(request.project().resolve("project.json"));
            if (projectJson == null || !isNativeProject(projectJson)) {
                return new EngineResult(
                        1, "error: not a native PPTX enhancement project: " + request.project(), false);
            }
            JsonNode plan = readPlan(request.project());
            Set<String> modules = enabledModules(plan);
            if (!isNotesOnly(modules)) {
                return new EngineResult(
                        0, SKIP_PREFIX + " apply modules not notes-only: " + modules, false);
            }
            if (!hasRequiredProjectPaths(projectJson)) {
                return new EngineResult(0, SKIP_PREFIX + " project.json missing source/notes/exports paths", false);
            }
            if (!isPlanConfirmed(plan) && !request.force()) {
                return new EngineResult(
                        1,
                        "error: enhancement plan is not confirmed (Java subset refuses --force-less apply)",
                        false);
            }

            Path sourcePptx = request.project().resolve(text(projectJson, "source_pptx"));
            Path notesDir = request.project().resolve(text(projectJson, "notes_dir"));
            Path exportsDir = request.project().resolve(text(projectJson, "exports_dir"));
            Path output = request.output() != null
                    ? request.output()
                    : exportsDir.resolve(sourcePptx.getFileName().toString().replace(".pptx", "") + "_enhanced.pptx");

            if (Files.exists(output) && !request.overwrite()) {
                return new EngineResult(1, "error: output already exists, pass --overwrite: " + output, false);
            }

            Path extractDir = Files.createTempDirectory("java-native-enhance-apply-");
            try {
                PptxPackage.extract(sourcePptx, extractDir);
                List<SlidePartRef> slides = SlideParts.read(extractDir);
                List<String> missing = new ArrayList<>();
                for (SlidePartRef slide : slides) {
                    if (NotesApplier.resolveNotePath(notesDir, slide.index()).isEmpty()) {
                        missing.add(String.valueOf(slide.index()));
                    }
                }
                if (!missing.isEmpty()) {
                    return new EngineResult(
                            1,
                            "error: missing notes for slide(s): " + String.join(", ", missing),
                            false);
                }
                Set<String> noteParts = NotesApplier.applyNotesFromDirectory(extractDir, notesDir, slides);
                Files.createDirectories(output.getParent());
                Path staging = Files.createTempDirectory(output.getParent(), "java-native-enhance-out-");
                try {
                    Path candidate = staging.resolve(output.getFileName().toString());
                    PptxPackage.zipDirectory(extractDir, candidate);
                    Files.move(candidate, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    deleteRecursively(staging);
                }
                return new EngineResult(
                        0,
                        SUBSET_HEADER + " applied notes parts=" + noteParts.size() + " output=" + output,
                        false);
            } finally {
                deleteRecursively(extractDir);
            }
        } catch (Exception ex) {
            return new EngineResult(1, SUBSET_HEADER + " apply failed: " + ex.getMessage(), false);
        }
    }

    public static boolean isNotesOnlyEligible(Path project) {
        try {
            Set<String> modules = enabledModules(readPlan(project));
            return isNotesOnly(modules);
        } catch (Exception ex) {
            return false;
        }
    }

    public static boolean isSkip(EngineResult result) {
        return result != null && result.output() != null && result.output().startsWith(SKIP_PREFIX);
    }

    static Set<String> enabledModules(JsonNode plan) {
        if (plan == null || !plan.path("modules").isObject()) {
            return new LinkedHashSet<>(WRITABLE_MODULES);
        }
        Set<String> enabled = new LinkedHashSet<>();
        JsonNode modules = plan.get("modules");
        for (String name : WRITABLE_MODULES) {
            JsonNode config = modules.get(name);
            if (config != null && config.path("enabled").asBoolean(false)) {
                enabled.add(name);
            }
        }
        return enabled;
    }

    private static boolean isNotesOnly(Set<String> modules) {
        return modules.equals(Set.of("notes"));
    }

    private static boolean isPlanConfirmed(JsonNode plan) {
        return plan != null && "confirmed".equals(plan.path("status").asText());
    }

    private static boolean isNativeProject(JsonNode projectJson) {
        String schema = projectJson.path("schema").asText("");
        return "native_pptx_enhancement_project.v1".equals(schema)
                || "native_narration_pptx_project.v1".equals(schema);
    }

    private static boolean hasRequiredProjectPaths(JsonNode projectJson) {
        return hasText(projectJson, "source_pptx")
                && hasText(projectJson, "notes_dir")
                && hasText(projectJson, "exports_dir");
    }

    private static boolean hasText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value != null && !value.isBlank();
    }

    private static JsonNode readPlan(Path project) throws IOException {
        Path plan = project.resolve("analysis/enhancement_plan.json");
        if (!Files.isRegularFile(plan)) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(plan.toFile());
    }

    private static JsonNode readJson(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return MAPPER.readTree(path.toFile());
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("project.json missing " + field);
        }
        return value;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    record ApplyRequest(Path project, Path output, boolean overwrite, boolean force) {
        static ApplyRequest parse(List<String> arguments) {
            Path project = null;
            Path output = null;
            boolean overwrite = false;
            boolean force = false;
            for (int i = 0; i < arguments.size(); i++) {
                String arg = arguments.get(i);
                if ("apply".equals(arg)) {
                    continue;
                }
                if ("--overwrite".equals(arg)) {
                    overwrite = true;
                    continue;
                }
                if ("--force".equals(arg)) {
                    force = true;
                    continue;
                }
                if ("--output".equals(arg) || "-o".equals(arg)) {
                    if (i + 1 < arguments.size()) {
                        output = Path.of(arguments.get(++i));
                    }
                    continue;
                }
                if (arg.startsWith("-")) {
                    // Skip unknown flags and their values for forward compatibility.
                    if (i + 1 < arguments.size() && !arguments.get(i + 1).startsWith("-")) {
                        i++;
                    }
                    continue;
                }
                if (project == null) {
                    project = Path.of(arg);
                }
            }
            return new ApplyRequest(project, output, overwrite, force);
        }
    }
}
