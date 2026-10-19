package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.BeautifyPptxErrorCode;
import domi.argenticpptmaster.domain.BeautifyPptxFailureKind;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.PptJobEvent;
import domi.argenticpptmaster.domain.PptJobEventType;
import domi.argenticpptmaster.domain.PptJobNode;
import domi.argenticpptmaster.domain.PptJobNodeStatus;
import domi.argenticpptmaster.engine.EngineResult;
import domi.argenticpptmaster.engine.PptxMarkdownReadback;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/** Beautify 导出后的服务端 read-back 校验。 */
@Component
public class BeautifyPptxOutputVerifier {

    public static final String REPORT_FILE = "beautify-readback.json";
    private static final Pattern SLIDE_HEADER = Pattern.compile("^##\\s+Slide\\s+(\\d+)\\s*$", Pattern.MULTILINE);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PptxMarkdownReadback readback;
    private final PptWorkflowEvents events;

    public BeautifyPptxOutputVerifier(PptxMarkdownReadback readback, PptWorkflowEvents events) {
        this.readback = readback;
        this.events = events;
    }

    public ReadbackResult verify(PptJob job, Path exportPptx, Path projectPath, Duration timeout) {
        ObjectNode report = OBJECT_MAPPER.createObjectNode();
        report.put("schema", "beautify_readback.v1");
        report.put("exportFileName", exportPptx.getFileName().toString());
        ArrayNode warnings = report.putArray("warnings");
        ArrayNode errors = report.putArray("errors");
        Path validationDir = projectPath.resolve("validation");
        Path reportPath = validationDir.resolve(REPORT_FILE);
        boolean pageCountMatch = false;
        boolean textFidelityPassed = false;
        boolean dataFidelityPassed = false;
        try {
            JsonNode inventory = OBJECT_MAPPER.readTree(
                    projectPath.resolve("analysis/beautify_inventory.json").toFile());
            int expectedPages = inventory.path("slide_count").asInt(0);
            int actualPages = countSlides(exportPptx, errors);
            report.put("expectedPageCount", expectedPages);
            report.put("actualPageCount", actualPages);
            pageCountMatch = expectedPages > 0 && expectedPages == actualPages;
            if (!pageCountMatch) {
                errors.add(error("slide_count_mismatch",
                        "actual=" + actualPages + " expected=" + expectedPages));
            }

            Path markdown = validationDir.resolve("beautify-readback.md");
            Files.createDirectories(validationDir);
            runReadback(exportPptx, markdown, timeout, errors);
            Map<Integer, String> slides = parseSlides(markdown);
            if (pageCountMatch && slides.size() != expectedPages) {
                errors.add(error("readback_slide_parse_mismatch",
                        "parsed=" + slides.size() + " expected=" + expectedPages));
                pageCountMatch = false;
            }

            textFidelityPassed = pageCountMatch;
            dataFidelityPassed = pageCountMatch;
            String strictness = BeautifyPptxPlanStore.readPlan(job)
                    .map(plan -> plan.path("validationStrictness").asText("strict"))
                    .orElse("strict");
            for (int index = 0; index < inventory.path("slides").size(); index++) {
                int page = index + 1;
                JsonNode slide = inventory.path("slides").get(index);
                String body = slides.getOrDefault(page, "");
                Map<String, Integer> expected = expectedTexts(slide);
                for (Map.Entry<String, Integer> entry : expected.entrySet()) {
                    if (containsCount(body, entry.getKey()) < entry.getValue()) {
                        textFidelityPassed = false;
                        dataFidelityPassed = false;
                        errors.add(error("fidelity_mismatch", "page=" + page));
                        break;
                    }
                }
                if (hasEntries(slide, "images") && !body.toLowerCase(Locale.ROOT).contains("image")) {
                    warnings.add(warning("image_presence_unverified", "page=" + page));
                }
            }
            if ("strict".equals(strictness)) {
                ArrayNode escalated = OBJECT_MAPPER.createArrayNode();
                ArrayNode remaining = OBJECT_MAPPER.createArrayNode();
                for (int i = 0; i < warnings.size(); i++) {
                    String code = warnings.get(i).path("code").asText();
                    if ("fidelity_soft_mismatch".equals(code) || "chart_style_degraded".equals(code)) {
                        escalated.add(warnings.get(i).deepCopy());
                    } else {
                        remaining.add(warnings.get(i).deepCopy());
                    }
                }
                warnings.removeAll();
                warnings.addAll(remaining);
                errors.addAll(escalated);
            }

            String status = errors.isEmpty()
                    ? (warnings.isEmpty() ? "PASSED" : "PASSED_WITH_WARNINGS")
                    : "FAILED";
            report.put("status", status);
            report.put("pageCountMatch", pageCountMatch);
            report.put("textFidelityPassed", textFidelityPassed && errors.isEmpty());
            report.put("dataFidelityPassed", dataFidelityPassed && errors.isEmpty());
            atomicWrite(reportPath, (OBJECT_MAPPER.writeValueAsString(report) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
            job.updateReadbackValidation(status, warnings.size(), errors.size());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", status);
            summary.put("pageCountMatch", pageCountMatch);
            summary.put("textFidelityPassed", textFidelityPassed && errors.isEmpty());
            summary.put("dataFidelityPassed", dataFidelityPassed && errors.isEmpty());
            summary.put("report", "validation/" + REPORT_FILE);
            summary.put("warningCount", warnings.size());
            summary.put("errorCount", errors.size());
            if ("PASSED".equals(status) || "PASSED_WITH_WARNINGS".equals(status)) {
                completeValidationNode(job, summary);
            } else {
                events.record(job, PptJobEvent.of(PptJobEventType.NODE_FAILED,
                        "beautify output validation failed",
                        Map.of("node", PptJobNode.BEAUTIFY_OUTPUT_VALIDATED.name(), "summary", summary,
                                "errorCode", BeautifyPptxErrorCode.BEAUTIFY_READBACK_FAILED.code())));
            }
            return new ReadbackResult(status, warnings.size(), errors.size(), reportPath);
        } catch (IOException ex) {
            errors.add(error("unreadable_package", "export package or inventory cannot be read"));
            report.put("status", "FAILED");
            report.put("pageCountMatch", false);
            report.put("textFidelityPassed", false);
            report.put("dataFidelityPassed", false);
            try {
                Files.createDirectories(validationDir);
                atomicWrite(reportPath, (OBJECT_MAPPER.writeValueAsString(report) + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // best effort
            }
            job.updateReadbackValidation("FAILED", warnings.size(), errors.size());
            throw new BeautifyPptxAnalysisException("readback", BeautifyPptxErrorCode.BEAUTIFY_READBACK_FAILED,
                    BeautifyPptxFailureKind.INTERNAL, "beautify readback failed", ex);
        }
    }

    private void completeValidationNode(PptJob job, Map<String, Object> summary) {
        if (job.nodeExecution(PptJobNode.BEAUTIFY_OUTPUT_VALIDATED) == null
                || job.nodeExecution(PptJobNode.BEAUTIFY_OUTPUT_VALIDATED).status() == PptJobNodeStatus.PENDING) {
            job.startNode(PptJobNode.BEAUTIFY_OUTPUT_VALIDATED);
            events.record(job, PptJobEvent.of(PptJobEventType.NODE_STARTED,
                    "node started: " + PptJobNode.BEAUTIFY_OUTPUT_VALIDATED.name(),
                    Map.of("node", PptJobNode.BEAUTIFY_OUTPUT_VALIDATED.name())));
        }
        job.completeNode(PptJobNode.BEAUTIFY_OUTPUT_VALIDATED, summary);
        events.record(job, PptJobEvent.of(PptJobEventType.NODE_COMPLETED,
                "node completed: " + PptJobNode.BEAUTIFY_OUTPUT_VALIDATED.name(),
                Map.of("node", PptJobNode.BEAUTIFY_OUTPUT_VALIDATED.name(), "summary", summary)));
    }

    private void runReadback(Path exportPptx, Path markdown, Duration timeout, ArrayNode errors) {
        EngineResult result = readback.toMarkdown(exportPptx, markdown, timeout);
        if (!result.successful()) {
            errors.add(error("readback_command_failed", "ppt_to_md failed"));
            throw new BeautifyPptxAnalysisException("readback", BeautifyPptxErrorCode.BEAUTIFY_READBACK_FAILED,
                    result.timedOut() ? BeautifyPptxFailureKind.TIMEOUT : BeautifyPptxFailureKind.PROCESS_EXIT,
                    "ppt_to_md failed");
        }
    }

    private static int countSlides(Path exportPptx, ArrayNode errors) throws IOException {
        try (ZipFile zip = new ZipFile(exportPptx.toFile())) {
            ZipEntry presentation = zip.getEntry("ppt/presentation.xml");
            if (presentation == null) {
                errors.add(error("missing_part", "missing ppt/presentation.xml"));
                return 0;
            }
            Document document = readXml(zip, presentation, errors);
            if (document == null) {
                return 0;
            }
            NodeList sldId = document.getElementsByTagNameNS("*", "sldId");
            return sldId.getLength();
        }
    }

    private static Document readXml(ZipFile zip, ZipEntry entry, ArrayNode errors) {
        try (InputStream in = zip.getInputStream(entry)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(in);
        } catch (Exception ex) {
            errors.add(error("xml_parse_failed", "failed to parse presentation.xml"));
            return null;
        }
    }

    private static Map<Integer, String> parseSlides(Path markdown) throws IOException {
        if (!Files.isRegularFile(markdown)) {
            return Map.of();
        }
        String content = Files.readString(markdown);
        Matcher matcher = SLIDE_HEADER.matcher(content);
        List<Integer> starts = new ArrayList<>();
        List<Integer> pages = new ArrayList<>();
        while (matcher.find()) {
            pages.add(Integer.parseInt(matcher.group(1)));
            starts.add(matcher.start());
        }
        Map<Integer, String> result = new LinkedHashMap<>();
        for (int index = 0; index < pages.size(); index++) {
            int from = starts.get(index);
            int to = index + 1 < starts.size() ? starts.get(index + 1) : content.length();
            result.put(pages.get(index), content.substring(from, to));
        }
        return result;
    }

    private static Map<String, Integer> expectedTexts(JsonNode slide) {
        Map<String, Integer> expected = new HashMap<>();
        for (JsonNode text : iterable(slide.path("text_blocks"))) {
            JsonNode value = text.isObject() ? text.path("text") : text;
            if (value.isTextual() && !value.asText().isBlank()) {
                expected.merge(value.asText(), 1, Integer::sum);
            }
        }
        for (JsonNode table : iterable(slide.path("tables"))) {
            for (JsonNode row : iterable(table.path("rows"))) {
                for (JsonNode cell : iterable(row)) {
                    String value = cell.isTextual() ? cell.asText() : cell.path("text").asText("");
                    if (!value.isBlank()) {
                        expected.merge(value, 1, Integer::sum);
                    }
                }
            }
        }
        for (JsonNode chart : iterable(slide.path("charts"))) {
            for (JsonNode category : iterable(chart.path("categories"))) {
                if (category.isTextual() && !category.asText().isBlank()) {
                    expected.merge(category.asText(), 1, Integer::sum);
                }
            }
            for (JsonNode series : iterable(chart.path("series"))) {
                String name = series.path("name").asText("");
                if (!name.isBlank()) {
                    expected.merge(name, 1, Integer::sum);
                }
                for (JsonNode value : iterable(series.path("values"))) {
                    if (value.isNumber() || value.isTextual()) {
                        expected.merge(value.asText(), 1, Integer::sum);
                    }
                }
            }
        }
        return expected;
    }

    static int containsCount(String body, String needle) {
        if (needle == null || needle.isEmpty() || body == null || body.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = body.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private static boolean hasEntries(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isArray() && !value.isEmpty();
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return node;
    }

    private static ObjectNode error(String code, String message) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("code", code);
        node.put("message", truncate(message, 240));
        return node;
    }

    private static ObjectNode warning(String code, String message) {
        return error(code, message);
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    public record ReadbackResult(String status, int warningCount, int errorCount, Path reportPath) {
        public boolean passed() {
            return errorCount == 0 && ("PASSED".equals(status) || "PASSED_WITH_WARNINGS".equals(status));
        }
    }
}
