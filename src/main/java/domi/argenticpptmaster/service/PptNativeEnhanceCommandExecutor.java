package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.NativeEnhancePptxProperties;
import domi.argenticpptmaster.config.PptMasterProperties;
import domi.argenticpptmaster.domain.NativeEnhanceErrorCode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.domain.PptWorkflowMode;
import domi.argenticpptmaster.infra.PptMasterCommandExecutor;
import domi.argenticpptmaster.repository.PptJobRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Native-enhance 受限命令编排：托管 init/draft plan、checkpoint 恢复、音频生成与受控 apply。
 * 永不传递 {@code --force}。
 */
@Component
public class PptNativeEnhanceCommandExecutor {

    static final String NATIVE_ENHANCE_SCRIPT = "skills/ppt-master/scripts/native_enhance_pptx.py";
    static final String PPT_TO_MD_SCRIPT = "skills/ppt-master/scripts/source_to_md/ppt_to_md.py";
    static final String NOTES_TO_AUDIO_SCRIPT = "skills/ppt-master/scripts/notes_to_audio.py";

    private final PptMasterProperties properties;
    private final PptJobRepository repository;
    private final PptWorkflowEvents events;
    private final PptMasterCommandExecutor commands;
    private final NativeEnhanceReadinessContributor readiness;
    private final NativeEnhanceOutputVerifier outputVerifier;
    private final NativeEnhancePptxLifecycleStore lifecycleStore;
    private final NativeEnhancePptxTelemetry telemetry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PptNativeEnhanceCommandExecutor(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptMasterCommandExecutor commands,
            NativeEnhanceReadinessContributor readiness,
            NativeEnhanceOutputVerifier outputVerifier) {
        this(properties, repository, events, commands, readiness, outputVerifier, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PptNativeEnhanceCommandExecutor(
            PptMasterProperties properties,
            PptJobRepository repository,
            PptWorkflowEvents events,
            PptMasterCommandExecutor commands,
            NativeEnhanceReadinessContributor readiness,
            NativeEnhanceOutputVerifier outputVerifier,
            NativeEnhancePptxLifecycleStore lifecycleStore,
            NativeEnhancePptxTelemetry telemetry) {
        this.properties = properties;
        this.repository = repository;
        this.events = events;
        this.commands = commands;
        this.readiness = readiness;
        this.outputVerifier = outputVerifier;
        this.lifecycleStore = lifecycleStore;
        this.telemetry = telemetry;
    }

    /** 阶段 3：托管 init/draft 后打开确认门禁；已确认则继续 notes-only。 */
    public void runNotesOnlyMvp(UUID jobId) {
        resumeFromCheckpoint(jobId, null);
    }

    /** 确认 enhancement plan 后继续 notes→validate→apply（audio 启用时先语音门禁）。 */
    public void continueAfterPlanConfirmation(UUID jobId) {
        resumeFromCheckpoint(jobId, PptJobNode.ENHANCEMENT_PLAN_CONFIRMED);
    }

    /** 确认语音配置后继续 audio→validate→apply→read-back。 */
    public void continueAfterAudioVoiceConfirmation(UUID jobId) {
        resumeFromCheckpoint(jobId, PptJobNode.AUDIO_VOICE_CONFIRMED);
    }

    /** 按 checkpoint 跳过已完成阶段；未确认时仅打开确认等待。 */
    public void resumeFromCheckpoint(UUID jobId, PptJobNode checkpoint) {
        PptJob job = repository.findById(jobId).orElseThrow();
        if (job.workflowMode() != PptWorkflowMode.NATIVE_ENHANCE_PPTX) {
            throw new IllegalArgumentException("native-enhance executor requires NATIVE_ENHANCE_PPTX");
        }
        if (properties.nativeEnhancePptx().production().executionStopEnabled()) {
            NativeEnhanceExecutionException stopped = new NativeEnhanceExecutionException(
                    "EXECUTION",
                    NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED,
                    "native-enhance-pptx execution is stopped");
            fail(job, stopped);
            throw stopped;
        }
        Path workspace = job.workspacePath().toAbsolutePath().normalize();
        try {
            Path source = requireSourcePptx(job, workspace);
            Path project = resolveOrInitProject(job, workspace, source, checkpoint);
            if (!nodeCompleted(job, PptJobNode.ENHANCEMENT_PLAN_CONFIRMED)) {
                openEnhancementPlanConfirmation(job, project);
                return;
            }
            int slideCount;
            if (nodeCompleted(job, PptJobNode.NOTES_GENERATED)) {
                slideCount = readSlideCount(project);
            } else {
                slideCount = injectMinimalNotes(job, project);
            }
            if (NativeEnhancePlanStore.audioEnabled(job)
                    && !nodeCompleted(job, PptJobNode.AUDIO_VOICE_CONFIRMED)) {
                openAudioVoiceConfirmation(job);
                return;
            }
            if (NativeEnhancePlanStore.audioEnabled(job)
                    && !nodeCompleted(job, PptJobNode.AUDIO_GENERATED)) {
                generateAudio(job, project);
            }
            requireFfprobeIfTimingsEnabled(job);
            if (!nodeCompleted(job, PptJobNode.NATIVE_ENHANCE_VALIDATED)) {
                validateProject(job, project);
            }
            Path export;
            if (nodeCompleted(job, PptJobNode.PPT_EXPORTED)) {
                export = requireUniqueEnhancedExport(project.resolve("exports"));
            } else {
                export = applyProject(job, project);
            }
            if (!nodeCompleted(job, PptJobNode.NATIVE_ENHANCE_OUTPUT_VALIDATED)) {
                structuredOutputValidate(job, workspace, project, export, slideCount);
            }
        } catch (NativeEnhanceExecutionException ex) {
            fail(job, ex);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            NativeEnhanceExecutionException wrapped = new NativeEnhanceExecutionException(
                    "EXECUTION",
                    NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED,
                    safeMessage(ex.getMessage()),
                    ex);
            fail(job, wrapped);
            throw wrapped;
        }
    }

    /** 驳回修订后：保留 init，提升 draft version，重新打开确认。 */
    public void reopenEnhancementPlanConfirmation(UUID jobId) {
        PptJob job = repository.findById(jobId).orElseThrow();
        Path project = job.projectPath()
                .orElseThrow(() -> new NativeEnhanceExecutionException(
                        "PLAN", NativeEnhanceErrorCode.NATIVE_ENHANCE_PLAN_FAILED, "project path is missing"));
        if (!nodeCompleted(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED)) {
            NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.bumpDraftVersion(job);
            int slideCount = readSlideCount(project);
            recordCheckpoint(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                    "enhancementPlanStatus", "draft",
                    "enabledModules", readEnabledModules(project.resolve("analysis").resolve("enhancement_plan.json")),
                    "sourcePageCount", slideCount,
                    "version", draft.version(),
                    "digest", draft.digest()));
        } else {
            NativeEnhancePlanStore.bumpDraftVersion(job);
            // re-complete draft evidence with new version/digest
            NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.readDraft(job)
                    .orElseThrow(() -> new NativeEnhanceExecutionException(
                            "PLAN", NativeEnhanceErrorCode.NATIVE_ENHANCE_PLAN_FAILED, "draft missing after bump"));
            int slideCount = readSlideCount(project);
            recordCheckpoint(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                    "enhancementPlanStatus", "draft",
                    "enabledModules", readEnabledModules(project.resolve("analysis").resolve("enhancement_plan.json")),
                    "sourcePageCount", slideCount,
                    "version", draft.version(),
                    "digest", draft.digest()));
        }
        openEnhancementPlanConfirmation(job, project);
    }

