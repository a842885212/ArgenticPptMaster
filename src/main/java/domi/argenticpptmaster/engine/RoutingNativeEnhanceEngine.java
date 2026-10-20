package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.engine.compare.PackageXmlComparator;
import domi.argenticpptmaster.engine.compare.SlideXmlComparator;
import domi.argenticpptmaster.engine.file.JavaNativeEnhanceEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dual-runs the Java native-enhance notes subset beside Python, returning the Python
 * result as the production authority.
 *
 * <p>Java must not alone gate apply/validate until notes (and later audio/timings/transitions)
 * produce slide/notes XML aligned with the pinned ppt-master converter. Until then Python
 * decides success; Java findings are logged for dual-run drift detection.</p>
 */
public final class RoutingNativeEnhanceEngine implements NativeEnhanceEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingNativeEnhanceEngine.class);

    private final NativeEnhanceEngine javaEngine;
    private final NativeEnhanceEngine pythonEngine;
    private final PackageXmlComparator packageComparator;
    private final SlideXmlComparator slideComparator;

    public RoutingNativeEnhanceEngine(NativeEnhanceEngine javaEngine, NativeEnhanceEngine pythonEngine) {
        this(javaEngine, pythonEngine, new PackageXmlComparator(), new SlideXmlComparator());
    }

    public RoutingNativeEnhanceEngine(
            NativeEnhanceEngine javaEngine,
            NativeEnhanceEngine pythonEngine,
            PackageXmlComparator packageComparator,
            SlideXmlComparator slideComparator) {
        this.javaEngine = Objects.requireNonNull(javaEngine, "javaEngine");
        this.pythonEngine = Objects.requireNonNull(pythonEngine, "pythonEngine");
        this.packageComparator = Objects.requireNonNull(packageComparator, "packageComparator");
        this.slideComparator = Objects.requireNonNull(slideComparator, "slideComparator");
    }

    @Override
    public EngineResult init(
            Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
        // Java subset does not implement init — delegate straight to Python.
        return pythonEngine.init(sourcePptx, name, projectDir, projectsRoot, timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        EngineResult pythonResult = pythonEngine.validate(project, timeout);
        if (JavaNativeEnhanceEngine.isNotesOnlyEligible(project)) {
            EngineResult javaResult = runSafely(() -> javaEngine.validate(project, timeout), "java-validate");
            if (!JavaNativeEnhanceEngine.isSkip(javaResult)
                    && javaResult.successful() != pythonResult.successful()) {
                log.warn(
                        "native-enhance dual-run validate exit mismatch for {}: java exit={} python exit={}",
                        project,
                        javaResult.exitCode(),
                        pythonResult.exitCode());
            }
        }
        return pythonResult;
    }

    @Override
    public EngineResult apply(List<String> arguments, Duration timeout) {
        EngineResult pythonResult = pythonEngine.apply(arguments, timeout);
        Path project = extractProject(arguments);
        if (project == null || !JavaNativeEnhanceEngine.isNotesOnlyEligible(project)) {
            return pythonResult;
        }
        if (!pythonResult.successful()) {
            // Still attempt Java dual-run for diagnostics when Python fails, but never override.
            runSafely(() -> javaEngine.apply(arguments, timeout), "java-apply");
            return pythonResult;
        }

        Path pythonOut = resolveDefaultOutput(project, arguments);
        if (pythonOut == null || !Files.isRegularFile(pythonOut)) {
            log.info("native-enhance dual-run skipped: python output not found for {}", project);
            return pythonResult;
        }

        try {
            Path dualDir = Files.createTempDirectory(pythonOut.getParent(), "dual-run-java-native-");
            Path javaOut = dualDir.resolve(pythonOut.getFileName().toString());
            List<String> javaArgs = withOutput(arguments, javaOut);
            EngineResult javaResult = runSafely(() -> javaEngine.apply(javaArgs, timeout), "java-apply");
            if (JavaNativeEnhanceEngine.isSkip(javaResult) || !javaResult.successful()) {
                log.warn(
                        "native-enhance dual-run java apply skipped/failed for {}: {}",
                        project,
                        javaResult.output());
                return pythonResult;
            }
            PackageXmlComparator.Result packageResult = packageComparator.compare(pythonOut, javaOut);
            SlideXmlComparator.Result slideResult = slideComparator.compare(pythonOut, javaOut);
            if (!packageResult.equal() || !slideResult.equal()) {
                log.warn(
                        "native-enhance dual-run XML mismatch for {}: packageDiffs={} slideDiffs={}",
                        pythonOut.getFileName(),
                        packageResult.diffs().size(),
                        slideResult.diffs().size());
            } else {
                log.info("native-enhance dual-run package/slide XML match for {}", pythonOut.getFileName());
            }
        } catch (Exception ex) {
            log.warn("native-enhance dual-run compare failed for {}; python remains authority", project, ex);
        }
        return pythonResult;
    }

    private static EngineResult runSafely(EngineCall call, String label) {
        try {
            return call.run();
        } catch (RuntimeException ex) {
            log.warn("native-enhance {} threw; continuing with python authority", label, ex);
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
}
