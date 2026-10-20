// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.nativeenhance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.ooxml.NotesApplier;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import domi.argenticpptmaster.engine.ooxml.SlidePartRef;
import domi.argenticpptmaster.engine.ooxml.SlideParts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Init / validate / apply orchestration for {@code JavaNativeEnhanceEngine}. */
public final class NativeEnhanceOps {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SLUG = Pattern.compile("[^0-9A-Za-z._-]+");

    private NativeEnhanceOps() {}

    public static EngineResult init(Path sourcePptx, String name, Path projectDir, Path projectsRoot) {
        try {
            Path source = sourcePptx.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pptx")) {
                return new EngineResult(1, "error: expected an existing .pptx file: " + source, false);
            }
            // Light delivery: package must open and expose slides.
            Path probeDir = Files.createTempDirectory("java-native-enhance-init-probe-");
            List<SlidePartRef> slideParts;
            try {
                PptxPackage.extract(source, probeDir);
                slideParts = SlideParts.read(probeDir);
            } catch (Exception ex) {
                return new EngineResult(1, "error: source PPTX failed delivery/open check: " + ex.getMessage(), false);
            } finally {
                deleteRecursively(probeDir);
            }

            String stem = sanitizeSlug(name != null && !name.isBlank() ? name : stripPptx(source.getFileName().toString()));
            Path projectPath = projectDir != null
                    ? projectDir.toAbsolutePath().normalize()
                    : projectsRoot.toAbsolutePath().normalize()
                            .resolve(stem + "_native_enhance_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
            if (Files.exists(projectPath)) {
                try (var children = Files.list(projectPath)) {
                    if (children.findAny().isPresent()) {
                        return new EngineResult(
                                1,
                                "error: project directory already exists and is not empty: " + projectPath,
                                false);
                    }
                }
            }
            for (String dirname : List.of("sources", "analysis", "notes", "audio", "exports", "validation")) {
                Files.createDirectories(projectPath.resolve(dirname));
            }

            Path archived = projectPath.resolve("sources").resolve(source.getFileName().toString());
            String importMode = archiveSource(source, archived, projectsRoot.toAbsolutePath().normalize());

            // Markdown is produced by NotesAudioGateway / readback elsewhere; write a stub for layout parity.
            Path sourceMd = projectPath.resolve("sources").resolve(stripPptx(source.getFileName().toString()) + ".md");
            Files.writeString(
                    sourceMd,
                    "# " + stripPptx(source.getFileName().toString()) + "\n\n"
                            + "Slides: " + slideParts.size() + "\n",
                    StandardCharsets.UTF_8);

            ObjectNode slideIndex = MAPPER.createObjectNode();
            slideIndex.put("schema", NativeEnhanceConstants.SLIDE_INDEX_SCHEMA);
            slideIndex.put("source_pptx", "sources/" + source.getFileName());
            slideIndex.put("slide_count", slideParts.size());
            ArrayNode slides = slideIndex.putArray("slides");
            for (SlidePartRef slide : slideParts) {
                ObjectNode row = slides.addObject();
                row.put("index", slide.index());
                row.put("note_file", "notes/" + String.format("%03d.md", slide.index()));
                row.put("audio_stem", String.format("%03d", slide.index()));
                row.put("part_name", slide.partName());
                row.put("slide_number", slide.slideNumber());
            }
            writeJson(projectPath.resolve("analysis/slide_index.json"), slideIndex);

            ObjectNode project = MAPPER.createObjectNode();
            project.put("schema", NativeEnhanceConstants.PROJECT_SCHEMA);
            project.put("kind", "native_pptx_enhancement");
            project.putArray("modules")
                    .add("notes")
                    .add("audio")
                    .add("timings")
                    .add("transitions")
                    .add("delivery.check");
            project.put("source_pptx", "sources/" + source.getFileName());
            project.put("source_markdown", "sources/" + sourceMd.getFileName());
            ObjectNode sourceImport = project.putObject("source_import");
            sourceImport.put("mode", importMode);
            sourceImport.put("original_path", source.toString());
            project.put("source_sha256", sha256(archived));
            project.put("slide_count", slideParts.size());
            ArrayNode roster = project.putArray("slide_part_roster");
            slideParts.forEach(s -> roster.add(s.partName()));
            project.put("notes_dir", "notes");
            project.put("audio_dir", "audio");
            project.put("exports_dir", "exports");
            ObjectNode transition = project.putObject("transition");
            transition.put("effect", "fade");
            transition.put("duration", NativeEnhanceConstants.DEFAULT_TRANSITION_DURATION);
            ObjectNode audio = project.putObject("audio");
            audio.put("provider", "");
            audio.put("voice", "");
            audio.put("rate", "");
            writeJson(projectPath.resolve("project.json"), project);

            ObjectNode plan = EnhancementPlanSupport.buildEnhancementPlan(
                    project,
                    slideParts.size(),
                    0,
                    0,
                    "fade",
                    NativeEnhanceConstants.DEFAULT_TRANSITION_DURATION,
                    NativeEnhanceConstants.DEFAULT_NARRATION_PADDING,
                    NativeEnhanceConstants.DEFAULT_NARRATION_START_FLOOR,
                    false,
                    null);
            writeJson(projectPath.resolve("analysis/enhancement_plan.json"), plan);

            ObjectNode report = MAPPER.createObjectNode();
            report.put("schema", NativeEnhanceConstants.VALIDATION_SCHEMA);
            report.put("status", "passed");
            report.put("phase", "intake");
            report.put("source_delivery_policy", "preserve-baseline");
            ObjectNode delivery = report.putObject("delivery_check");
            delivery.put("status", "passed");
            delivery.put("engine", "java-lite");
            ObjectNode file = delivery.putObject("file");
            file.put("path", archived.toAbsolutePath().toString());
            ObjectNode slidesNode = delivery.putObject("slides");
            slidesNode.put("count", slideParts.size());
            slidesNode.putArray("hidden");
            writeJson(projectPath.resolve("validation/report.json"), report);

            String output = "Project: " + projectPath + "\nSlides: " + slideParts.size()
                    + "\nSource import: " + importMode
                    + "\nSource markdown: " + sourceMd
                    + "\nDraft enhancement plan: " + projectPath.resolve("analysis/enhancement_plan.json");
            return new EngineResult(0, output, false);
        } catch (Exception ex) {
            return new EngineResult(1, "error: init failed: " + ex.getMessage(), false);
        }
    }

