package domi.argenticpptmaster.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.PptOutline;
import domi.argenticpptmaster.domain.SlideOutline;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从锁定的逐页大纲派生并校验图片生成清单。 */
public final class PptImageManifestStore {
    private static final String RELATIVE_PATH = "images/image_prompts.json";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PptOutlineStore outlineStore = new PptOutlineStore();

    public Path path(Path projectPath) {
        return projectPath.resolve(RELATIVE_PATH);
    }

    public Map<String, Object> writeFromLockedOutline(Path projectPath) {
        PptOutline outline = outlineStore.read(projectPath);
        if (!outline.locked()) {
            throw new IllegalStateException("image manifest requires a locked outline");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (SlideOutline slide : outline.slides()) {
            if (slide.imageRequirement() == null) {
                continue;
            }
            items.add(item(outline.version(), slide));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("outlineVersion", outline.version());
        manifest.put("items", items);
        write(projectPath, manifest);
        return Map.copyOf(manifest);
    }

    public Map<String, Object> readForOutlineVersion(Path projectPath, int outlineVersion) {
        Path target = path(projectPath);
        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("image prompts manifest not found: " + target);
        }
        try {
            Map<String, Object> manifest = objectMapper.readValue(target.toFile(), new TypeReference<>() {});
            Object version = manifest.get("outlineVersion");
            if (!(version instanceof Number number) || number.intValue() != outlineVersion) {
                throw new IllegalStateException("image manifest outlineVersion does not match locked outline");
            }
            if (!(manifest.get("items") instanceof List<?> items)) {
                throw new IllegalStateException("image manifest items are invalid");
            }
            for (Object item : items) {
                validateItem(item, outlineVersion);
            }
            return Map.copyOf(manifest);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("image manifest is invalid", ex);
        }
    }

    private Map<String, Object> item(int outlineVersion, SlideOutline slide) {
        Map<String, Object> requirement = slide.imageRequirement();
        String purpose = required(requirement, "purpose");
        String prompt = required(requirement, "prompt");
        String aspectRatio = optional(requirement, "aspectRatio", "16:9");
        validateOptional(requirement, "altText");
        validateOptional(requirement, "style");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("outlineVersion", outlineVersion);
        item.put("slideNo", slide.slideNo());
        item.put("requirementFingerprint", fingerprint(slide.slideNo() + "\n" + purpose + "\n" + prompt + "\n" + aspectRatio));
        item.put("filename", "slide-%02d-image.png".formatted(slide.slideNo()));
        item.put("purpose", purpose);
        item.put("prompt", prompt);
        item.put("aspect_ratio", aspectRatio);
        item.put("status", "Pending");
        copyOptional(requirement, item, "altText", "alt_text");
        copyOptional(requirement, item, "style", "style");
        return item;
    }

    private static void validateItem(Object candidate, int outlineVersion) {
        if (!(candidate instanceof Map<?, ?> item)) {
            throw new IllegalStateException("image manifest item is invalid");
        }
        Object itemVersion = item.get("outlineVersion");
        if (!(itemVersion instanceof Number number) || number.intValue() != outlineVersion) {
            throw new IllegalStateException("image manifest item outlineVersion does not match locked outline");
        }
        Object slideNo = item.get("slideNo");
        if (!(slideNo instanceof Number slideNumber) || slideNumber.intValue() <= 0) {
            throw new IllegalStateException("image manifest item slideNo is invalid");
        }
        requireManifestText(item, "filename");
        requireManifestText(item, "prompt");
        requireManifestText(item, "aspect_ratio");
        requireManifestText(item, "status");
    }

    private static void requireManifestText(Map<?, ?> item, String field) {
        Object value = item.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("image manifest item " + field + " is invalid");
        }
    }

    private void write(Path projectPath, Map<String, Object> manifest) {
        Path target = path(projectPath);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "image-prompts-", ".json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to persist image manifest", ex);
        }
    }

    private static String required(Map<String, Object> requirement, String field) {
        String value = optional(requirement, field, null);
        if (value == null) {
            throw new IllegalArgumentException("imageRequirement." + field + " is required");
        }
        return value;
    }

    private static String optional(Map<String, Object> requirement, String field, String fallback) {
        Object value = requirement.get(field);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("imageRequirement." + field + " must be a non-blank string");
        }
        return text.trim();
    }

    private static void validateOptional(Map<String, Object> requirement, String field) {
        optional(requirement, field, null);
    }

    private static void copyOptional(Map<String, Object> source, Map<String, Object> target, String sourceField, String targetField) {
        String value = optional(source, sourceField, null);
        if (value != null) {
            target.put(targetField, value);
        }
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte current : digest) {
                result.append("%02x".formatted(current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
