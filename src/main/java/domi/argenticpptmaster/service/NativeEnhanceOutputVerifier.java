package domi.argenticpptmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Post-export read-back for native-enhance: writes {@code validation/native-enhance-readback.json}.
 */
@Component
public class NativeEnhanceOutputVerifier {

    public static final String REPORT_FILE = "native-enhance-readback.json";
    public static final String SCHEMA = "native_pptx_enhancement_readback.v1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReadbackResult verify(
            Path exportPptx,
            Path validationDir,
            int expectedSlides,
            NativeEnhancePlanStore.ApplySettings settings,
            int notesFileCount,
            int audioFileCount) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("schema", SCHEMA);
        report.put("exportFileName", exportPptx.getFileName().toString());
        report.put("expectedPageCount", expectedSlides);
        ArrayNode warnings = report.putArray("warnings");
        ArrayNode errors = report.putArray("errors");
        ObjectNode modules = report.putObject("enabledModules");
        modules.put("notes", settings.notesEnabled());
        modules.put("audio", settings.audioEnabled());
        modules.put("timings", settings.timingsEnabled());
        modules.put("transitions", settings.transitionsEnabled());

        try {
            report.put("exportFileHash", sha256(exportPptx));
            int actualSlides;
            int notesSlides;
            int mediaParts;
            int transitionMarkers;
            try (ZipFile zip = new ZipFile(exportPptx.toFile())) {
                requireEntry(zip, "ppt/presentation.xml", errors);
                requireEntry(zip, "[Content_Types].xml", errors);
                Document presentation = readXml(zip, "ppt/presentation.xml", errors);
                actualSlides = 0;
                if (presentation != null) {
                    NodeList sldId = presentation.getElementsByTagNameNS("*", "sldId");
                    actualSlides = sldId.getLength();
                }
                if (actualSlides <= 0) {
                    actualSlides = countEntries(zip, name -> name.startsWith("ppt/slides/slide")
                            && name.endsWith(".xml")
                            && !name.contains("_rels"));
                }
                report.put("actualPageCount", actualSlides);
                if (expectedSlides > 0 && actualSlides != expectedSlides) {
                    errors.add(error("slide_count_mismatch",
                            "actual=" + actualSlides + " expected=" + expectedSlides));
                }

                notesSlides = countEntries(zip, name -> name.startsWith("ppt/notesSlides/notesSlide")
                        && name.endsWith(".xml")
                        && !name.contains("_rels"));
                report.put("notesSlideCount", notesSlides);
                report.put("notesFileCount", notesFileCount);
                if (settings.notesEnabled() && notesSlides <= 0 && notesFileCount > 0) {
                    errors.add(error("notes_missing", "notes enabled but no notesSlide parts found"));
                }

                mediaParts = countEntries(zip, name -> name.startsWith("ppt/media/"));
                report.put("mediaPartCount", mediaParts);
                report.put("audioFileCount", audioFileCount);
                if (settings.audioEnabled() && mediaParts <= 0 && audioFileCount > 0) {
                    errors.add(error("media_missing", "audio enabled but no media parts found in export"));
                }

                transitionMarkers = countXmlContains(zip, "p:transition");
                report.put("transitionMarkerCount", transitionMarkers);
                if (settings.transitionsEnabled() && transitionMarkers <= 0) {
                    warnings.add(warning("transition_unverified",
                            "transitions enabled but no p:transition markers found"));
                }
            }

            ObjectNode stability = report.putObject("visibleContentStability");
            stability.put("pageCountMatch", expectedSlides <= 0 || expectedSlides == actualSlides);
            stability.put("slidePartCount", actualSlides);
            stability.put("summary", expectedSlides > 0 && expectedSlides == actualSlides
                    ? "page_count_stable"
                    : "page_count_unchecked_or_mismatch");

            String status = errors.isEmpty()
                    ? (warnings.isEmpty() ? "PASSED" : "PASSED_WITH_WARNINGS")
                    : "FAILED";
            report.put("status", status);
            Path reportPath = validationDir.resolve(REPORT_FILE).normalize();
            Files.createDirectories(validationDir);
            atomicWrite(reportPath, (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            return new ReadbackResult(status, warnings.size(), errors.size(), reportPath,
                    report.path("exportFileHash").asText(""), actualSlides);
        } catch (IOException ex) {
            errors.add(error("unreadable_package", "export package is not a readable PPTX ZIP"));
            report.put("status", "FAILED");
            report.put("actualPageCount", 0);
            try {
                Files.createDirectories(validationDir);
                Path reportPath = validationDir.resolve(REPORT_FILE).normalize();
                atomicWrite(reportPath, (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
                        + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                return new ReadbackResult("FAILED", warnings.size(), errors.size(), reportPath, "", 0);
            } catch (IOException ignored) {
                return new ReadbackResult("FAILED", warnings.size(), Math.max(1, errors.size()), null, "", 0);
            }
        }
    }

    static int countNotesFiles(Path project) throws IOException {
        Path notes = project.resolve("notes");
        if (!Files.isDirectory(notes)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(notes)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .count();
        }
    }

    static int countAudioFiles(Path project) throws IOException {
        Path audio = project.resolve("audio");
        if (!Files.isDirectory(audio)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(audio)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".mp3"))
                    .count();
        }
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
                din.transferTo(java.io.OutputStream.nullOutputStream());
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

    private static ObjectNode error(String code, String message) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("code", code);
        node.put("message", truncate(message, 240));
        return node;
    }

    private static ObjectNode warning(String code, String message) {
        return error(code, message);
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    public record ReadbackResult(
            String status,
            int warningCount,
            int errorCount,
            Path reportPath,
            String exportHash,
            int actualPageCount) {
        public boolean passed() {
            return errorCount == 0 && ("PASSED".equals(status) || "PASSED_WITH_WARNINGS".equals(status));
        }
    }
}
