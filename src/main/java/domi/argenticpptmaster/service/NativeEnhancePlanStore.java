package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persists native-enhance {@code analysis/enhancement_plan.json} draft digests and confirms them.
 */
public final class NativeEnhancePlanStore {

    public static final String PLAN_FILE = "enhancement_plan.json";
    public static final String CONTEXT_TYPE = "native_pptx_enhancement_plan";
    private static final Set<String> MODULE_KEYS = Set.of("notes", "audio", "timings", "transitions");
    private static final Set<String> FORBIDDEN_ANSWER_KEYS = Set.of(
            "slide_count", "sourcePageCount", "pageCount", "pages", "slides",
            "source_pptx", "visibleContent", "visible_text", "status", "digest", "version", "schema");
    private static final Set<String> ALLOWED_ANSWER_KEYS = Set.of(
            "notesEnabled", "audioEnabled", "timingsEnabled", "transitionsEnabled",
            "transitionEffect", "transitionDuration", "narrationPadding", "applyWithoutAudio",
            "planVersion", "planDigest");
    private static final Set<String> TRANSITION_EFFECTS = Set.of(
            "cover", "fade", "push", "random", "split", "strips", "wipe", "none");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private NativeEnhancePlanStore() {
    }

    public static PlanMetadata ensureDraftMetadata(PptJob job) {
        ObjectNode plan = readPlanObject(job)
                .orElseThrow(() -> new PptJobStateException("enhancement plan is missing"));
        if (!"draft".equals(plan.path("status").asText()) && !"confirmed".equals(plan.path("status").asText())) {
            plan.put("status", "draft");
        }
        ensureModuleKeys(plan);
        if (plan.path("version").asInt(0) <= 0) {
            plan.put("version", 1);
        }
        if (!"draft".equals(plan.path("status").asText())) {
            // Only rewrite digest for drafts; confirmed plans are immutable here.
            return new PlanMetadata(plan.path("version").asInt(), plan.path("digest").asText(""), plan.path("status").asText());
        }
        plan.remove("digest");
        String digest = digest(canonicalBytes(plan));
        plan.put("digest", digest);
        plan.put("status", "draft");
        atomicWrite(path(job), writeBytes(plan));
        return new PlanMetadata(plan.path("version").asInt(), digest, "draft");
    }

    public static PlanMetadata bumpDraftVersion(PptJob job) {
        ObjectNode plan = readPlanObject(job)
                .orElseThrow(() -> new PptJobStateException("enhancement plan is missing"));
        ensureModuleKeys(plan);
        int next = Math.max(1, plan.path("version").asInt(0) + 1);
        plan.put("version", next);
        plan.put("status", "draft");
        plan.remove("digest");
        String digest = digest(canonicalBytes(plan));
        plan.put("digest", digest);
        atomicWrite(path(job), writeBytes(plan));
        return new PlanMetadata(next, digest, "draft");
    }

    public static Optional<PlanMetadata> readDraft(PptJob job) {
        return read(job, "draft");
    }

    public static Optional<PlanMetadata> read(PptJob job) {
        return read(job, null);
    }

    public static Optional<ObjectNode> readPlan(PptJob job) {
        return readPlanObject(job).flatMap(plan -> {
            String status = plan.path("status").asText();
            if (!"draft".equals(status) && !"confirmed".equals(status)) {
                return Optional.empty();
            }
            String storedDigest = plan.path("digest").asText();
            if (storedDigest.isBlank()) {
                return Optional.empty();
            }
            String actual = digest(canonicalBytes(plan));
            if (!storedDigest.equals(actual)) {
                return Optional.empty();
            }
            return Optional.of(plan);
        });
    }

    public static PlanMetadata confirmDraft(PptJob job, int version, String digest, Map<String, Object> answers) {
        PlanMetadata draft = readDraft(job)
                .orElseThrow(() -> new PptJobStateException("enhancement plan draft is missing or invalid"));
        if (draft.version() != version || !draft.digest().equals(digest)) {
            throw new PptJobStateException("enhancement plan version or digest mismatch");
        }
        Map<String, Object> safeAnswers = answers == null ? Map.of() : answers;
        rejectForbiddenAnswers(safeAnswers);
        try {
            ObjectNode plan = (ObjectNode) OBJECT_MAPPER.readTree(path(job).toFile());
            int expectedSlideCount = plan.path("slide_count").asInt(0);
            applyModuleAnswers(plan, safeAnswers);
            ensureModuleKeys(plan);
            if (expectedSlideCount > 0 && plan.path("slide_count").asInt(0) != expectedSlideCount) {
                throw new PptJobStateException("enhancement plan slide_count cannot change on confirm");
            }
            // Default production confirm: notes on, others off unless explicitly enabled.
            ObjectNode modules = plan.with("modules");
            if (!hasExplicitModuleAnswer(safeAnswers)) {
                setModuleEnabled(modules, "notes", true);
                setModuleEnabled(modules, "audio", false);
                setModuleEnabled(modules, "timings", false);
                setModuleEnabled(modules, "transitions", false);
            }
            if (modules.path("audio").path("enabled").asBoolean(false)
                    && !modules.path("notes").path("enabled").asBoolean(false)) {
                throw new PptJobStateException("audio requires notes to be enabled");
            }
            plan.put("status", "confirmed");
            plan.remove("digest");
            String confirmedDigest = digest(canonicalBytes(plan));
            plan.put("digest", confirmedDigest);
            atomicWrite(path(job), writeBytes(plan));
            return new PlanMetadata(draft.version(), confirmedDigest, "confirmed");
        } catch (PptJobStateException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new PptJobStateException("failed to confirm enhancement plan");
        }
    }