    private Path resolveOrInitProject(PptJob job, Path workspace, Path source, PptJobNode checkpoint) {
        boolean skipInit = canSkipInit(job, checkpoint);
        if (skipInit) {
            Path existing = job.projectPath().orElse(null);
            if (existing != null && initArtifactsReusable(existing)) {
                return existing.toAbsolutePath().normalize();
            }
        }
        return initProject(job, workspace, source);
    }

    private boolean canSkipInit(PptJob job, PptJobNode checkpoint) {
        if (checkpoint == null) {
            return nodeCompleted(job, PptJobNode.NATIVE_ENHANCE_INITIALIZED)
                    || nodeCompleted(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED);
        }
        return switch (checkpoint) {
            case NATIVE_ENHANCE_INITIALIZED, ENHANCEMENT_PLAN_DRAFTED, ENHANCEMENT_PLAN_CONFIRMED,
                    NOTES_GENERATED, AUDIO_VOICE_CONFIRMED, AUDIO_GENERATED, NATIVE_ENHANCE_VALIDATED, PPT_EXPORTED,
                    NATIVE_ENHANCE_OUTPUT_VALIDATED -> true;
            default -> false;
        };
    }

    private boolean initArtifactsReusable(Path projectDir) {
        return Files.isRegularFile(projectDir.resolve("project.json"))
                && Files.isRegularFile(projectDir.resolve("analysis").resolve("slide_index.json"))
                && Files.isRegularFile(projectDir.resolve("analysis").resolve("enhancement_plan.json"))
                && !Files.exists(projectDir.resolve("svg_output"));
    }

