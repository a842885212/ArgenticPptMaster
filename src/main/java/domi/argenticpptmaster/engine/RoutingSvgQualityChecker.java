package domi.argenticpptmaster.engine;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production SVG quality checker: Java subset is the authority (including
 * {@code validation/svg_quality_report.json}).
 *
 * <p>When {@code ppt-master.engine.dual-run-enabled=true}, Python runs as secondary diagnostics
 * only; exit-code drift is logged and never replaces the Java result.</p>
 */
public final class RoutingSvgQualityChecker implements SvgQualityChecker {

    private static final Logger log = LoggerFactory.getLogger(RoutingSvgQualityChecker.class);

    private final SvgQualityChecker javaChecker;
    private final SvgQualityChecker pythonChecker;
    private final PptEngineProperties properties;

    public RoutingSvgQualityChecker(SvgQualityChecker javaChecker, SvgQualityChecker pythonChecker) {
        this(javaChecker, pythonChecker, PptEngineProperties.defaults());
    }

    public RoutingSvgQualityChecker(
            SvgQualityChecker javaChecker,
            SvgQualityChecker pythonChecker,
            PptEngineProperties properties) {
        this.javaChecker = Objects.requireNonNull(javaChecker, "javaChecker");
        this.pythonChecker = Objects.requireNonNull(pythonChecker, "pythonChecker");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public EngineResult check(Path project, String format, Duration timeout) {
        EngineResult javaResult = javaChecker.check(project, format, timeout);
        if (properties.isDualRunEnabled()) {
            dualRunDiagnose(project, format, timeout, javaResult);
        }
        return javaResult;
    }

    private void dualRunDiagnose(Path project, String format, Duration timeout, EngineResult javaResult) {
        try {
            EngineResult pythonResult = pythonChecker.check(project, format, timeout);
            if (javaResult.successful() != pythonResult.successful()) {
                log.warn(
                        "svg quality dual-run exit mismatch for {} format={}: java exit={} timedOut={} python exit={} timedOut={}",
                        project,
                        format,
                        javaResult.exitCode(),
                        javaResult.timedOut(),
                        pythonResult.exitCode(),
                        pythonResult.timedOut());
            } else if (!javaResult.successful()) {
                log.info(
                        "svg quality dual-run both failed for {} format={} (java is authority)",
                        project,
                        format);
            } else {
                log.info(
                        "svg quality dual-run both passed for {} format={} (java is authority)",
                        project,
                        format);
            }
        } catch (RuntimeException ex) {
            log.warn("svg quality dual-run diagnostic failed; java remains authority", ex);
        }
    }
}
