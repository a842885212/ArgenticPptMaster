package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.exception.PptJobStateException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 校验 fill plan 草案结构与 slide library 引用。 */
@Component
public class TemplateFillPlanValidator {

    public static final int MAX_PREVIEW_CHARS = 500;
    static final int MAX_LIST_ITEMS = 50;
    static final int MAX_LIST_ITEM_CHARS = 500;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNode parseAndValidateDraft(String jsonPlan, Path slideLibraryPath) {
        if (jsonPlan == null || jsonPlan.isBlank()) {
            throw new PptJobStateException("fill plan JSON is required");
        }
        JsonNode plan;
        try {
            plan = objectMapper.readTree(jsonPlan);
        } catch (IOException ex) {
            throw new PptJobStateException("fill plan must be valid JSON");
        }
        if (plan == null || !plan.isObject()) {
            throw new PptJobStateException("fill plan must be a JSON object");
        }
        String status = textOrNull(plan.get("status"));
        if (!"draft".equalsIgnoreCase(status)) {
            throw new PptJobStateException("fill plan status must be draft");
        }
        JsonNode slides = plan.get("slides");
        if (slides == null || !slides.isArray() || slides.isEmpty()) {
            throw new PptJobStateException("fill plan must contain at least one slide");
        }
        Set<Integer> outputOrders = new HashSet<>();
        SlideLibraryIndex index = SlideLibraryIndex.load(slideLibraryPath, objectMapper);
        int previousOrder = 0;
        for (JsonNode slide : slides) {
            int outputOrder = requirePositiveInt(slide.get("outputOrder"), "outputOrder");
            if (!outputOrders.add(outputOrder)) {
                throw new PptJobStateException("fill plan outputOrder must be unique");
            }
            if (outputOrder <= previousOrder) {
                throw new PptJobStateException("fill plan outputOrder must be ascending");
            }
            previousOrder = outputOrder;
            int templateSlideIndex = requirePositiveInt(slide.get("templateSlideIndex"), "templateSlideIndex");
            if (!index.slideIndexes.contains(templateSlideIndex)) {
                throw new PptJobStateException("fill plan references unknown template slide: " + templateSlideIndex);
            }
            JsonNode slotMappings = slide.get("slotMappings");
            if (slotMappings != null && slotMappings.isArray()) {
                for (JsonNode mapping : slotMappings) {
                    String slotId = textOrNull(mapping.get("slotId"));
                    if (slotId == null || !index.slotIds.contains(slotId)) {
                        throw new PptJobStateException("fill plan references unknown slot: " + slotId);
                    }
                    boundString(mapping.get("sourceRef"), "sourceRef", 256);
                    boundString(mapping.get("preview"), "preview", MAX_PREVIEW_CHARS);
                }
            }
            boundString(slide.get("layoutNote"), "layoutNote", MAX_PREVIEW_CHARS);
        }
        requireBoundedList(plan.get("omittedContent"), "omittedContent");
        requireBoundedList(plan.get("capacityRisks"), "capacityRisks");
        requireBoundedList(plan.get("splitSuggestions"), "splitSuggestions");
        requireBoundedList(plan.get("acceptedWarnings"), "acceptedWarnings");
        if (plan.get("tableChartHandling") != null && !plan.get("tableChartHandling").isArray()) {
            throw new PptJobStateException("tableChartHandling must be an array");
        }
        return plan;
    }

    public ObjectNode normalizeDraft(JsonNode plan, int version) {
        ObjectNode normalized = plan.deepCopy();
        normalized.put("status", "draft");
        normalized.put("version", version);
        return normalized;
    }

    private static void requireBoundedList(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            throw new PptJobStateException(field + " must be an array");
        }
        if (node.size() > MAX_LIST_ITEMS) {
            throw new PptJobStateException(field + " exceeds item limit");
        }
        for (JsonNode item : node) {
            boundString(item, field, MAX_LIST_ITEM_CHARS);
        }
    }

    private static int requirePositiveInt(JsonNode node, String field) {
        if (node == null || !node.isInt() || node.asInt() <= 0) {
            throw new PptJobStateException("fill plan " + field + " must be a positive integer");
        }
        return node.asInt();
    }

    private static String boundString(JsonNode node, String field, int maxChars) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new PptJobStateException(field + " must be a string");
        }
        String value = node.asText();
        if (value.length() > maxChars) {
            throw new PptJobStateException(field + " exceeds length limit");
        }
        return value;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private record SlideLibraryIndex(Set<Integer> slideIndexes, Set<String> slotIds) {
        static SlideLibraryIndex load(Path slideLibraryPath, ObjectMapper mapper) {
            if (!Files.isRegularFile(slideLibraryPath)) {
                throw new PptJobStateException("slide library is missing");
            }
            try {
                JsonNode root = mapper.readTree(slideLibraryPath.toFile());
                Set<Integer> slideIndexes = new HashSet<>();
                Set<String> slotIds = new HashSet<>();
                JsonNode slides = root.get("slides");
                if (slides != null && slides.isArray()) {
                    for (JsonNode slide : slides) {
                        JsonNode indexNode = slide.get("slide_index");
                        if (indexNode != null && indexNode.isInt()) {
                            slideIndexes.add(indexNode.asInt());
                        }
                        collectIds(slide.get("slots"), "slot_id", slotIds);
                        collectIds(slide.get("tables"), "table_id", slotIds);
                        collectIds(slide.get("charts"), "chart_id", slotIds);
                    }
                }
                return new SlideLibraryIndex(slideIndexes, slotIds);
            } catch (IOException ex) {
                throw new PptJobStateException("failed to read slide library");
            }
        }

        private static void collectIds(JsonNode nodes, String idField, Set<String> target) {
            if (nodes == null || !nodes.isArray()) {
                return;
            }
            Iterator<JsonNode> iterator = nodes.elements();
            while (iterator.hasNext()) {
                JsonNode node = iterator.next();
                JsonNode idNode = node.get(idField);
                if (idNode != null && idNode.isTextual()) {
                    target.add(idNode.asText());
                }
            }
        }
    }
}
