package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.domain.PptNodeExecution;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 持久化 beautify 计划草案，并在批准时固化 identityLock。 */
public final class BeautifyPptxPlanStore {

    private static final String PLAN_FILE = "beautify_plan.draft.json";
    private static final Set<String> ALLOWED_STRICTNESS = Set.of("strict", "standard");
    private static final Set<String> ALLOWED_IDENTITY_SOURCES = Set.of("theme", "observed");
    private static final Set<String> FORBIDDEN_ANSWER_KEYS = Set.of(
            "hardConstraints", "textVerbatim", "dataVerbatim", "pageMapping", "sourcePageCount",
            "pageCount", "slides", "status", "digest", "version");
    private static final Set<String> ALLOWED_ANSWER_KEYS = Set.of(
            "identitySource", "paletteCandidateId", "fontCandidateId", "bodySizeCandidateId",
            "validationStrictness", "acceptedIgnoredIds", "needsConfirmationResolutions");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private BeautifyPptxPlanStore() {
    }

    public static PlanMetadata storeDraft(PptJob job, String content) {
        ObjectNode plan = parseDraft(content);
        int expectedPages = expectedSourcePageCount(job);
        if (expectedPages > 0 && plan.path("sourcePageCount").asInt(0) != expectedPages) {
            throw new PptJobStateException("beautify plan sourcePageCount must match inventory");
        }
        plan.remove("digest");
        plan.remove("identityLock");
        int version = plan.path("version").asInt();
        String digest = digest(canonicalBytes(plan));
        plan.put("digest", digest);
        atomicWrite(path(job), writeBytes(plan));
        return new PlanMetadata(version, digest, "draft");
    }

    public static Optional<PlanMetadata> readDraft(PptJob job) {
        return read(job, "draft");
    }

    public static Optional<PlanMetadata> read(PptJob job) {
        return read(job, null);
    }

