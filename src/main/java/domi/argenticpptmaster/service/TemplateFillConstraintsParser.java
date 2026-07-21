package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import domi.argenticpptmaster.domain.TemplateFillConstraints;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Parses and validates optional templateConstraints JSON from multipart create requests. */
@Component
public class TemplateFillConstraintsParser {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "allowedTemplateSlides",
            "excludedTemplateSlides",
            "preserveCover",
            "preserveEnding",
            "maxSlides");
    private static final int MAX_JSON_CHARS = 8_192;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public TemplateFillConstraints parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return TemplateFillConstraints.empty();
        }
        if (rawJson.length() > MAX_JSON_CHARS) {
            throw new PptJobStateException("templateConstraints exceeds size limit");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            throw new PptJobStateException("templateConstraints must be valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new PptJobStateException("templateConstraints must be a JSON object");
        }
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new PptJobStateException("templateConstraints contains unknown field: " + field);
            }
        }
        try {
            return new TemplateFillConstraints(
                    readPositiveIntList(root.get("allowedTemplateSlides"), "allowedTemplateSlides"),
                    readPositiveIntList(root.get("excludedTemplateSlides"), "excludedTemplateSlides"),
                    readBoolean(root.get("preserveCover"), false),
                    readBoolean(root.get("preserveEnding"), false),
                    readOptionalPositiveInt(root.get("maxSlides"), "maxSlides"));
        } catch (IllegalArgumentException ex) {
            throw new PptJobStateException(ex.getMessage());
        }
    }

    private static List<Integer> readPositiveIntList(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new PptJobStateException(field + " must be an array");
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.canConvertToInt()) {
                throw new PptJobStateException(field + " must contain integers");
            }
            values.add(item.asInt());
        }
        return values;
    }

    private static boolean readBoolean(JsonNode node, boolean defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new PptJobStateException("boolean field has invalid type");
        }
        return node.asBoolean();
    }

    private static Integer readOptionalPositiveInt(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt() || node.asInt() <= 0) {
            throw new PptJobStateException(field + " must be a positive integer");
        }
        return node.asInt();
    }
}