    public static EngineResult validate(Path project) {
        try {
            JsonNode projectJson = readJson(project.resolve("project.json"));
            if (projectJson == null || !isNativeProject(projectJson)) {
                return new EngineResult(1, "error: not a native PPTX enhancement project: " + project, false);
            }
            JsonNode plan = EnhancementPlanSupport.loadPlan(project);
            Set<String> modules = EnhancementPlanSupport.enabledModules(plan);
            Path sourcePptx = project.resolve(text(projectJson, "source_pptx"));
            Path notesDir = project.resolve(text(projectJson, "notes_dir"));
            Path audioDir = project.resolve(text(projectJson, "audio_dir"));

            DeliveryLite delivery = DeliveryLite.audit(sourcePptx);
            if (!delivery.errors().isEmpty()) {
                ObjectNode report = writePreflight(
                        project, plan, modules, "failed", Map.of("fatal_delivery_errors", delivery.errors(), "delivery_check", delivery.toJson()));
                return new EngineResult(1, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report), false);
            }

            Path extractDir = Files.createTempDirectory("java-native-enhance-validate-");
            List<SlidePartRef> slides;
            Map<Integer, List<String>> existingCarriers;
            try {
                PptxPackage.extract(sourcePptx, extractDir);
                slides = SlideParts.read(extractDir);
                existingCarriers = modules.contains("audio")
                        ? AudioApplier.nativeAudioCarriers(extractDir, slides)
                        : Map.of();
            } finally {
                deleteRecursively(extractDir);
            }

            List<String> sourceErrors = sourceStateErrors(project, projectJson, sourcePptx, slides);
            List<String> planErrors = new ArrayList<>();
            try {
                EnhancementPlanSupport.validatePlanModules(plan, false);
            } catch (IllegalArgumentException ex) {
                planErrors.add(ex.getMessage());
            }
            List<String> transitionErrors = new ArrayList<>();
            EnhancementPlanSupport.ResolvedTransitionPlan resolved = null;
            if (planErrors.isEmpty()) {
                try {
                    resolved = EnhancementPlanSupport.resolveTransitionPlan(projectJson, plan, slides, null, null, false);
                } catch (IllegalArgumentException ex) {
                    transitionErrors.add(ex.getMessage());
                }
            }
            MaterialReadiness readiness =
                    MaterialReadiness.collect(slides, notesDir, audioDir, modules, modules);

            boolean hardFailure = !sourceErrors.isEmpty()
                    || !readiness.moduleErrors().isEmpty()
                    || !planErrors.isEmpty()
                    || !transitionErrors.isEmpty()
                    || !existingCarriers.isEmpty();
            String status;
            if (hardFailure) {
                status = "failed";
            } else if (!readiness.ready()) {
                status = "needs-materials";
            } else {
                status = delivery.hasFindings() ? "passed-with-advisories" : "passed";
            }

            ObjectNode details = MAPPER.createObjectNode();
            details.put("slide_count", slides.size());
            details.put("notes_required", modules.contains("notes"));
            details.put("audio_required", modules.contains("audio") || modules.contains("timings"));
            details.set("plan_errors", toArray(planErrors));
            details.set("transition_errors", toArray(transitionErrors));
            details.put("transition_override_count", resolved == null ? 0 : resolved.slideEnters().size());
            details.set("source_errors", toArray(sourceErrors));
            details.set("existing_native_audio_carriers", MAPPER.valueToTree(existingCarriers));
            details.put("source_delivery_policy", "preserve-baseline");
            details.set("delivery_check", delivery.toJson());
            details.put("notes_count", readiness.notesCount());
            details.put("audio_count", readiness.audioCount());
            details.set("missing_notes", MAPPER.valueToTree(readiness.missingNotes()));
            details.set("invalid_notes", MAPPER.valueToTree(readiness.invalidNotes().keySet().stream().sorted().toList()));
            details.set("invalid_note_reasons", MAPPER.valueToTree(readiness.invalidNotes()));
            details.set("missing_audio", MAPPER.valueToTree(readiness.missingAudio()));
            details.set("invalid_audio", MAPPER.valueToTree(readiness.invalidAudio().keySet().stream().sorted().toList()));
            details.set("invalid_audio_reasons", MAPPER.valueToTree(readiness.invalidAudio()));
            details.set("module_errors", toArray(readiness.moduleErrors()));

            ObjectNode report = writePreflight(project, plan, modules, status, details);
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            if (hardFailure) {
                return new EngineResult(1, json, false);
            }
            return new EngineResult(readiness.ready() ? 0 : 2, json, false);
        } catch (Exception ex) {
            return new EngineResult(1, "error: validate failed: " + ex.getMessage(), false);
        }
    }

    public static EngineResult apply(List<String> arguments) {
        try {
            ApplyRequest request = ApplyRequest.parse(arguments);
            if (request.project() == null) {
                return new EngineResult(1, "error: apply requires a project path", false);
            }
            Path project = request.project();
            JsonNode projectJson = readJson(project.resolve("project.json"));
            if (projectJson == null || !isNativeProject(projectJson)) {
                return new EngineResult(1, "error: not a native PPTX enhancement project: " + project, false);
            }
            JsonNode plan = EnhancementPlanSupport.loadPlan(project);
            Set<String> modules = EnhancementPlanSupport.enabledModules(plan);

            writePreflight(project, plan, modules, "running", Map.of());
            if (!EnhancementPlanSupport.planConfirmed(plan) && !request.force()) {
                return failPreflight(
                        project,
                        plan,
                        modules,
                        List.of(
                                "enhancement plan is not confirmed: "
                                        + project.resolve("analysis/enhancement_plan.json")
                                        + " (run plan, get user confirmation, set status to \"confirmed\", or pass --force)"));
            }
            try {
                EnhancementPlanSupport.validatePlanModules(plan, false);
            } catch (IllegalArgumentException ex) {
                return failPreflight(project, plan, modules, List.of(ex.getMessage()));
            }

            Path sourcePptx = project.resolve(text(projectJson, "source_pptx"));
            Path notesDir = project.resolve(text(projectJson, "notes_dir"));
            Path audioDir = project.resolve(text(projectJson, "audio_dir"));
            Path exportsDir = project.resolve(text(projectJson, "exports_dir"));

            DeliveryLite sourceDelivery = DeliveryLite.audit(sourcePptx);
            if (!sourceDelivery.errors().isEmpty()) {
                return failPreflight(project, plan, modules, sourceDelivery.errors());
            }

            double narrationPadding = NativeEnhanceConstants.DEFAULT_NARRATION_PADDING;
            double narrationStartFloor = NativeEnhanceConstants.DEFAULT_NARRATION_START_FLOOR;
            JsonNode timingsCfg = plan.path("modules").path("timings");
            if (request.narrationPadding() != null) {
                narrationPadding = SlideMotionSupport.validateSeconds(request.narrationPadding(), "narration padding", true);
            } else if (timingsCfg.has("narration_padding")) {
                narrationPadding = SlideMotionSupport.validateSeconds(
                        timingsCfg.path("narration_padding").asDouble(), "narration padding", true);
            }
            if (request.narrationStartFloor() != null) {
                narrationStartFloor =
                        SlideMotionSupport.validateSeconds(request.narrationStartFloor(), "narration start floor", true);
            } else if (timingsCfg.has("narration_start_floor")) {
                narrationStartFloor = SlideMotionSupport.validateSeconds(
                        timingsCfg.path("narration_start_floor").asDouble(), "narration start floor", true);
            }

            Path output = request.output() != null
                    ? request.output()
                    : exportsDir.resolve(stripPptx(sourcePptx.getFileName().toString()) + "_enhanced.pptx");
            if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pptx")) {
                return failPreflight(project, plan, modules, List.of("output must use a .pptx extension: " + output));
            }
            if (Files.exists(output) && !request.overwrite()) {
                return failPreflight(
                        project, plan, modules, List.of("output already exists, pass --overwrite: " + output));
            }

            Path extractDir = Files.createTempDirectory("java-native-enhance-apply-");
            try {
                PptxPackage.extract(sourcePptx, extractDir);
                List<SlidePartRef> slides = SlideParts.read(extractDir);
                List<String> sourceErrors = sourceStateErrors(project, projectJson, sourcePptx, slides);
                if (!sourceErrors.isEmpty()) {
                    return failPreflight(project, plan, modules, sourceErrors);
                }

                EnhancementPlanSupport.ResolvedTransitionPlan resolved;
                try {
                    resolved = EnhancementPlanSupport.resolveTransitionPlan(
                            projectJson,
                            plan,
                            slides,
                            request.transition(),
                            request.transitionDuration(),
                            request.applyTransitionWithoutAudio());
                } catch (IllegalArgumentException ex) {
                    return failPreflight(project, plan, modules, List.of(ex.getMessage()));
                }

                MaterialReadiness readiness = MaterialReadiness.collect(slides, notesDir, audioDir, modules, modules);
                if (!readiness.ready()) {
                    ObjectNode report = writePreflight(
                            project,
                            plan,
                            modules,
                            readiness.moduleErrors().isEmpty() ? "needs-materials" : "failed",
                            Map.of(
                                    "notes_count",
                                    readiness.notesCount(),
                                    "audio_count",
                                    readiness.audioCount(),
                                    "missing_notes",
                                    readiness.missingNotes(),
                                    "missing_audio",
                                    readiness.missingAudio()));
                    return new EngineResult(
                            1,
                            "error: " + String.join("; ", readiness.messages()) + "\n" + report,
                            false);
                }

                if (modules.contains("audio")) {
                    Map<Integer, List<String>> existing = AudioApplier.nativeAudioCarriers(extractDir, slides);
                    if (!existing.isEmpty()) {
                        return failPreflight(
                                project,
                                plan,
                                modules,
                                List.of(
                                        "source PPTX already contains native-enhance narration carrier(s); refusing to append duplicate audio: "
                                                + existing));
                    }
                }

                Set<String> noteParts = new LinkedHashSet<>();
                Set<String> audioExts = new LinkedHashSet<>();
                int audioCount = 0;
                int transitionOnlyCount = 0;
                boolean wroteAutoAdvance = false;
                for (SlidePartRef slide : slides) {
                    boolean hasSlideTransition = resolved.slideEnters().containsKey(slide.index());
                    SlideMotionSupport.EnterUpdate enter = resolved.slideEnters()
                            .getOrDefault(slide.index(), resolved.globalEnter());
                    Path note = readiness.notePaths().get(slide.index());
                    if (modules.contains("notes") && note != null) {
                        NotesApplier.applyNotes(extractDir, slide, note).ifPresent(noteParts::add);
                    }
                    Path audio = readiness.audioPaths().get(slide.index());
                    if (modules.contains("audio") && audio != null) {
                        wroteAutoAdvance = AudioApplier.applyAudio(
                                        extractDir,
                                        slide,
                                        audio,
                                        enter,
                                        modules.contains("timings"),
                                        narrationPadding,
                                        narrationStartFloor,
                                        readiness.audioDurations().get(slide.index()))
                                || wroteAutoAdvance;
                        String ext = audio.getFileName().toString();
                        int dot = ext.lastIndexOf('.');
                        if (dot >= 0) {
                            audioExts.add(ext.substring(dot).toLowerCase(Locale.ROOT));
                        }
                        audioCount++;
                        continue;
                    }
                    if (hasSlideTransition || resolved.applyWithoutAudio()) {
                        if (AudioApplier.applyTransitionOnly(extractDir, slide, enter)) {
                            transitionOnlyCount++;
                        }
                    }
                }

                AudioApplier.updateContentTypes(extractDir, noteParts, audioExts);
                if (wroteAutoAdvance) {
                    SlideMotionSupport.setDirectoryUseTimings(extractDir, true);
                }

                Files.createDirectories(output.getParent());
                Path staging = Files.createTempDirectory(output.getParent(), "java-native-enhance-out-");
                try {
                    Path candidate = staging.resolve(output.getFileName().toString());
                    PptxPackage.zipDirectory(extractDir, candidate);
                    DeliveryLite candidateDelivery = DeliveryLite.audit(candidate);
                    if (candidateDelivery.slideCount() != slides.size()) {
                        throw new IllegalStateException(
                                "PPTX delivery postflight slide count changed: "
                                        + candidateDelivery.slideCount()
                                        + " != "
                                        + slides.size());
                    }
                    Files.move(candidate, output, StandardCopyOption.REPLACE_EXISTING);

                    ObjectNode report = MAPPER.createObjectNode();
                    report.put("schema", NativeEnhanceConstants.VALIDATION_SCHEMA);
                    report.put(
                            "status",
                            sourceDelivery.hasFindings() || candidateDelivery.hasFindings()
                                    ? "passed-with-advisories"
                                    : "passed");
                    report.put("phase", "postflight");
                    report.put("plan_status", plan.path("status").asText("missing"));
                    report.set("enabled_modules", MAPPER.valueToTree(modules.stream().sorted().toList()));
                    report.put("slide_count", slides.size());
                    ObjectNode applied = report.putObject("applied");
                    applied.put("notes", noteParts.size());
                    applied.put("audio", audioCount);
                    applied.put("transition_only_slides", transitionOnlyCount);
                    applied.put("automatic_advance", wroteAutoAdvance);
                    report.set("source_delivery_check", sourceDelivery.toJson());
                    report.set("output_delivery_check", candidateDelivery.toJson());
                    report.put("source_delivery_policy", "preserve-baseline");
                    writeJson(project.resolve("validation/report.json"), report);
                } finally {
                    deleteRecursively(staging);
                }

                StringBuilder out = new StringBuilder();
                out.append("Output: ").append(output).append('\n');
                out.append("Notes applied: ").append(noteParts.size()).append('\n');
                out.append("Audio embedded: ").append(audioCount).append('\n');
                if (transitionOnlyCount > 0) {
                    out.append("Transition-only slides: ").append(transitionOnlyCount).append('\n');
                }
                return new EngineResult(0, out.toString(), false);
            } finally {
                deleteRecursively(extractDir);
            }
        } catch (Exception ex) {
            return new EngineResult(1, "error: apply failed: " + ex.getMessage(), false);
        }
    }

    private static EngineResult failPreflight(
            Path project, JsonNode plan, Set<String> modules, List<String> messages) throws IOException {
        writePreflight(project, plan, modules, "failed", Map.of("errors", messages));
        return new EngineResult(1, "error: " + String.join("; ", messages), false);
    }

    private static ObjectNode writePreflight(
            Path project, JsonNode plan, Set<String> modules, String status, Object details) throws IOException {
        ObjectNode report = MAPPER.createObjectNode();
        report.put("schema", NativeEnhanceConstants.VALIDATION_SCHEMA);
        report.put("status", status);
        report.put("phase", "preflight");
        report.put("plan_status", plan == null ? "missing" : plan.path("status").asText("missing"));
        report.set("enabled_modules", MAPPER.valueToTree(modules.stream().sorted().toList()));
        if (details instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                report.set(String.valueOf(entry.getKey()), MAPPER.valueToTree(entry.getValue()));
            }
        } else if (details instanceof ObjectNode node) {
            node.fields().forEachRemaining(e -> report.set(e.getKey(), e.getValue()));
        }
        Files.createDirectories(project.resolve("validation"));
        writeJson(project.resolve("validation/report.json"), report);
        return report;
    }

    private static List<String> sourceStateErrors(
            Path project, JsonNode projectJson, Path sourcePptx, List<SlidePartRef> slides) {
        List<String> errors = new ArrayList<>();
        if (!Files.isRegularFile(sourcePptx)) {
            errors.add("archived source PPTX is missing: " + sourcePptx);
        }
        int expected = projectJson.path("slide_count").asInt(-1);
        if (expected >= 0 && expected != slides.size()) {
            errors.add("source slide_count changed: " + expected + " != " + slides.size());
        }
        JsonNode roster = projectJson.get("slide_part_roster");
        if (roster != null && roster.isArray() && roster.size() == slides.size()) {
            for (int i = 0; i < slides.size(); i++) {
                if (!slides.get(i).partName().equals(roster.get(i).asText())) {
                    errors.add("source slide part roster changed");
                    break;
                }
            }
        }
        return errors;
    }

    private static boolean isNativeProject(JsonNode projectJson) {
        String schema = projectJson.path("schema").asText("");
        return NativeEnhanceConstants.PROJECT_SCHEMA.equals(schema)
                || NativeEnhanceConstants.LEGACY_PROJECT_SCHEMA.equals(schema);
    }

    private static String archiveSource(Path source, Path archived, Path projectsRoot) throws IOException {
        Files.createDirectories(archived.getParent());
        if (source.toAbsolutePath().normalize().equals(archived.toAbsolutePath().normalize())) {
            return "reuse";
        }
        if (isRelativeTo(source, projectsRoot)) {
            Files.move(source, archived, StandardCopyOption.REPLACE_EXISTING);
            return "move";
        }
        Files.copy(source, archived, StandardCopyOption.REPLACE_EXISTING);
        return "copy";
    }

    private static boolean isRelativeTo(Path path, Path parent) {
        try {
            path.toAbsolutePath().normalize().relativize(parent.toAbsolutePath().normalize());
            return path.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize());
        } catch (Exception ex) {
            return false;
        }
    }

    private static String sanitizeSlug(String value) {
        String cleaned = SLUG.matcher(value.trim()).replaceAll("_");
        cleaned = cleaned.replaceAll("_+", "_").replaceAll("^_|_$", "");
        return cleaned.isBlank() ? "native_enhance" : cleaned;
    }

    private static String stripPptx(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".pptx") ? name.substring(0, name.length() - 5) : name;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path);
                DigestInputStream din = new DigestInputStream(in, digest)) {
            din.transferTo(NullOutputStream.INSTANCE);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static JsonNode readJson(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return MAPPER.readTree(path.toFile());
    }

    private static void writeJson(Path path, JsonNode node) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), node);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("project.json missing " + field);
        }
        return value;
    }

    private static ArrayNode toArray(List<String> values) {
        ArrayNode array = MAPPER.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    /** Sink for digest streaming. */
    private static final class NullOutputStream extends java.io.OutputStream {
        static final NullOutputStream INSTANCE = new NullOutputStream();

        @Override
        public void write(int b) {}

        @Override
        public void write(byte[] b, int off, int len) {}
    }

    record ApplyRequest(
            Path project,
            Path output,
            boolean overwrite,
            boolean force,
            String transition,
            Double transitionDuration,
            boolean applyTransitionWithoutAudio,
            Double narrationPadding,
            Double narrationStartFloor) {
        static ApplyRequest parse(List<String> arguments) {
            Path project = null;
            Path output = null;
            boolean overwrite = false;
            boolean force = false;
            String transition = null;
            Double transitionDuration = null;
            boolean applyTransitionWithoutAudio = false;
            Double narrationPadding = null;
            Double narrationStartFloor = null;
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
                if ("--apply-transition-without-audio".equals(arg)) {
                    applyTransitionWithoutAudio = true;
                    continue;
                }
                if (("--output".equals(arg) || "-o".equals(arg)) && i + 1 < arguments.size()) {
                    output = Path.of(arguments.get(++i));
                    continue;
                }
                if ("--transition".equals(arg) && i + 1 < arguments.size()) {
                    transition = arguments.get(++i);
                    continue;
                }
                if ("--transition-duration".equals(arg) && i + 1 < arguments.size()) {
                    transitionDuration = Double.parseDouble(arguments.get(++i));
                    continue;
                }
                if ("--narration-padding".equals(arg) && i + 1 < arguments.size()) {
                    narrationPadding = Double.parseDouble(arguments.get(++i));
                    continue;
                }
                if ("--narration-start-floor".equals(arg) && i + 1 < arguments.size()) {
                    narrationStartFloor = Double.parseDouble(arguments.get(++i));
                    continue;
                }
                if (arg.startsWith("-")) {
                    if (i + 1 < arguments.size() && !arguments.get(i + 1).startsWith("-")) {
                        i++;
                    }
                    continue;
                }
                if (project == null) {
                    project = Path.of(arg);
                }
            }
            return new ApplyRequest(
                    project,
                    output,
                    overwrite,
                    force,
                    transition,
                    transitionDuration,
                    applyTransitionWithoutAudio,
                    narrationPadding,
                    narrationStartFloor);
        }
    }

    /** Lightweight structural delivery audit (ZIP open + slide count). */
    record DeliveryLite(String status, List<String> errors, List<String> advisories, int slideCount) {
        static DeliveryLite audit(Path pptx) {
            List<String> errors = new ArrayList<>();
            int count = 0;
            Path tmp = null;
            try {
                tmp = Files.createTempDirectory("java-ne-delivery-");
                PptxPackage.extract(pptx, tmp);
                count = SlideParts.read(tmp).size();
            } catch (Exception ex) {
                errors.add(ex.getMessage() == null ? "delivery open failed" : ex.getMessage());
            } finally {
                if (tmp != null) {
                    try {
                        deleteRecursively(tmp);
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
            return new DeliveryLite(errors.isEmpty() ? "passed" : "failed", errors, List.of(), count);
        }

        boolean hasFindings() {
            return !errors.isEmpty() || !advisories.isEmpty();
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("status", status);
            node.put("engine", "java-lite");
            node.set("errors", toArray(errors));
            node.set("advisories", toArray(advisories));
            ObjectNode slides = node.putObject("slides");
            slides.put("count", slideCount);
            slides.putArray("hidden");
            return node;
        }
    }
}
