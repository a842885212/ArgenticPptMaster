// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import domi.argenticpptmaster.config.PptEngineProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Beautify-analysis routing with {@code JavaBeautifyAnalysisEngine} as the production authority.
 *
 * <p>When dual-run is enabled, Python runs as a secondary diagnostic only; mismatches are
 * logged (or thrown when fail-on-mismatch is set) and never replace the Java result or
 * artifact. Absolute {@code source} / {@code source_pptx} paths are ignored in JSON compares.</p>
 */
public final class RoutingBeautifyAnalysisEngine implements BeautifyAnalysisEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingBeautifyAnalysisEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BeautifyAnalysisEngine javaEngine;
    private final BeautifyAnalysisEngine pythonEngine;
    private final PptEngineProperties properties;

    public RoutingBeautifyAnalysisEngine(
            BeautifyAnalysisEngine javaEngine, BeautifyAnalysisEngine pythonEngine) {
        this(javaEngine, pythonEngine, PptEngineProperties.defaults());
    }

    public RoutingBeautifyAnalysisEngine(
            BeautifyAnalysisEngine javaEngine,
            BeautifyAnalysisEngine pythonEngine,
            PptEngineProperties properties) {
        this.javaEngine = Objects.requireNonNull(javaEngine, "javaEngine");
        this.pythonEngine = Objects.requireNonNull(pythonEngine, "pythonEngine");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public EngineResult identify(Path sourcePptx, Duration timeout) {
        EngineResult javaResult = javaEngine.identify(sourcePptx, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose("identify", () -> {
                EngineResult pythonResult = pythonEngine.identify(sourcePptx, timeout);
                log.info(
                        "beautify dual-run identify javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
                if (javaResult.successful() && pythonResult.successful()) {
                    compareJsonOrThrow("identify", javaResult.output(), pythonResult.output());
                }
            });
        }
        return javaResult;
    }

    @Override
    public EngineResult intake(Path sourcePptx, Path analysisOutDir, Duration timeout) {
        EngineResult javaResult = javaEngine.intake(sourcePptx, analysisOutDir, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose("intake", () -> {
                Path dualDir = Files.createTempDirectory(analysisOutDir.getParent(), "dual-python-intake-");
                EngineResult pythonResult = pythonEngine.intake(sourcePptx, dualDir, timeout);
                log.info(
                        "beautify dual-run intake javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
                if (javaResult.successful() && pythonResult.successful()) {
                    String stem = stem(sourcePptx);
                    compareFiles(
                            "intake-identity",
                            analysisOutDir.resolve(stem + ".identity.json"),
                            dualDir.resolve(stem + ".identity.json"));
                    compareFiles(
                            "intake-slide-library",
                            analysisOutDir.resolve(stem + ".slide_library.json"),
                            dualDir.resolve(stem + ".slide_library.json"));
                    compareFiles(
                            "intake-source-profile",
                            analysisOutDir.resolve("source_profile.json"),
                            dualDir.resolve("source_profile.json"));
                }
            });
        }
        return javaResult;
    }

    @Override
    public EngineResult inventory(
            Path slideLibrary, Optional<Path> imageManifest, Path inventoryOut, Duration timeout) {
        EngineResult javaResult = javaEngine.inventory(slideLibrary, imageManifest, inventoryOut, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose("inventory", () -> {
                Path dualOut = Files.createTempFile(
                        inventoryOut != null && inventoryOut.getParent() != null
                                ? inventoryOut.getParent()
                                : Files.createTempDirectory("dual-inventory-"),
                        "dual-python-inventory-",
                        ".json");
                EngineResult pythonResult =
                        pythonEngine.inventory(slideLibrary, imageManifest, dualOut, timeout);
                log.info(
                        "beautify dual-run inventory javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
                if (javaResult.successful() && pythonResult.successful() && inventoryOut != null) {
                    compareFiles("inventory", inventoryOut, dualOut);
                }
                Files.deleteIfExists(dualOut);
            });
        }
        return javaResult;
    }

    private void compareFiles(String label, Path javaPath, Path pythonPath) throws Exception {
        if (!Files.isRegularFile(javaPath) || !Files.isRegularFile(pythonPath)) {
            log.warn("beautify dual-run {} skipped: missing artifact java={} python={}", label, javaPath, pythonPath);
            return;
        }
        compareJsonOrThrow(label, Files.readString(javaPath), Files.readString(pythonPath));
    }

    private void compareJsonOrThrow(String label, String javaJson, String pythonJson) throws Exception {
        JsonNode javaNode = normalize(MAPPER.readTree(javaJson));
        JsonNode pythonNode = normalize(MAPPER.readTree(pythonJson));
        if (!javaNode.equals(pythonNode)) {
            String summary = "beautify dual-run JSON mismatch for " + label;
            if (properties.isDualRunFailOnMismatch()) {
                throw new IllegalStateException(summary);
            }
            log.warn(summary);
        } else {
            log.info("beautify dual-run JSON match for {}", label);
        }
    }

    /** Drop volatile absolute path fields so dual-run compares stay useful across cwd. */
    static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.remove("source");
            copy.remove("source_pptx");
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                copy.set(entry.getKey(), normalize(entry.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            var arr = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                arr.add(normalize(child));
            }
            return arr;
        }
        return node;
    }

    private void dualRunDiagnose(String stage, DualCall call) {
        try {
            call.run();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("beautify dual-run {} diagnostic failed; java remains authority", stage, ex);
        }
    }

    private static String stem(Path pptxPath) {
        String name = pptxPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @FunctionalInterface
    private interface DualCall {
        void run() throws Exception;
    }
}