    private Path initProject(PptJob job, Path workspace, Path source) {
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        Path projectsRoot = requireInside(workspace, workspace.resolve("projects"), "projects root");
        Path projectDir = requireInside(workspace, projectsRoot.resolve(sanitizeSlug(job.projectName())), "project");
        try {
            Files.createDirectories(projectsRoot);
        } catch (IOException ex) {
            throw new NativeEnhanceExecutionException(
                    "INIT", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "failed to create projects root", ex);
        }
        List<String> args = new ArrayList<>();
        args.add("init");
        args.add(source.toString());
        args.add("--name");
        args.add(sanitizeSlug(job.projectName()));
        args.add("--project-dir");
        args.add(projectDir.toString());
        args.add("--projects-root");
        args.add(projectsRoot.toString());
        run(job, "INIT", NATIVE_ENHANCE_SCRIPT, args, limits.initTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED);
        requireFile(projectDir.resolve("project.json"), "INIT");
        requireFile(projectDir.resolve("analysis").resolve("slide_index.json"), "INIT");
        requireFile(projectDir.resolve("analysis").resolve("enhancement_plan.json"), "INIT");
        if (Files.exists(projectDir.resolve("svg_output"))) {
            throw new NativeEnhanceExecutionException(
                    "INIT", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "svg_output must not be created");
        }
        job.prepareProject(projectDir);
        int slideCount = readSlideCount(projectDir);
        NativeEnhancePlanStore.PlanMetadata draftMeta = NativeEnhancePlanStore.ensureDraftMetadata(job);
        List<String> draftModules = readEnabledModules(projectDir.resolve("analysis").resolve("enhancement_plan.json"));
        if (draftModules.isEmpty()) {
            draftModules = List.of("notes");
        }
        recordCheckpoint(job, PptJobNode.PROJECT_READY, Map.of(
                "projectReady", true,
                "sourcePageCount", slideCount));
        recordCheckpoint(job, PptJobNode.NATIVE_ENHANCE_INITIALIZED, Map.of(
                "sourcePageCount", slideCount,
                "schema", "native_pptx_enhancement_project.v1",
                "slideIndexReady", true,
                "sourcesArchived", true));
        recordCheckpoint(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                "enhancementPlanStatus", "draft",
                "enabledModules", draftModules,
                "sourcePageCount", slideCount,
                "version", draftMeta.version(),
                "digest", draftMeta.digest()));
        return projectDir;
    }

    private void openEnhancementPlanConfirmation(PptJob job, Path project) {
        if (!nodeCompleted(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED)) {
            NativeEnhancePlanStore.PlanMetadata draft = NativeEnhancePlanStore.ensureDraftMetadata(job);
            int slideCount = readSlideCount(project);
            recordCheckpoint(job, PptJobNode.ENHANCEMENT_PLAN_DRAFTED, Map.of(
                    "enhancementPlanStatus", "draft",
                    "enabledModules", readEnabledModules(project.resolve("analysis").resolve("enhancement_plan.json")),
                    "sourcePageCount", slideCount,
                    "version", draft.version(),
                    "digest", draft.digest()));
        } else {
            NativeEnhancePlanStore.ensureDraftMetadata(job);
        }
        Map<String, Object> contextData = NativeEnhanceConfirmationSummary.fromWorkspace(job, Map.of());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "enhancement_plan_confirmation");
        payload.put("message", "Confirm native-enhance module plan before notes/apply");
        payload.put("contextData", contextData);
        String confirmationId = UUID.randomUUID().toString();
        job.requireConfirmation(confirmationId, payload);
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.CONFIRMATION_REQUIRED, "waiting for enhancement plan confirmation",
                Map.of("confirmationId", confirmationId, "stage", "enhancement_plan_confirmation",
                        "version", contextData.get("version"), "digest", contextData.get("digest"))));
    }

    private void openAudioVoiceConfirmation(PptJob job) {
        Map<String, Object> contextData = NativeEnhanceConfirmationSummary.audioVoiceContext(job, Map.of());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "audio_voice_confirmation");
        payload.put("message", "Confirm TTS voice before audio generation");
        payload.put("contextData", contextData);
        String confirmationId = UUID.randomUUID().toString();
        job.requireConfirmation(confirmationId, payload);
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.CONFIRMATION_REQUIRED, "waiting for audio voice confirmation",
                Map.of("confirmationId", confirmationId, "stage", "audio_voice_confirmation")));
    }

    private int injectMinimalNotes(PptJob job, Path project) throws IOException {
        Path notesDir = requireInside(project, project.resolve("notes"), "notes");
        Files.createDirectories(notesDir);
        Path slideIndex = requireInside(project, project.resolve("analysis").resolve("slide_index.json"), "slide index");
        JsonNode index = objectMapper.readTree(slideIndex.toFile());
        JsonNode slides = index.path("slides");
        if (!slides.isArray() || slides.isEmpty()) {
            throw new NativeEnhanceExecutionException(
                    "NOTES", NativeEnhanceErrorCode.NATIVE_ENHANCE_PLAN_FAILED, "slide_index slides are missing");
        }
        int count = 0;
        for (JsonNode slide : slides) {
            int slideNo = slide.path("index").asInt(slide.path("slide_number").asInt(0));
            if (slideNo <= 0) {
                continue;
            }
            String noteFile = slide.path("note_file").asText(String.format("%03d.md", slideNo));
            Path notePath = requireInside(notesDir, notesDir.resolve(Path.of(noteFile).getFileName().toString()), "note");
            String titleHint = slide.path("title").asText("slide " + slideNo);
            String body = "This is slide " + slideNo + ". " + titleHint
                    + ". Please review the visible content on this page.";
            Files.writeString(notePath, body + System.lineSeparator(), StandardCharsets.UTF_8);
            count++;
        }
        recordCheckpoint(job, PptJobNode.NOTES_GENERATED, Map.of(
                "notesCoverage", Map.of("ready", count, "total", count),
                "audioCoverage", Map.of("ready", 0, "total", 0),
                "audioVoiceStatus", "skipped"));
        return count;
    }

    private void validateProject(PptJob job, Path project) {
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        run(job, "VALIDATE", NATIVE_ENHANCE_SCRIPT, List.of("validate", project.toString()),
                limits.validateTimeout(), NativeEnhanceErrorCode.NATIVE_ENHANCE_VALIDATE_FAILED);
        Path report = project.resolve("validation").resolve("report.json");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("validated", true);
        Map<String, Object> reportSummary = new LinkedHashMap<>();
        if (Files.isRegularFile(report)) {
            try {
                JsonNode node = objectMapper.readTree(report.toFile());
                String planStatus = node.path("plan_status").asText("");
                int notesCount = node.path("notes_count").asInt(0);
                int missingNotes = node.path("missing_notes").size();
                int missingAudio = node.path("missing_audio").size();
                summary.put("planStatus", planStatus);
                summary.put("notesCount", notesCount);
                summary.put("missingNotes", missingNotes);
                reportSummary.put("plan_status", planStatus);
                reportSummary.put("missingNotes", missingNotes);
                reportSummary.put("missingAudio", missingAudio);
            } catch (IOException ignored) {
                // keep minimal summary
            }
        }
        if (!reportSummary.isEmpty()) {
            summary.put("validateReportSummary", reportSummary);
        }
        recordCheckpoint(job, PptJobNode.NATIVE_ENHANCE_VALIDATED, summary);
    }

    private void generateAudio(PptJob job, Path project) throws IOException {
        if (!nodeCompleted(job, PptJobNode.AUDIO_VOICE_CONFIRMED)) {
            throw new NativeEnhanceExecutionException(
                    "AUDIO", NativeEnhanceErrorCode.NATIVE_ENHANCE_AUDIO_FAILED,
                    "audio voice must be confirmed before notes_to_audio");
        }
        Map<String, String> audio = NativeEnhancePlanStore.readProjectAudio(job);
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        Path audioDir = requireInside(project, project.resolve("audio"), "audio");
        Files.createDirectories(audioDir);
        List<String> args = buildNotesToAudioArguments(project, audio);
        run(job, "AUDIO", NOTES_TO_AUDIO_SCRIPT, args, limits.audioTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_AUDIO_FAILED);
        int notesReady = NativeEnhanceOutputVerifier.countNotesFiles(project);
        int audioReady = NativeEnhanceOutputVerifier.countAudioFiles(project);
        recordCheckpoint(job, PptJobNode.AUDIO_GENERATED, Map.of(
                "audioCoverage", Map.of("ready", audioReady, "total", notesReady),
                "provider", audio.get("provider"),
                "voice", audio.get("voice")));
    }

    static List<String> buildNotesToAudioArguments(Path project, Map<String, String> audio) {
        List<String> args = new ArrayList<>();
        args.add(project.toString());
        args.add("--voice");
        args.add(audio.get("voice"));
        args.add("--rate");
        args.add(audio.getOrDefault("rate", "+0%"));
        args.add("--provider");
        args.add(audio.getOrDefault("provider", "edge"));
        return List.copyOf(args);
    }

    private void requireFfprobeIfTimingsEnabled(PptJob job) {
        if (!NativeEnhancePlanStore.timingsEnabled(job)) {
            return;
        }
        if (!readiness.isFfprobeAvailable()) {
            throw new NativeEnhanceExecutionException(
                    "TIMINGS",
                    NativeEnhanceErrorCode.NATIVE_ENHANCE_FFPROBE_UNAVAILABLE,
                    "ffprobe is required when timings are enabled");
        }
    }

    private Path applyProject(PptJob job, Path project) throws IOException {
        if (!nodeCompleted(job, PptJobNode.ENHANCEMENT_PLAN_CONFIRMED)) {
            throw new NativeEnhanceExecutionException(
                    "APPLY", NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED,
                    "enhancement plan must be confirmed before apply");
        }
        if (NativeEnhancePlanStore.audioEnabled(job) && !nodeCompleted(job, PptJobNode.AUDIO_VOICE_CONFIRMED)) {
            throw new NativeEnhanceExecutionException(
                    "APPLY", NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED,
                    "audio voice must be confirmed before apply");
        }
        if (NativeEnhancePlanStore.audioEnabled(job) && !nodeCompleted(job, PptJobNode.AUDIO_GENERATED)) {
            throw new NativeEnhanceExecutionException(
                    "APPLY", NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED,
                    "audio must be generated before apply");
        }
        NativeEnhancePlanStore.PlanMetadata plan = NativeEnhancePlanStore.read(job).orElse(null);
        if (plan == null || !"confirmed".equals(plan.status())) {
            throw new NativeEnhanceExecutionException(
                    "APPLY", NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED,
                    "enhancement plan status must be confirmed");
        }
        NativeEnhancePlanStore.ApplySettings settings = NativeEnhancePlanStore.applySettings(job);
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        Path exportsDir = requireInside(project, project.resolve("exports"), "exports");
        Files.createDirectories(exportsDir);
        job.startExport();
        repository.save(job);
        List<String> args = buildApplyArguments(project, settings);
        run(job, "APPLY", NATIVE_ENHANCE_SCRIPT, args, limits.applyTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED);
        Path export = requireUniqueEnhancedExport(exportsDir);
        recordCheckpoint(job, PptJobNode.PPT_EXPORTED, Map.of(
                "exportFileName", export.getFileName().toString()));
        return export;
    }

    static List<String> buildApplyArguments(Path project, NativeEnhancePlanStore.ApplySettings settings) {
        List<String> args = new ArrayList<>();
        args.add("apply");
        args.add(project.toString());
        args.add("--overwrite");
        if (settings.transitionsEnabled()) {
            if (!NativeEnhancePlanStore.isAllowedTransitionEffect(settings.transitionEffect())) {
                throw new NativeEnhanceExecutionException(
                        "APPLY", NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED,
                        "unsupported transition effect: " + settings.transitionEffect());
            }
            args.add("--transition");
            args.add(settings.transitionEffect());
            args.add("--transition-duration");
            args.add(Double.toString(settings.transitionDuration()));
            if (settings.applyWithoutAudio()) {
                args.add("--apply-transition-without-audio");
            }
        }
        if (settings.timingsEnabled()) {
            args.add("--narration-padding");
            args.add(Double.toString(settings.narrationPadding()));
        }
        return List.copyOf(args);
    }

    private void structuredOutputValidate(PptJob job, Path workspace, Path project, Path export, int expectedSlides)
            throws IOException {
        NativeEnhancePptxProperties limits = properties.nativeEnhancePptx();
        Path validationDir = requireInside(project, project.resolve("validation"), "validation");
        Files.createDirectories(validationDir);
        Path readbackMd = validationDir.resolve("readback.md");
        run(job, "READBACK", PPT_TO_MD_SCRIPT,
                List.of(export.toString(), "-o", readbackMd.toString()),
                limits.applyTimeout(),
                NativeEnhanceErrorCode.NATIVE_ENHANCE_OUTPUT_INVALID);

        NativeEnhancePlanStore.ApplySettings settings = NativeEnhancePlanStore.applySettings(job);
        int notesFiles = NativeEnhanceOutputVerifier.countNotesFiles(project);
        int audioFiles = NativeEnhanceOutputVerifier.countAudioFiles(project);
        NativeEnhanceOutputVerifier.ReadbackResult result = outputVerifier.verify(
                export, validationDir, expectedSlides, settings, notesFiles, audioFiles);
        if (!result.passed()) {
            throw new NativeEnhanceExecutionException(
                    "OUTPUT",
                    NativeEnhanceErrorCode.NATIVE_ENHANCE_OUTPUT_INVALID,
                    "native-enhance readback failed: status=" + result.status()
                            + ", errors=" + result.errorCount());
        }

        Path deliveryDir = requireInside(workspace, workspace.resolve("exports"), "delivery exports");
        Files.createDirectories(deliveryDir);
        Path delivery = requireInside(workspace, deliveryDir.resolve(export.getFileName().toString()), "delivery");
        Files.copy(export, delivery, StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("exportFileName", delivery.getFileName().toString());
        summary.put("sourcePageCount", expectedSlides);
        summary.put("actualPageCount", result.actualPageCount());
        summary.put("readbackPresent", Files.isRegularFile(readbackMd));
        summary.put("readbackReport", NativeEnhanceOutputVerifier.REPORT_FILE);
        summary.put("pageCountMatch", expectedSlides <= 0 || expectedSlides == result.actualPageCount());
        summary.put("outputValidationSummary", Map.of(
                "status", result.status(),
                "warningCount", result.warningCount(),
                "errorCount", result.errorCount()));
        recordCheckpoint(job, PptJobNode.NATIVE_ENHANCE_OUTPUT_VALIDATED, summary);
        job.complete(delivery);
        repository.save(job);
        markTerminal(job);
        events.record(job, PptJobEvent.of(PptJobEventType.EXPORT_READY, "native-enhance export ready",
                Map.of("fileName", delivery.getFileName().toString(), "sourcePageCount", expectedSlides,
                        "outputValidationStatus", result.status())));
    }

    private Path requireSourcePptx(PptJob job, Path workspace) {
        if (job.sourceFiles().size() != 1) {
            throw new NativeEnhanceExecutionException(
                    "INIT", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "exactly one source pptx is required");
        }
        return requireInside(workspace, job.sourceFiles().get(0).storedPath(), "source");
    }

    private int readSlideCount(Path project) {
        Path slideIndex = project.resolve("analysis").resolve("slide_index.json");
        if (!Files.isRegularFile(slideIndex)) {
            return 0;
        }
        try {
            return objectMapper.readTree(slideIndex.toFile()).path("slide_count").asInt(0);
        } catch (IOException ex) {
            return 0;
        }
    }

    private List<String> readEnabledModules(Path planPath) {
        if (!Files.isRegularFile(planPath)) {
            return List.of();
        }
        try {
            JsonNode modules = objectMapper.readTree(planPath.toFile()).path("modules");
            if (!modules.isObject()) {
                return List.of();
            }
            List<String> enabled = new ArrayList<>();
            modules.fields().forEachRemaining(entry -> {
                if (entry.getValue().path("enabled").asBoolean(false)) {
                    enabled.add(entry.getKey());
                }
            });
            return List.copyOf(enabled);
        } catch (IOException ex) {
            return List.of();
        }
    }

    private Path requireUniqueEnhancedExport(Path exportsDir) {
        List<Path> exports;
        try (Stream<Path> stream = Files.list(exportsDir)) {
            exports = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pptx"))
                    .filter(path -> path.getFileName().toString().contains("_enhanced"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new NativeEnhanceExecutionException(
                    "APPLY", NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED, "failed to list exports", ex);
        }
        if (exports.size() != 1) {
            throw new NativeEnhanceExecutionException(
                    "APPLY",
                    NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED,
                    "expected exactly one *_enhanced.pptx export, found=" + exports.size());
        }
        return exports.get(0);
    }

    private void run(
            PptJob job,
            String stage,
            String script,
            List<String> arguments,
            Duration timeout,
            NativeEnhanceErrorCode errorCode) {
        rejectUnsafeCommand(stage, script, arguments);
        events.record(job, PptJobEvent.of(PptJobEventType.NODE_STARTED,
                "native-enhance stage started", Map.of("stage", stage)));
        PptMasterCommandExecutor.CommandResult result =
                commands.runPythonScript(script, List.copyOf(arguments), timeout);
        if (!result.successful()) {
            throw new NativeEnhanceExecutionException(
                    stage,
                    errorCode,
                    "native-enhance " + stage.toLowerCase() + " failed (exitCode=" + result.exitCode()
                            + ", timedOut=" + result.timedOut() + "): " + safeMessage(result.output()));
        }
    }

    static void rejectUnsafeCommand(String stage, String script, List<String> arguments) {
        if (!NATIVE_ENHANCE_SCRIPT.equals(script)
                && !PPT_TO_MD_SCRIPT.equals(script)
                && !NOTES_TO_AUDIO_SCRIPT.equals(script)) {
            throw new NativeEnhanceExecutionException(
                    stage, NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "script is not allowed: " + script);
        }
        if (script.contains("svg") || script.contains("template_fill") || script.contains("beautify")) {
            throw new NativeEnhanceExecutionException(
                    stage, NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "svg/template/beautify tooling is not allowed");
        }
        for (String argument : arguments) {
            if (argument != null && argument.contains("--force")) {
                throw new NativeEnhanceExecutionException(
                        stage, NativeEnhanceErrorCode.NATIVE_ENHANCE_APPLY_FAILED, "force execution is not allowed");
            }
        }
    }

    private void recordCheckpoint(PptJob job, PptJobNode node, Map<String, Object> summary) {
        job.startNode(node);
        job.completeNode(node, summary);
        repository.save(job);
        events.record(job, PptJobEvent.of(PptJobEventType.NODE_COMPLETED,
                "node completed: " + node.name(),
                Map.of("node", node.name(), "summary", sanitizeSummary(summary))));
    }

    private void fail(PptJob job, NativeEnhanceExecutionException ex) {
        String message = ex.errorCode().code() + ": " + safeMessage(ex.getMessage());
        if (job.currentNode().isPresent()) {
            job.failNode(job.currentNode().get(), message);
        } else {
            job.fail(message);
        }
        repository.save(job);
        markTerminal(job);
        if (telemetry != null) {
            telemetry.recordError(ex.errorCode());
        }
        events.record(job, PptJobEvent.of(PptJobEventType.JOB_FAILED, "native-enhance failed",
                Map.of("stage", ex.stage(), "errorCode", ex.errorCode().code())));
    }

    private void markTerminal(PptJob job) {
        if (lifecycleStore == null) {
            return;
        }
        try {
            lifecycleStore.markTerminal(job, lifecycleStore.retentionForTerminalStatus(job.status()));
        } catch (RuntimeException ignored) {
            // lifecycle is best-effort at terminal boundaries
        }
    }

    private static Map<String, Object> sanitizeSummary(Map<String, Object> summary) {
        Map<String, Object> safe = new LinkedHashMap<>();
        summary.forEach((key, value) -> {
            if (value instanceof Path path) {
                safe.put(key, path.getFileName().toString());
            } else if (value instanceof String text && looksLikeAbsolutePath(text)) {
                safe.put(key, Path.of(text).getFileName().toString());
            } else {
                safe.put(key, value);
            }
        });
        return Map.copyOf(safe);
    }

    private static boolean looksLikeAbsolutePath(String text) {
        return text.startsWith("/") || text.matches("^[A-Za-z]:\\\\.*");
    }

    private static boolean nodeCompleted(PptJob job, PptJobNode node) {
        PptNodeExecution execution = job.nodeExecution(node);
        return execution != null && execution.status() == PptJobNodeStatus.COMPLETED;
    }

    private static Path requireInside(Path root, Path candidate, String label) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new NativeEnhanceExecutionException(
                    "PATH", NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, label + " escapes workspace");
        }
        return normalized;
    }

    private static void requireFile(Path path, String stage) {
        if (!Files.isRegularFile(path)) {
            throw new NativeEnhanceExecutionException(
                    stage, NativeEnhanceErrorCode.NATIVE_ENHANCE_INIT_FAILED, "missing required file: " + path.getFileName());
        }
    }

    static String sanitizeSlug(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return "native_enhance";
        }
        String slug = projectName.trim().replaceAll("[^0-9A-Za-z_-]", "_");
        return slug.isBlank() ? "native_enhance" : slug;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
