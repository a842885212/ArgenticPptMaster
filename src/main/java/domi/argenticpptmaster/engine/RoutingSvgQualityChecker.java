package domi.argenticpptmaster.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dual-runs the Java SVG quality subset and the Python checker, returning the Python
 * result as the production authority.
 *
 * <p>Java must not alone gate finalize/export until it covers every blocking rule the
 * service path exercises in ppt-master. Until then Python decides exit success; Java
 * findings are logged for dual-run drift detection.</p>
 */
public final class RoutingSvgQualityChecker implements SvgQualityChecker {

    private static final Logger log = LoggerFactory.getLogger(RoutingSvgQualityChecker.class);

    private final SvgQualityChecker javaChecker;
    private final SvgQualityChecker pythonChecker;

    public RoutingSvgQualityChecker(SvgQualityChecker javaChecker, SvgQualityChecker pythonChecker) {
        this.javaChecker = Objects.requireNonNull(javaChecker, "javaChecker");
        this.pythonChecker = Objects.requireNonNull(pythonChecker, "pythonChecker");
    }

    @Override
    public EngineResult check(Path project, String format, Duration timeout) {
        EngineResult javaResult = runSafely(javaChecker, project, format, timeout, "java");
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
                    "svg quality dual-run both failed for {} format={} (python is authority)",
                    project,
                    format);
        }

        return pythonResult;
    }

    private static EngineResult runSafely(
            SvgQualityChecker checker,
            Path project,
            String format,
            Duration timeout,
            String label) {
        try {
            return checker.check(project, format, timeout);
        } catch (RuntimeException ex) {
            log.warn("svg quality {} checker threw; continuing with python authority", label, ex);
            return new EngineResult(1, "[" + label + "] checker failed: " + ex.getMessage(), false);
        }
    }
}