    public static Optional<ObjectNode> readPlan(PptJob job) {
        Path path = path(job);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(path.toFile());
            if (!(parsed instanceof ObjectNode plan)) {
                return Optional.empty();
            }
            String status = plan.path("status").asText();
            if (!"draft".equals(status) && !"confirmed".equals(status)) {
                return Optional.empty();
            }
            String storedDigest = plan.path("digest").asText();
            String actualDigest = digest(canonicalBytes(plan));
            if (storedDigest.isBlank() || !storedDigest.equals(actualDigest)) {
                return Optional.empty();
            }
            return Optional.of(plan);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public static boolean hasIdentityLock(PptJob job) {
        return readPlan(job)
                .map(plan -> "confirmed".equals(plan.path("status").asText())
                        && plan.path("identityLock").isObject()
                        && ALLOWED_IDENTITY_SOURCES.contains(plan.path("identityLock").path("source").asText()))
                .orElse(false);
    }

    public static PlanMetadata confirmDraft(PptJob job, int version, String digest) {
        return confirmDraft(job, version, digest, Map.of());
    }

    public static PlanMetadata confirmDraft(PptJob job, int version, String digest, Map<String, Object> answers) {
        PlanMetadata draft = readDraft(job)
                .orElseThrow(() -> new PptJobStateException("beautify plan draft is missing or invalid"));
        if (draft.version() != version || !draft.digest().equals(digest)) {
            throw new PptJobStateException("beautify plan version or digest mismatch");
        }
        Map<String, Object> safeAnswers = answers == null ? Map.of() : answers;
        rejectForbiddenAnswers(safeAnswers);
        try {
            ObjectNode plan = (ObjectNode) OBJECT_MAPPER.readTree(path(job).toFile());
            plan.set("identityLock", OBJECT_MAPPER.valueToTree(resolveIdentityLock(plan, safeAnswers)));
            if (safeAnswers.get("validationStrictness") instanceof String strictness
                    && ALLOWED_STRICTNESS.contains(strictness)) {
                plan.put("validationStrictness", strictness);
            }
            if (safeAnswers.containsKey("needsConfirmationResolutions")) {
                plan.set("needsConfirmationResolutions",
                        OBJECT_MAPPER.valueToTree(safeAnswers.get("needsConfirmationResolutions")));
            }
            if (safeAnswers.containsKey("acceptedIgnoredIds")) {
                plan.set("acceptedIgnoredIds", OBJECT_MAPPER.valueToTree(safeAnswers.get("acceptedIgnoredIds")));
            }
            plan.put("status", "confirmed");
            // Recompute digest after applying confirm-time overrides (strictness / resolutions),
            // while still excluding lock-only fields from the canonical hash.
            plan.remove("digest");
            String confirmedDigest = digest(canonicalBytes(plan));
            plan.put("digest", confirmedDigest);
            atomicWrite(path(job), writeBytes(plan));
            return new PlanMetadata(draft.version(), confirmedDigest, "confirmed");
        } catch (IOException ex) {
            throw new PptJobStateException("failed to confirm beautify plan");
        }
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

    private static ObjectNode parseDraft(String content) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(content);
            if (!(parsed instanceof ObjectNode plan)
                    || plan.path("version").asInt(0) <= 0
                    || !"draft".equals(plan.path("status").asText())
                    || plan.path("sourcePageCount").asInt(0) <= 0
                    || !plan.path("hardConstraints").path("textVerbatim").asBoolean(false)
                    || !plan.path("hardConstraints").path("dataVerbatim").asBoolean(false)
                    || !"1:1".equals(plan.path("hardConstraints").path("pageMapping").asText())
                    || !plan.path("ignored").isArray()
                    || !plan.path("needsConfirmation").isArray()
                    || !ALLOWED_STRICTNESS.contains(plan.path("validationStrictness").asText())
                    || !validIdentityRecommendation(plan.path("identityRecommendation"))) {
                throw new PptJobStateException(
                        "beautify plan must preserve text, data, and 1:1 page mapping with identity recommendation");
            }
            if (plan.has("identityLock")) {
                throw new PptJobStateException("beautify plan draft must not include identityLock");
            }
            return plan;
        } catch (IOException ex) {
            throw new PptJobStateException("beautify plan must be valid JSON");
        }
    }

    private static boolean validIdentityRecommendation(JsonNode recommendation) {
        if (!recommendation.isObject()) {
            return false;
        }
        String source = recommendation.path("source").asText();
        if (!ALLOWED_IDENTITY_SOURCES.contains(source)) {
            return false;
        }
        JsonNode candidates = recommendation.path("candidates");
        return candidates.isArray() && !candidates.isEmpty();
    }

