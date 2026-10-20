package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.engine.compare.PackageXmlComparator;
import domi.argenticpptmaster.engine.compare.SlideXmlComparator;
import domi.argenticpptmaster.engine.file.JavaTemplateFillEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dual-runs the Java template-fill notes+text subset beside Python, returning the Python
 * result as the production authority.
 *
 * <p>analyze / check-plan / validate are not ported and go straight to Python.
 * Java apply dual-runs only when it produces an output; mismatches warn and never
 * change the production result.</p>
 */
public final class RoutingTemplateFillEngine implements TemplateFillEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingTemplateFillEngine.class);

    private final TemplateFillEngine javaEngine;
    private final TemplateFillEngine pythonEngine;
    private final PackageXmlComparator packageComparator;
    private final SlideXmlComparator slideComparator;

    public RoutingTemplateFillEngine(TemplateFillEngine javaEngine, TemplateFillEngine pythonEngine) {
        this(javaEngine, pythonEngine, new PackageXmlComparator(), new SlideXmlComparator());
    }

    public RoutingTemplateFillEngine(
            TemplateFillEngine javaEngine,
            TemplateFillEngine pythonEngine,
            PackageXmlComparator packageComparator,
            SlideXmlComparator slideComparator) {
        this.javaEngine = Objects.requireNonNull(javaEngine, "javaEngine");
        this.pythonEngine = Objects.requireNonNull(pythonEngine, "pythonEngine");
        this.packageComparator = Objects.requireNonNull(packageComparator, "packageComparator");
        this.slideComparator = Objects.requireNonNull(slideComparator, "slideComparator");
    }

    @Override
    public EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout) {
        return pythonEngine.analyze(templatePptx, slideLibraryOut, timeout);
    }

    @Override
    public EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout) {
        return pythonEngine.checkPlan(slideLibrary, fillPlan, checkReportOut, timeout);
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        return pythonEngine.validate(project, timeout);
    }

    @Override
    public EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout) {
        EngineResult pythonResult = pythonEngine.apply(templatePptx, fillPlan, exportOut, timeout);
        if (!pythonResult.successful()) {
            return pythonResult;
        }
        Path pythonPptx = resolvePythonExport(exportOut);
        if (pythonPptx == null) {
            log.info("template-fill dual-run skipped: python export not found near {}", exportOut);
            return pythonResult;
        }
        try {
            Path dualDir = Files.createTempDirectory(pythonPptx.getParent(), "dual-run-java-template-");
            Path javaOut = dualDir.resolve(pythonPptx.getFileName().toString());
            EngineResult javaResult =
                    runSafely(() -> javaEngine.apply(templatePptx, fillPlan, javaOut, timeout), "java-apply");
            if (JavaTemplateFillEngine.isSkip(javaResult)) {
                log.info("template-fill dual-run java skipped for {}: {}", fillPlan, javaResult.output());
                return pythonResult;
            }
            if (!javaResult.successful() || !Files.isRegularFile(javaOut)) {
                log.warn(
                        "template-fill dual-run java apply failed for {}: {}",
                        fillPlan,
                        javaResult.output());
                return pythonResult;
            }
            PackageXmlComparator.Result packageResult = packageComparator.compare(pythonPptx, javaOut);
            SlideXmlComparator.Result slideResult = slideComparator.compare(pythonPptx, javaOut);
            if (!packageResult.equal() || !slideResult.equal()) {
                log.warn(
                        "template-fill dual-run XML mismatch for {}: packageDiffs={} slideDiffs={}",
                        pythonPptx.getFileName(),
                        packageResult.diffs().size(),
                        slideResult.diffs().size());
            } else {
                log.info("template-fill dual-run package/slide XML match for {}", pythonPptx.getFileName());
            }
        } catch (Exception ex) {
            log.warn("template-fill dual-run compare failed for {}; python remains authority", fillPlan, ex);
        }
        return pythonResult;
    }

    private static Path resolvePythonExport(Path exportOut) {
        if (exportOut == null) {
            return null;
        }
        if (Files.isRegularFile(exportOut)) {
            return exportOut;
        }
        Path parent = exportOut.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }
        try (var stream = Files.list(parent)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".pptx"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (Exception ex) {
                            return 0;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private static EngineResult runSafely(EngineCall call, String label) {
        try {
            return call.run();
        } catch (RuntimeException ex) {
            log.warn("template-fill {} threw; continuing with python authority", label, ex);
            return new EngineResult(1, "[" + label + "] failed: " + ex.getMessage(), false);
        }
    }

    @FunctionalInterface
    private interface EngineCall {
        EngineResult run();
    }
}
