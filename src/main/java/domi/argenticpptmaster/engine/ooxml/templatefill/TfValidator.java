// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.ooxml.templatefill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.engine.ooxml.MarkdownNotes;
import domi.argenticpptmaster.engine.ooxml.PptxPackage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Validate the latest template-fill export against the confirmed plan.
 *
 * <p>Uses OOXML text extraction instead of Python {@code ppt_to_md}, writing the same
 * {@code template_fill_pptx_validate.v1} report schema.</p>
 */
public final class TfValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TfValidator() {}

    public static ObjectNode validateProject(Path projectPath) throws Exception {
        Path planPath = projectPath.resolve("analysis/fill_plan.json");
        if (!Files.isRegularFile(planPath)) {
            throw new IllegalStateException("Missing fill plan: " + planPath);
        }
        JsonNode plan = MAPPER.readTree(planPath.toFile());
        Path outputPath = latestExport(projectPath);
        Path validationDir = projectPath.resolve("validation");
        Files.createDirectories(validationDir);
        Path readbackPath = validationDir.resolve("readback.md");

        List<SlideReadback> slides = extractReadback(outputPath);
        String markdown = toMarkdown(slides);
        Files.writeString(readbackPath, markdown, StandardCharsets.UTF_8);

        ArrayNode results = MAPPER.createArrayNode();
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("ok", 0);
        summary.put("warn", 0);
        summary.put("error", 0);

        int expectedSlides = plan.path("slides").isArray() ? plan.path("slides").size() : 0;
        if (expectedSlides == slides.size()) {
            summary.put("ok", summary.get("ok").asInt() + 1);
        } else {
            summary.put("error", summary.get("error").asInt() + 1);
            ObjectNode item = MAPPER.createObjectNode();
            item.put("status", "ERROR");
            item.put("code", "slide_count_mismatch");
            item.put("expected", expectedSlides);
            item.put("actual", slides.size());
            item.put("message", "read-back slide count does not match fill_plan.slides");
            results.add(item);
        }

        JsonNode library = firstLibrary(projectPath);
        int planSlide = 0;
        for (JsonNode slide : plan.path("slides")) {
            planSlide++;
            SlideReadback readback = planSlide <= slides.size() ? slides.get(planSlide - 1) : null;
            if (readback == null) {
                summary.put("error", summary.get("error").asInt() + 1);
                ObjectNode item = MAPPER.createObjectNode();
                item.put("status", "ERROR");
                item.put("code", "slide_missing_in_readback");
                item.put("plan_slide", planSlide);
                item.put("message", "planned slide section is missing from read-back Markdown");
                results.add(item);
                continue;
            }
            checkReplacements(results, summary, library, slide, planSlide, readback.body);
            checkTableCells(results, summary, library, slide, planSlide, readback.body);
            checkChartTexts(results, summary, library, slide, planSlide, readback.body);
            String plannedNotes = slide.path("notes").asText(slide.path("speaker_notes").asText(""));
            List<String> plannedNoteLines = paragraphLines(MarkdownNotes.toPlainText(plannedNotes));
            if (!plannedNoteLines.isEmpty()) {
                if (containsParagraphs(readback.notes, plannedNotes)) {
                    summary.put("ok", summary.get("ok").asInt() + 1);
                } else {
                    summary.put("error", summary.get("error").asInt() + 1);
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("status", "ERROR");
                    item.put("code", "notes_missing_in_readback");
                    item.put("plan_slide", planSlide);
                    item.put("message", "planned speaker notes not found on the corresponding read-back slide");
                    item.put("text", plannedNotes);
                    results.add(item);
                }
            } else if (!readback.notes.isBlank()) {
                summary.put("warn", summary.get("warn").asInt() + 1);
                ObjectNode item = MAPPER.createObjectNode();
                item.put("status", "WARN");
                item.put("code", "unexpected_notes_in_readback");
                item.put("plan_slide", planSlide);
                item.put(
                        "message",
                        "read-back slide contains speaker notes although the plan slide has no notes field");
                results.add(item);
            }
        }

        ObjectNode report = MAPPER.createObjectNode();
        report.put("schema", TfConstants.VALIDATE_SCHEMA);
        report.put("project", projectPath.toAbsolutePath().normalize().toString());
        report.put("export", outputPath.toAbsolutePath().normalize().toString());
        report.put("readback", readbackPath.toAbsolutePath().normalize().toString());
        report.set("summary", summary);
        report.set("results", results);
        Path reportPath = validationDir.resolve("validate_report.json");
        Files.writeString(reportPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        return report;
    }

    private static void checkReplacements(
            ArrayNode results,
            ObjectNode summary,
            JsonNode library,
            JsonNode slide,
            int planSlide,
            String body) {
        int sourceSlide = slide.path("source_slide").asInt(0);
        for (JsonNode replacement : slide.path("replacements")) {
            if (!replacement.isObject()) {
                continue;
            }
            String text = TfSelectors.replacementText(replacement);
            if (paragraphLines(text).isEmpty()) {
                continue;
            }
            if (replacement.path("optional").asBoolean(false)
                    && library != null
                    && matchLibrary(library, sourceSlide, TfSelectors.replacementSelectors(replacement), "slots")
                            == null) {
                continue;
            }
            if (containsParagraphs(body, text)) {
                summary.put("ok", summary.get("ok").asInt() + 1);
            } else {
                summary.put("error", summary.get("error").asInt() + 1);
                ObjectNode item = MAPPER.createObjectNode();
                item.put("status", "ERROR");
                item.put("code", "body_text_missing_in_readback");
                item.put("plan_slide", planSlide);
                item.put("message", "replacement text not found on the corresponding read-back slide");
                item.put("text", text);
                results.add(item);
            }
        }
    }

    private static void checkTableCells(
            ArrayNode results,
            ObjectNode summary,
            JsonNode library,
            JsonNode slide,
            int planSlide,
            String body) {
        int sourceSlide = slide.path("source_slide").asInt(0);
        for (JsonNode tableEdit : slide.path("table_edits")) {
            if (tableEdit.path("optional").asBoolean(false)
                    && library != null
                    && matchLibrary(library, sourceSlide, TfSelectors.tableSelectors(tableEdit), "tables") == null) {
                continue;
            }
            for (JsonNode cell : tableEdit.path("cells")) {
                String text = TfSelectors.tableCellText(cell);
                if (paragraphLines(text).isEmpty()) {
                    continue;
                }
                if (containsParagraphs(body, text)) {
                    summary.put("ok", summary.get("ok").asInt() + 1);
                } else {
                    summary.put("error", summary.get("error").asInt() + 1);
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("status", "ERROR");
                    item.put("code", "table_text_missing_in_readback");
                    item.put("plan_slide", planSlide);
                    item.put("message", "table cell text not found on the corresponding read-back slide");
                    item.put("text", text);
                    results.add(item);
                }
            }
        }
    }

    private static void checkChartTexts(
            ArrayNode results,
            ObjectNode summary,
            JsonNode library,
            JsonNode slide,
            int planSlide,
            String body) {
        int sourceSlide = slide.path("source_slide").asInt(0);
        for (JsonNode chartEdit : slide.path("chart_edits")) {
            if (chartEdit.path("optional").asBoolean(false)
                    && library != null
                    && matchLibrary(library, sourceSlide, TfSelectors.chartSelectors(chartEdit), "charts") == null) {
                continue;
            }
            List<String> tokens = new ArrayList<>();
            for (JsonNode category : chartEdit.path("categories")) {
                if (!category.asText("").isBlank()) {
                    tokens.add(category.asText());
                }
            }
            for (JsonNode series : chartEdit.path("series")) {
                String name = series.path("name").asText("").strip();
                if (!name.isEmpty()) {
                    tokens.add(name);
                }
                for (JsonNode value : series.path("values")) {
                    String text = formatChartValue(value);
                    if (!text.isBlank()) {
                        tokens.add(text);
                    }
                }
            }
            for (String text : tokens) {
                if (containsParagraphs(body, text)) {
                    summary.put("ok", summary.get("ok").asInt() + 1);
                } else {
                    summary.put("warn", summary.get("warn").asInt() + 1);
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("status", "WARN");
                    item.put("code", "chart_text_missing_in_readback");
                    item.put("plan_slide", planSlide);
                    item.put("message", "chart text not found on the corresponding read-back slide");
                    item.put("text", text);
                    results.add(item);
                }
            }
        }
    }

    private static String formatChartValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isFloatingPointNumber() && value.asDouble() == Math.rint(value.asDouble())) {
            return String.valueOf((int) value.asDouble());
        }
        return value.asText();
    }

    private static JsonNode matchLibrary(JsonNode library, int sourceSlide, List<String> selectors, String field) {
        for (JsonNode slide : library.path("slides")) {
            if (slide.path("slide_index").asInt(0) != sourceSlide) {
                continue;
            }
            for (JsonNode item : slide.path(field)) {
                for (String selector : selectors) {
                    if (selector.startsWith("slot_id:") && selector.substring(8).equals(item.path("slot_id").asText())) {
                        return item;
                    }
                    if (selector.startsWith("table_id:")
                            && selector.substring(9).equals(item.path("table_id").asText())) {
                        return item;
                    }
                    if (selector.startsWith("chart_id:")
                            && selector.substring(9).equals(item.path("chart_id").asText())) {
                        return item;
                    }
                    if (selector.startsWith("shape_id:")
                            && selector.substring(9).equals(item.path("shape_id").asText())) {
                        return item;
                    }
                    if (selector.startsWith("shape_name:")
                            && selector.substring(11).equals(item.path("shape_name").asText())) {
                        return item;
                    }
                }
            }
        }
        return null;
    }

    private static Path latestExport(Path projectPath) throws Exception {
        Path exportsDir = projectPath.resolve("exports");
        if (!Files.isDirectory(exportsDir)) {
            throw new IllegalStateException("No PPTX exports found in: " + exportsDir);
        }
        try (Stream<Path> stream = Files.list(exportsDir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".pptx"))
                    .max(Comparator.comparingLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (Exception ex) {
                            return 0L;
                        }
                    }))
                    .orElseThrow(() -> new IllegalStateException("No PPTX exports found in: " + exportsDir));
        }
    }

    private static JsonNode firstLibrary(Path projectPath) throws Exception {
        Path analysis = projectPath.resolve("analysis");
        if (!Files.isDirectory(analysis)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(analysis)) {
            Path library = stream
                    .filter(path -> path.getFileName().toString().endsWith(".slide_library.json"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
            return library == null ? null : MAPPER.readTree(library.toFile());
        }
    }

    private static List<SlideReadback> extractReadback(Path pptx) throws Exception {
        Path extractDir = Files.createTempDirectory("java-tf-validate-");
        try {
            PptxPackage.extract(pptx, extractDir);
            List<domi.argenticpptmaster.engine.ooxml.SlidePartRef> slides =
                    domi.argenticpptmaster.engine.ooxml.SlideParts.read(extractDir);
            List<SlideReadback> readbacks = new ArrayList<>();
            for (var slide : slides) {
                Document slideDoc = TfXml.parse(Files.readAllBytes(extractDir.resolve(slide.partName())));
                String body = String.join("\n", TfShapes.paragraphTexts(slideDoc.getDocumentElement()));
                String notes = "";
                Path rels = extractDir.resolve(TfXml.relsNameForPart(slide.partName()));
                if (Files.isRegularFile(rels)) {
                    Map<String, Map<String, String>> relationships =
                            TfXml.parseRelationships(Files.readAllBytes(rels));
                    for (Map<String, String> rel : relationships.values()) {
                        if (domi.argenticpptmaster.engine.ooxml.OoxmlConstants.NOTES_SLIDE_REL_TYPE.equals(
                                rel.get("type"))) {
                            String notesPart = TfXml.normalizePart(rel.get("target"), slide.partName());
                            Path notesPath = extractDir.resolve(notesPart);
                            if (Files.isRegularFile(notesPath)) {
                                Document notesDoc = TfXml.parse(Files.readAllBytes(notesPath));
                                notes = String.join("\n", TfShapes.paragraphTexts(notesDoc.getDocumentElement()));
                            }
                        }
                    }
                }
                // Also collect table cell text into body for table checks
                StringBuilder bodyBuilder = new StringBuilder(body);
                for (Element table : TfShapes.tableContainers(slideDoc.getDocumentElement())) {
                    for (String paragraph : TfShapes.paragraphTexts(table)) {
                        if (!paragraph.isBlank()) {
                            if (bodyBuilder.length() > 0) {
                                bodyBuilder.append('\n');
                            }
                            bodyBuilder.append(paragraph);
                        }
                    }
                }
                readbacks.add(new SlideReadback(bodyBuilder.toString(), notes));
            }
            return readbacks;
        } finally {
            try (Stream<Path> walk = Files.walk(extractDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static String toMarkdown(List<SlideReadback> slides) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < slides.size(); i++) {
            out.append("## Slide ").append(i + 1).append("\n\n");
            out.append(slides.get(i).body).append("\n\n");
            if (!slides.get(i).notes.isBlank()) {
                out.append("### Speaker Notes\n\n");
                out.append(slides.get(i).notes).append("\n\n");
            }
        }
        return out.toString();
    }

    private static boolean containsParagraphs(String haystack, String value) {
        List<String> expected = paragraphLines(MarkdownNotes.toPlainText(value));
        if (expected.isEmpty()) {
            return true;
        }
        List<String> actual = paragraphLines(haystack);
        if (expected.size() > actual.size()) {
            // also allow single-line contains
            String joined = String.join(" ", actual);
            return expected.stream().allMatch(joined::contains);
        }
        for (int start = 0; start <= actual.size() - expected.size(); start++) {
            boolean match = true;
            for (int offset = 0; offset < expected.size(); offset++) {
                if (!actual.get(start + offset).equals(expected.get(offset))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        String joined = String.join(" ", actual);
        return expected.stream().allMatch(joined::contains);
    }

    private static List<String> paragraphLines(String value) {
        if (value == null) {
            return List.of();
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String cleaned = WHITESPACE.matcher(line).replaceAll(" ").strip();
            if (!cleaned.isEmpty()) {
                lines.add(cleaned);
            }
        }
        return lines;
    }

    private record SlideReadback(String body, String notes) {}
}
