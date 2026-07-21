package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.domain.PptJob;
import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Independent OOXML readback verifier for template-fill exports.
 * Uses JDK ZIP + secure XML parsing only (no second write engine).
 */
@Component
public class TemplateFillOutputVerifier {

    public static final String REPORT_FILE = "template-fill-readback.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReadbackResult verify(
            PptJob job,
            Path exportPptx,
            Path fillPlan,
            Path validationDir,
            String planDigest,
            int planVersion) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("schema", "template_fill_readback.v1");
        report.put("planVersion", planVersion);
        report.put("planDigest", planDigest == null ? "" : planDigest);
        ArrayNode warnings = report.putArray("warnings");
        ArrayNode errors = report.putArray("errors");
        try {
            String fileHash = sha256(exportPptx);
            report.put("exportFileHash", fileHash);
            report.put("exportFileName", exportPptx.getFileName().toString());

            JsonNode plan = objectMapper.readTree(fillPlan.toFile());
            int plannedSlides = plan.path("slides").isArray() ? plan.path("slides").size() : 0;
            Integer maxSlides = job.templateConstraints().maxSlides();
            if (maxSlides != null && plannedSlides > maxSlides) {
                errors.add(error("max_slides_exceeded", "planned slides exceed maxSlides"));
            }

            try (ZipFile zip = new ZipFile(exportPptx.toFile())) {
                requireEntry(zip, "ppt/presentation.xml", errors);
                requireEntry(zip, "[Content_Types].xml", errors);
                Document presentation = readXml(zip, "ppt/presentation.xml", errors);
                int actualSlides = 0;
                if (presentation != null) {
                    NodeList sldId = presentation.getElementsByTagNameNS("*", "sldId");
                    actualSlides = sldId.getLength();
                    report.put("actualSlideCount", actualSlides);
                    if (actualSlides != plannedSlides) {
                        errors.add(error("slide_count_mismatch",
                                "actual=" + actualSlides + " planned=" + plannedSlides));
                    }
                }

                int notesCount = countEntries(zip, name -> name.startsWith("ppt/notesSlides/notesSlide")
                        && name.endsWith(".xml")
                        && !name.contains("_rels"));
                int plannedNotes = 0;
                int plannedTables = 0;
                int plannedCharts = 0;
                int plannedTransitions = 0;
                for (JsonNode slide : plan.path("slides")) {
                    String notes = slide.path("notes").asText("");
                    if (notes.isBlank()) {
                        notes = slide.path("speaker_notes").asText("");
                    }
                    if (!notes.isBlank()) {
                        plannedNotes++;
                    }
                    plannedTables += slide.path("table_edits").isArray() ? slide.path("table_edits").size() : 0;
                    plannedCharts += slide.path("chart_edits").isArray() ? slide.path("chart_edits").size() : 0;
                    if (slide.hasNonNull("transition")) {
                        plannedTransitions++;
                    }
                }
                report.put("plannedNotesCount", plannedNotes);
                report.put("actualNotesCount", notesCount);
                if (plannedNotes > 0 && notesCount != plannedNotes) {
                    errors.add(error("notes_count_mismatch",
                            "actual=" + notesCount + " planned=" + plannedNotes));
                }

                int chartParts = countEntries(zip, name -> name.startsWith("ppt/charts/chart") && name.endsWith(".xml"));
                report.put("plannedChartMappings", plannedCharts);
                report.put("chartPartCount", chartParts);
                if (plannedCharts > 0 && chartParts == 0) {
                    errors.add(error("chart_parts_missing", "planned charts but no chart parts found"));
                }

                int tableMarkers = countXmlContains(zip, "a:tbl");
                report.put("plannedTableMappings", plannedTables);
                report.put("tableMarkerCount", tableMarkers);
                if (plannedTables > 0 && tableMarkers == 0) {
                    errors.add(error("table_markers_missing", "planned tables but no table markup found"));
                }

                int transitionMarkers = countXmlContains(zip, "p:transition");
                report.put("plannedTransitions", plannedTransitions);
                report.put("transitionMarkerCount", transitionMarkers);
                if (plannedTransitions > 0 && transitionMarkers == 0) {
                    errors.add(error("transition_missing", "planned transitions but none found in package"));
                }

                int timingMarkers = countXmlContains(zip, "p:timing");
                report.put("timingMarkerCount", timingMarkers);
                if (timingMarkers == 0) {
                    warnings.add(warning("animation_semantics_unverified",
                            "object animation relationships were not positively verified"));
                }
            }

            String status = errors.isEmpty() ? (warnings.isEmpty() ? "PASSED" : "PASSED_WITH_WARNINGS") : "FAILED";
            report.put("status", status);
            Path reportPath = validationDir.resolve(REPORT_FILE).normalize();
            Files.createDirectories(validationDir);
            atomicWrite(reportPath, (objectMapper.writeValueAsString(report) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
            return new ReadbackResult(status, warnings.size(), errors.size(), reportPath, fileHash(report));
        } catch (IOException ex) {
            errors.add(error("unreadable_package", "export package is not a readable PPTX ZIP"));
            report.put("status", "FAILED");
            try {
                Files.createDirectories(validationDir);
                Path reportPath = validationDir.resolve(REPORT_FILE).normalize();
                atomicWrite(reportPath, (objectMapper.writeValueAsString(report) + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // best effort
            }
            throw new PptTemplateFillExecutionException(
                    "READBACK",
                    "template-fill readback failed",
                    TemplateFillErrorCode.TEMPLATE_READBACK_FAILED);
        } finally {
            job.updateReadbackValidation(
                    report.path("status").asText("FAILED"),
                    warnings.size(),
                    errors.size());
        }
    }

    private static String fileHash(ObjectNode report) {
        return report.path("exportFileHash").asText("");
    }

    private static ObjectNode error(String code, String message) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("code", code);
        node.put("message", truncate(message, 240));
        return node;
    }

    private static ObjectNode warning(String code, String message) {
        return error(code, message);
    }

    private static void requireEntry(ZipFile zip, String name, ArrayNode errors) {
        if (zip.getEntry(name) == null) {
            errors.add(error("missing_part", "missing " + name));
        }
    }

    private Document readXml(ZipFile zip, String name, ArrayNode errors) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(in);
        } catch (Exception ex) {
            errors.add(error("xml_parse_failed", "failed to parse " + name));
            return null;
        }
    }

    private static int countEntries(ZipFile zip, java.util.function.Predicate<String> predicate) {
        int count = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (predicate.test(entry.getName())) {
                count++;
            }
        }
        return count;
    }

    private int countXmlContains(ZipFile zip, String marker) throws IOException {
        String local = marker.contains(":") ? marker.substring(marker.indexOf(':') + 1) : marker;
        int count = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.endsWith(".xml") || name.contains("../")) {
                continue;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (xml.contains("<" + marker) || xml.contains(":" + local + " ") || xml.contains(":" + local + ">")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file);
                    DigestInputStream din = new DigestInputStream(in, digest)) {
                din.transferTo(Output.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IOException("failed to hash export", ex);
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    public record ReadbackResult(String status, int warningCount, int errorCount, Path reportPath, String exportHash) {
        public boolean passed() {
            return errorCount == 0 && ("PASSED".equals(status) || "PASSED_WITH_WARNINGS".equals(status));
        }
    }

    private static final class Output {
        static java.io.OutputStream nullOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }
    }
}
