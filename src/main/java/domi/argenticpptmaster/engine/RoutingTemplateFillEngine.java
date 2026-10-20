// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.config.PptEngineProperties;
import domi.argenticpptmaster.engine.compare.PackageXmlComparator;
import domi.argenticpptmaster.engine.compare.SlideXmlComparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template-fill routing with {@code JavaTemplateFillEngine} as the production authority.
 *
 * <p>When dual-run is enabled, Python runs as a secondary diagnostic only; mismatches are
 * logged (or thrown when fail-on-mismatch is set) and never replace the Java result or
 * artifact.</p>
 */
public final class RoutingTemplateFillEngine implements TemplateFillEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingTemplateFillEngine.class);

    private final TemplateFillEngine javaEngine;
    private final TemplateFillEngine pythonEngine;
    private final PptEngineProperties properties;
    private final PackageXmlComparator packageComparator;
    private final SlideXmlComparator slideComparator;

    public RoutingTemplateFillEngine(TemplateFillEngine javaEngine, TemplateFillEngine pythonEngine) {
        this(javaEngine, pythonEngine, PptEngineProperties.defaults());
    }

    public RoutingTemplateFillEngine(
            TemplateFillEngine javaEngine, TemplateFillEngine pythonEngine, PptEngineProperties properties) {
        this(javaEngine, pythonEngine, properties, new PackageXmlComparator(), new SlideXmlComparator());
    }

    public RoutingTemplateFillEngine(
            TemplateFillEngine javaEngine,
            TemplateFillEngine pythonEngine,
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
    public EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout) {
        EngineResult javaResult = javaEngine.analyze(templatePptx, slideLibraryOut, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose("analyze", () -> {
                Path dualOut = Files.createTempFile("dual-python-library-", ".json");
                EngineResult pythonResult = pythonEngine.analyze(templatePptx, dualOut, timeout);
                log.info(
                        "template-fill dual-run analyze javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
                Files.deleteIfExists(dualOut);
            });
        }
        return javaResult;
    }

    @Override
    public EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout) {
        EngineResult javaResult = javaEngine.checkPlan(slideLibrary, fillPlan, checkReportOut, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose("check-plan", () -> {
                Path dualOut = Files.createTempFile("dual-python-check-", ".json");
                EngineResult pythonResult = pythonEngine.checkPlan(slideLibrary, fillPlan, dualOut, timeout);
                log.info(
                        "template-fill dual-run check-plan javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
                Files.deleteIfExists(dualOut);
            });
        }
        return javaResult;
    }

    @Override
    public EngineResult validate(Path project, Duration timeout) {
        EngineResult javaResult = javaEngine.validate(project, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose("validate", () -> {
                EngineResult pythonResult = pythonEngine.validate(project, timeout);
                log.info(
                        "template-fill dual-run validate javaExit={} pythonExit={}",
                        javaResult.exitCode(),
                        pythonResult.exitCode());
            });
        }
        return javaResult;
    }

    @Override
    public EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout) {
        EngineResult javaResult = javaEngine.apply(templatePptx, fillPlan, exportOut, timeout);
        if (!javaResult.successful() || !properties.isDualRunEnabled()) {
            return javaResult;
        }
        Path javaPptx = resolveExport(exportOut);
        if (javaPptx == null) {
            log.info("template-fill dual-run skipped: java export not found near {}", exportOut);
            return javaResult;
        }
        try {
            Path dualDir = Files.createTempDirectory(javaPptx.getParent(), "dual-run-python-template-");
            Path pythonOut = dualDir.resolve(javaPptx.getFileName().toString());
            EngineResult pythonResult =
                    runSafely(() -> pythonEngine.apply(templatePptx, fillPlan, pythonOut, timeout), "python-apply");
            if (!pythonResult.successful()) {
                log.warn(
                        "template-fill dual-run python apply failed for {}: {}",
                        fillPlan,
                        pythonResult.output());
                return javaResult;
            }
            Path pythonPptx = resolveExport(pythonOut);
            if (pythonPptx == null || !Files.isRegularFile(pythonPptx)) {
                log.warn("template-fill dual-run python export missing for {}", fillPlan);
                return javaResult;
            }
            PackageXmlComparator.Result packageResult = packageComparator.compare(javaPptx, pythonPptx);
            SlideXmlComparator.Result slideResult = slideComparator.compare(javaPptx, pythonPptx);
            if (!packageResult.equal() || !slideResult.equal()) {
                String summary = "template-fill dual-run XML mismatch for "
                        + javaPptx.getFileName()
                        + ": packageDiffs="
                        + packageResult.diffs().size()
                        + " slideDiffs="
                        + slideResult.diffs().size();
                if (properties.isDualRunFailOnMismatch()) {
                    throw new IllegalStateException(summary);
                }
                log.warn(summary);
            } else {
                log.info("template-fill dual-run package/slide XML match for {}", javaPptx.getFileName());
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("template-fill dual-run compare failed for {}; java remains authority", fillPlan, ex);
        }
        return javaResult;
    }

    private static Path resolveExport(Path exportOut) {
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
        String stem = exportOut.getFileName().toString();
        if (stem.toLowerCase().endsWith(".pptx")) {
            stem = stem.substring(0, stem.length() - 5);
        }
        final String prefix = stem;
        try (var stream = Files.list(parent)) {
            return stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".pptx");
                    })
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

    private void dualRunDiagnose(String stage, DualCall call) {
        try {
            call.run();
        } catch (Exception ex) {
            log.warn("template-fill dual-run {} diagnostic failed; java remains authority", stage, ex);
        }
    }

    private static EngineResult runSafely(EngineCall call, String label) {
        try {
            return call.run();
        } catch (RuntimeException ex) {
            log.warn("template-fill {} threw; continuing with java authority", label, ex);
            return new EngineResult(1, "[" + label + "] failed: " + ex.getMessage(), false);
        }
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