    public static boolean audioEnabled(PptJob job) {
        return moduleEnabled(job, "audio");
    }

    public static boolean timingsEnabled(PptJob job) {
        return moduleEnabled(job, "timings");
    }

    public static boolean transitionsEnabled(PptJob job) {
        return moduleEnabled(job, "transitions");
    }

    public static boolean moduleEnabled(PptJob job, String module) {
        return readPlan(job)
                .map(plan -> plan.path("modules").path(module).path("enabled").asBoolean(false))
                .orElse(false);
    }

    public static boolean isAllowedTransitionEffect(String effect) {
        return effect != null && TRANSITION_EFFECTS.contains(effect);
    }

    /**
     * Reads apply-relevant settings from the confirmed plan (defaults match upstream V1).
     */
    public static ApplySettings applySettings(PptJob job) {
        ObjectNode plan = readPlan(job).orElse(null);
        if (plan == null) {
            return ApplySettings.defaults();
        }
        JsonNode modules = plan.path("modules");
        JsonNode transitions = modules.path("transitions");
        JsonNode timings = modules.path("timings");
        String effect = transitions.path("effect").asText("fade");
        boolean transitionsEnabled = modules.path("transitions").path("enabled").asBoolean(false);
        if (transitionsEnabled && !isAllowedTransitionEffect(effect)) {
            throw new PptJobStateException("unsupported transition effect: " + effect);
        }
        if (!isAllowedTransitionEffect(effect)) {
            effect = "fade";
        }
        double duration = transitions.path("duration").asDouble(0.5);
        boolean applyWithoutAudio = transitions.path("apply_without_audio").asBoolean(false);
        double narrationPadding = timings.path("narration_padding").asDouble(0.4);
        return new ApplySettings(
                modules.path("notes").path("enabled").asBoolean(false),
                modules.path("audio").path("enabled").asBoolean(false),
                modules.path("timings").path("enabled").asBoolean(false),
                transitionsEnabled,
                effect,
                duration,
                applyWithoutAudio,
                narrationPadding);
    }

