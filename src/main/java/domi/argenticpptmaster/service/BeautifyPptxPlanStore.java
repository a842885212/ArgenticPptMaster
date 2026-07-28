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
import java.util.Optional;

/** 持久化阶段 1 的 beautify 草案，并将确认绑定到规范化后的计划摘要。 */
public final class BeautifyPptxPlanStore {

    private static final String PLAN_FILE = "beautify_plan.draft.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private BeautifyPptxPlanStore() {
    }

    public static PlanMetadata storeDraft(PptJob job, String content) {
        ObjectNode plan = parseDraft(content);
        int version = plan.path("version").asInt();
        plan.remove("digest");
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

    private static Optional<PlanMetadata> read(PptJob job, String expectedStatus) {
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
            if ((expectedStatus != null && !expectedStatus.equals(status))
                    || (!"draft".equals(status) && !"confirmed".equals(status))) {
                return Optional.empty();
            }
            String storedDigest = plan.path("digest").asText();
            String actualDigest = digest(canonicalBytes(plan));
            if (storedDigest.isBlank() || !storedDigest.equals(actualDigest)) {
                return Optional.empty();
            }
            return Optional.of(new PlanMetadata(plan.path("version").asInt(), storedDigest, status));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public static void confirmDraft(PptJob job, int version, String digest) {
        PlanMetadata draft = readDraft(job)
                .orElseThrow(() -> new PptJobStateException("beautify plan draft is missing or invalid"));
        if (draft.version() != version || !draft.digest().equals(digest)) {
            throw new PptJobStateException("beautify plan version or digest mismatch");
        }
        try {
            ObjectNode plan = (ObjectNode) OBJECT_MAPPER.readTree(path(job).toFile());
            plan.put("status", "confirmed");
            atomicWrite(path(job), writeBytes(plan));
        } catch (IOException ex) {
            throw new PptJobStateException("failed to confirm beautify plan");
        }
    }

    private static ObjectNode parseDraft(String content) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(content);
            if (!(parsed instanceof ObjectNode plan)
                    || plan.path("version").asInt(0) <= 0
                    || !"draft".equals(plan.path("status").asText())
                    || !plan.path("hardConstraints").path("textVerbatim").asBoolean(false)
                    || !plan.path("hardConstraints").path("dataVerbatim").asBoolean(false)
                    || !"1:1".equals(plan.path("hardConstraints").path("pageMapping").asText())) {
                throw new PptJobStateException("beautify plan must preserve text, data, and 1:1 page mapping");
            }
            return plan;
        } catch (IOException ex) {
            throw new PptJobStateException("beautify plan must be valid JSON");
        }
    }

    private static Path path(PptJob job) {
        Path project = job.projectPath().orElseThrow(() -> new PptJobStateException("project path is missing"));
        Path result = project.resolve("analysis").resolve(PLAN_FILE).normalize();
        if (!result.startsWith(project)) {
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
