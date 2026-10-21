// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.config.PptEngineProperties;
import domi.argenticpptmaster.engine.compare.PackageXmlComparator;
import domi.argenticpptmaster.engine.compare.SlideXmlComparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native-enhance routing with {@code JavaNativeEnhanceEngine} as the production authority.
 *
 * <p>When dual-run is enabled, Python runs as a secondary diagnostic only; mismatches are
 * logged (or thrown when fail-on-mismatch is set) and never replace the Java result or
 * artifact.</p>
 */
public final class RoutingNativeEnhanceEngine implements NativeEnhanceEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingNativeEnhanceEngine.class);

    private final NativeEnhanceEngine javaEngine;
    private final NativeEnhanceEngine pythonEngine;
    private final PptEngineProperties properties;
    private final PackageXmlComparator packageComparator;
    private final SlideXmlComparator slideComparator;

    public RoutingNativeEnhanceEngine(NativeEnhanceEngine javaEngine, NativeEnhanceEngine pythonEngine) {
        this(javaEngine, pythonEngine, PptEngineProperties.defaults());
    }

    public RoutingNativeEnhanceEngine(
            NativeEnhanceEngine javaEngine, NativeEnhanceEngine pythonEngine, PptEngineProperties properties) {
        this(javaEngine, pythonEngine, properties, new PackageXmlComparator(), new SlideXmlComparator());
    }

    public RoutingNativeEnhanceEngine(
            NativeEnhanceEngine javaEngine,
            NativeEnhanceEngine pythonEngine,
            PptEngineProperties properties,
            PackageXmlComparator packageComparator,
            SlideXmlComparator slideComparator) {
        this.javaEngine = Objects.requireNonNull(javaEngine, "javaEngine");
        this.pythonEngine = Objects.requireNonNull(pythonEngine, "pythonEngine");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.packageComparator = Objects.requireNonNull(packageComparator, "packageComparator");
        this.slideComparator = Objects.requireNonNull(slideComparator, "slideComparator");
    }

    @Override
    public EngineResult init(
            Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
        // Init is Java-only: dual-running Python would create a second project tree.
        return javaEngine.init(sourcePptx, name, projectDir, projectsRoot, timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        EngineResult javaResult = javaEngine.validate(project, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose("validate", () -> {
                EngineResult pythonResult = pythonEngine.validate(project, timeout);
                log.info(
                        "native-enhance dual-run validate javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
            });
        }
        return javaResult;
    }

    @Override
    public EngineResult apply(List<String> arguments, Duration timeout) {
        EngineResult javaResult = javaEngine.apply(arguments, timeout);
        if (!javaResult.successful() || !properties.isDualRunEnabled()) {
            return javaResult;
        }
        Path project = extractProject(arguments);
        if (project == null) {
            return javaResult;
        }
        Path javaOut = resolveDefaultOutput(project, arguments);
        if (javaOut == null || !Files.isRegularFile(javaOut)) {
            log.info("native-enhance dual-run skipped: java output not found for {}", project);
            return javaResult;
        }
        try {
            Path dualDir = Files.createTempDirectory(javaOut.getParent(), "dual-run-python-native-");
            Path pythonOut = dualDir.resolve(javaOut.getFileName().toString());
            List<String> pythonArgs = withOutput(arguments, pythonOut);
            EngineResult pythonResult = runSafely(() -> pythonEngine.apply(pythonArgs, timeout), "python-apply");
            if (!pythonResult.successful()) {
                log.warn(
                        "native-enhance dual-run python apply failed for {}: {}",
                        project,
                        pythonResult.output());
                return javaResult;
            }
            PackageXmlComparator.Result packageResult = packageComparator.compare(javaOut, pythonOut);
            SlideXmlComparator.Result slideResult = slideComparator.compare(javaOut, pythonOut);
            if (!packageResult.equal() || !slideResult.equal()) {
                String summary = "native-enhance dual-run XML mismatch for "
                        + javaOut.getFileName()
                        + ": packageDiffs="
                        + packageResult.diffs().size()
                        + " slideDiffs="
                        + slideResult.diffs().size();
                if (properties.isDualRunFailOnMismatch()) {
                    throw new IllegalStateException(summary);
                }
                log.warn(summary);
            } else {
                log.info("native-enhance dual-run package/slide XML match for {}", javaOut.getFileName());
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("native-enhance dual-run compare failed for {}; java remains authority", project, ex);
        }
        return javaResult;
    }

    private void dualRunDiagnose(String stage, DualCall call) {
        try {
            call.run();
        } catch (Exception ex) {
            log.warn("native-enhance dual-run {} diagnostic failed; java remains authority", stage, ex);
        }
    }

    private static EngineResult runSafely(EngineCall call, String label) {
        try {
            return call.run();
        } catch (RuntimeException ex) {
            log.warn("native-enhance {} threw; continuing with java authority", label, ex);
            return new EngineResult(1, "[" + label + "] failed: " + ex.getMessage(), false);
        }
    }

    private static Path extractProject(List<String> arguments) {
        if (arguments == null) {
            return null;
        }
        for (int i = 0; i < arguments.size(); i++) {
            String arg = arguments.get(i);
            if ("apply".equals(arg) && i + 1 < arguments.size()) {
                return Path.of(arguments.get(i + 1));
            }
        }
        return null;
    }

    private static Path resolveDefaultOutput(Path project, List<String> arguments) {
        for (int i = 0; i < arguments.size(); i++) {
            String arg = arguments.get(i);
            if (("--output".equals(arg) || "-o".equals(arg)) && i + 1 < arguments.size()) {
                return Path.of(arguments.get(i + 1));
            }
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var projectJson = mapper.readTree(project.resolve("project.json").toFile());
            Path source = project.resolve(projectJson.path("source_pptx").asText());
            Path exports = project.resolve(projectJson.path("exports_dir").asText("exports"));
            String stem = source.getFileName().toString();
            if (stem.endsWith(".pptx")) {
                stem = stem.substring(0, stem.length() - 5);
            }
            return exports.resolve(stem + "_enhanced.pptx");
        } catch (Exception ex) {
            return null;
        }
    }

    private static List<String> withOutput(List<String> arguments, Path output) {
        List<String> copy = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String arg = arguments.get(i);
            if ("--output".equals(arg) || "-o".equals(arg)) {
                if (i + 1 < arguments.size()) {
                    i++;
                }
                continue;
            }
            copy.add(arg);
        }
        copy.add("--output");
        copy.add(output.toString());
        if (!copy.contains("--overwrite")) {
            copy.add("--overwrite");
        }
        return copy;
    }

    @FunctionalInterface
    private interface EngineCall {
        EngineResult run();
    }

    @FunctionalInterface
    private interface DualCall {
        void run() throws Exception;
    }
}