    public static void writeProjectAudio(PptJob job, Map<String, Object> answers) {
        Path projectJson = projectJsonPath(job);
        if (!Files.isRegularFile(projectJson)) {
            throw new PptJobStateException("project.json is missing");
        }
        Map<String, Object> safe = answers == null ? Map.of() : answers;
        rejectForbiddenAnswers(safe);
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(projectJson.toFile());
            if (!(parsed instanceof ObjectNode root)) {
                throw new PptJobStateException("project.json must be an object");
            }
            ObjectNode audio = root.with("audio");
            String provider = stringAnswer(safe, "provider", audio.path("provider").asText("edge"));
            String voice = stringAnswer(safe, "voice", audio.path("voice").asText("zh-CN-YunjianNeural"));
            String rate = stringAnswer(safe, "rate", audio.path("rate").asText("+0%"));
            if (provider.isBlank() || voice.isBlank()) {
                throw new PptJobStateException("audio voice confirmation requires provider and voice");
            }
            audio.put("provider", provider);
            audio.put("voice", voice);
            audio.put("rate", rate);
            atomicWrite(projectJson, writeBytes(root));
        } catch (PptJobStateException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new PptJobStateException("failed to write project.json audio");
        }
    }

    public static Map<String, String> readProjectAudio(PptJob job) {
        Path projectJson = projectJsonPath(job);
        if (!Files.isRegularFile(projectJson)) {
            throw new PptJobStateException("project.json is missing");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(projectJson.toFile());
            JsonNode audio = root.path("audio");
            String provider = audio.path("provider").asText("edge");
            String voice = audio.path("voice").asText("zh-CN-YunjianNeural");
            String rate = audio.path("rate").asText("+0%");
            if (provider.isBlank() || voice.isBlank()) {
                throw new PptJobStateException("project.json audio provider/voice is missing");
            }
            Map<String, String> result = new LinkedHashMap<>();
            result.put("provider", provider);
            result.put("voice", voice);
            result.put("rate", rate.isBlank() ? "+0%" : rate);
            return Map.copyOf(result);
        } catch (PptJobStateException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new PptJobStateException("failed to read project.json audio");
        }
    }

    public static Map<String, Object> moduleSwitches(ObjectNode plan) {
        Map<String, Object> modules = new LinkedHashMap<>();
        JsonNode root = plan.path("modules");
        for (String key : MODULE_KEYS) {
            modules.put(key, root.path(key).path("enabled").asBoolean(false));
        }
        return modules;
    }

    private static Optional<PlanMetadata> read(PptJob job, String expectedStatus) {
        return readPlan(job).flatMap(plan -> {
            String status = plan.path("status").asText();
            if (expectedStatus != null && !expectedStatus.equals(status)) {
                return Optional.empty();
            }
            return Optional.of(new PlanMetadata(plan.path("version").asInt(), plan.path("digest").asText(), status));
        });
    }

    private static Optional<ObjectNode> readPlanObject(PptJob job) {
        Path planPath = path(job);
        if (!Files.isRegularFile(planPath)) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(planPath.toFile());
            if (!(parsed instanceof ObjectNode plan)) {
                return Optional.empty();
            }
            return Optional.of(plan);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private static void applyModuleAnswers(ObjectNode plan, Map<String, Object> answers) {
        ObjectNode modules = plan.with("modules");
        applyBool(modules, "notes", answers.get("notesEnabled"));
        applyBool(modules, "audio", answers.get("audioEnabled"));
        applyBool(modules, "timings", answers.get("timingsEnabled"));
        applyBool(modules, "transitions", answers.get("transitionsEnabled"));
        ObjectNode transitions = modules.with("transitions");
        if (answers.get("transitionEffect") instanceof String effect) {
            if (!TRANSITION_EFFECTS.contains(effect)) {
                throw new PptJobStateException("unsupported transition effect: " + effect);
            }
            transitions.put("effect", effect);
        }
        if (answers.get("transitionDuration") instanceof Number duration) {
            transitions.put("duration", duration.doubleValue());
        }
        if (answers.get("applyWithoutAudio") instanceof Boolean apply) {
            transitions.put("apply_without_audio", apply);
        }
        ObjectNode timings = modules.with("timings");
        if (answers.get("narrationPadding") instanceof Number padding) {
            timings.put("narration_padding", padding.doubleValue());
        }
    }

    private static boolean hasExplicitModuleAnswer(Map<String, Object> answers) {
        return answers.containsKey("notesEnabled")
                || answers.containsKey("audioEnabled")
                || answers.containsKey("timingsEnabled")
                || answers.containsKey("transitionsEnabled");
    }

    private static void applyBool(ObjectNode modules, String name, Object value) {
        if (value instanceof Boolean enabled) {
            setModuleEnabled(modules, name, enabled);
        }
    }

    private static void setModuleEnabled(ObjectNode modules, String name, boolean enabled) {
        ObjectNode module = modules.with(name);
        module.put("enabled", enabled);
    }

    private static void ensureModuleKeys(ObjectNode plan) {
        ObjectNode modules = plan.with("modules");
        for (String key : MODULE_KEYS) {
            if (!modules.has(key) || !modules.get(key).isObject()) {
                ObjectNode module = modules.putObject(key);
                module.put("enabled", "notes".equals(key));
            }
        }
    }

    private static void rejectForbiddenAnswers(Map<String, Object> answers) {
        for (String key : answers.keySet()) {
            if (FORBIDDEN_ANSWER_KEYS.contains(key)) {
                throw new PptJobStateException("confirmation answer cannot change field: " + key);
            }
            if (!ALLOWED_ANSWER_KEYS.contains(key)
                    && !"provider".equals(key)
                    && !"voice".equals(key)
                    && !"rate".equals(key)) {
                throw new PptJobStateException("unsupported confirmation answer: " + key);
            }
        }
    }

    private static String stringAnswer(Map<String, Object> answers, String key, String fallback) {
        Object value = answers.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return fallback == null ? "" : fallback;
    }

    private static Path path(PptJob job) {
        Path project = job.projectPath().orElseThrow(() -> new PptJobStateException("project path is missing"));
        Path result = project.resolve("analysis").resolve(PLAN_FILE).normalize();
        if (!result.startsWith(project.normalize())) {
            throw new PptJobStateException("enhancement plan path escapes project");
        }
        return result;
    }

    private static Path projectJsonPath(PptJob job) {
        Path project = job.projectPath().orElseThrow(() -> new PptJobStateException("project path is missing"));
        Path result = project.resolve("project.json").normalize();
        if (!result.startsWith(project.normalize())) {
            throw new PptJobStateException("project.json path escapes project");
        }
        return result;
    }

    private static byte[] writeBytes(JsonNode node) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to serialize enhancement plan");
        }
    }

    private static byte[] canonicalBytes(ObjectNode plan) {
        ObjectNode canonical = plan.deepCopy();
        canonical.remove("digest");
        canonical.remove("status");
        return writeBytes(canonical);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void atomicWrite(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to store enhancement plan");
        }
    }

    public record PlanMetadata(int version, String digest, String status) {
    }

    public record ApplySettings(
            boolean notesEnabled,
            boolean audioEnabled,
            boolean timingsEnabled,
            boolean transitionsEnabled,
            String transitionEffect,
            double transitionDuration,
            boolean applyWithoutAudio,
            double narrationPadding) {

        public static ApplySettings defaults() {
            return new ApplySettings(true, false, false, false, "fade", 0.5, false, 0.4);
        }
    }
}