    private static void rejectForbiddenAnswers(Map<String, Object> answers) {
        for (String key : answers.keySet()) {
            if (FORBIDDEN_ANSWER_KEYS.contains(key) || !ALLOWED_ANSWER_KEYS.contains(key)) {
                throw new PptJobStateException("beautify plan confirmation answers cannot relax hard constraints");
            }
        }
        Object strictness = answers.get("validationStrictness");
        if (strictness != null && (!(strictness instanceof String text) || !ALLOWED_STRICTNESS.contains(text))) {
            throw new PptJobStateException("beautify plan confirmation answers cannot relax hard constraints");
        }
        Object identitySource = answers.get("identitySource");
        if (identitySource != null
                && (!(identitySource instanceof String text) || !ALLOWED_IDENTITY_SOURCES.contains(text))) {
            throw new PptJobStateException("beautify plan confirmation answers cannot relax hard constraints");
        }
        Object resolutions = answers.get("needsConfirmationResolutions");
        if (resolutions != null && !(resolutions instanceof Map<?, ?>) && !(resolutions instanceof List<?>)) {
            throw new PptJobStateException("beautify plan confirmation answers cannot relax hard constraints");
        }
        if (resolutions instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof String strategy) {
                    String normalized = strategy.trim().toLowerCase();
                    if (!(normalized.equals("accept_degradation")
                            || normalized.equals("manual")
                            || normalized.equals("accept")
                            || normalized.equals("degrade"))) {
                        throw new PptJobStateException(
                                "beautify plan confirmation answers cannot relax hard constraints");
                    }
                } else if (value != null && !(value instanceof Map<?, ?>)) {
                    throw new PptJobStateException("beautify plan confirmation answers cannot relax hard constraints");
                }
            }
        }
    }

    private static Map<String, Object> resolveIdentityLock(ObjectNode plan, Map<String, Object> answers) {
        JsonNode recommendation = plan.path("identityRecommendation");
        String source = answers.get("identitySource") instanceof String text && ALLOWED_IDENTITY_SOURCES.contains(text)
                ? text
                : recommendation.path("source").asText();
        if (!candidateExists(recommendation, source)) {
            throw new PptJobStateException("beautify identity source must be listed in draft candidates");
        }
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("source", source);
        lock.put("paletteCandidateId", chooseCandidate(answers, "paletteCandidateId", recommendation, "paletteCandidateId"));
        lock.put("fontCandidateId", chooseCandidate(answers, "fontCandidateId", recommendation, "fontCandidateId"));
        lock.put("bodySizeCandidateId",
                chooseCandidate(answers, "bodySizeCandidateId", recommendation, "bodySizeCandidateId"));
        return lock;
    }

    private static String chooseCandidate(
            Map<String, Object> answers, String answerKey, JsonNode recommendation, String defaultField) {
        if (answers.get(answerKey) instanceof String chosen && !chosen.isBlank()) {
            return chosen.trim();
        }
        String fallback = recommendation.path(defaultField).asText();
        return fallback.isBlank() ? sourceFallback(recommendation) : fallback;
    }

    private static String sourceFallback(JsonNode recommendation) {
        return recommendation.path("source").asText("theme");
    }

    private static boolean candidateExists(JsonNode recommendation, String id) {
        JsonNode candidates = recommendation.path("candidates");
        if (!candidates.isArray()) {
            return false;
        }
        for (JsonNode candidate : candidates) {
            if (id.equals(candidate.path("id").asText()) || id.equals(candidate.asText())) {
                return true;
            }
        }
        return false;
    }

    private static int expectedSourcePageCount(PptJob job) {
        PptNodeExecution analyzed = job.nodeExecution(PptJobNode.BEAUTIFY_ANALYZED);
        if (analyzed != null
                && analyzed.status() == PptJobNodeStatus.COMPLETED
                && analyzed.summary().get("sourcePageCount") instanceof Number number) {
            return number.intValue();
        }
        Path inventory = job.projectPath()
                .map(path -> path.resolve("analysis/beautify_inventory.json"))
                .orElse(null);
        if (inventory == null || !Files.isRegularFile(inventory)) {
            return -1;
        }
        try {
            return OBJECT_MAPPER.readTree(inventory.toFile()).path("slide_count").asInt(-1);
        } catch (IOException ex) {
            return -1;
        }
    }

    private static Path path(PptJob job) {
        Path project = job.projectPath().orElseThrow(() -> new PptJobStateException("project path is missing"));
        Path result = project.resolve("analysis").resolve(PLAN_FILE).normalize();
        if (!result.startsWith(project.normalize())) {
            throw new PptJobStateException("beautify plan path escapes project");
        }
        return result;
    }

    private static byte[] writeBytes(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(node);
        } catch (IOException ex) {
            throw new PptJobStateException("failed to serialize beautify plan");
        }
    }

    private static byte[] canonicalBytes(ObjectNode plan) {
        ObjectNode canonical = plan.deepCopy();
        canonical.remove("digest");
        canonical.remove("status");
        canonical.remove("identityLock");
        canonical.remove("needsConfirmationResolutions");
        canonical.remove("acceptedIgnoredIds");
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
            throw new PptJobStateException("failed to store beautify plan");
        }
    }

    public record PlanMetadata(int version, String digest, String status) {
    }
}
