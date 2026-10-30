package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingSvgQualityCheckerTest {

    @TempDir
    Path tempDir;

    @Test
    void usesJavaAuthorityByDefaultWithoutCallingPython() {
        AtomicInteger javaCalls = new AtomicInteger();
        AtomicInteger pythonCalls = new AtomicInteger();
        SvgQualityChecker java = (project, format, timeout) -> {
            javaCalls.incrementAndGet();
            return new EngineResult(0, "[JAVA] ok", false);
        };
        SvgQualityChecker python = (project, format, timeout) -> {
            pythonCalls.incrementAndGet();
            return new EngineResult(1, "[PYTHON] fail", false);
        };

        EngineResult result = new RoutingSvgQualityChecker(java, python, PptEngineProperties.defaults())
                .check(tempDir, "ppt169", Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("[JAVA] ok");
        assertThat(javaCalls.get()).isEqualTo(1);
        assertThat(pythonCalls.get()).isZero();
    }

    @Test
    void returnsJavaFailureEvenWhenPythonWouldPass() {
        SvgQualityChecker java = (project, format, timeout) -> new EngineResult(1, "[JAVA] fail", false);
        SvgQualityChecker python = (project, format, timeout) -> new EngineResult(0, "[PYTHON] ok", false);

        EngineResult result = new RoutingSvgQualityChecker(java, python, PptEngineProperties.defaults())
                .check(tempDir, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("[JAVA] fail");
    }

    @Test
    void dualRunCallsPythonButKeepsJavaAuthority() {
        AtomicInteger pythonCalls = new AtomicInteger();
        SvgQualityChecker java = (project, format, timeout) -> new EngineResult(0, "[JAVA] authority", false);
        SvgQualityChecker python = (project, format, timeout) -> {
            pythonCalls.incrementAndGet();
            return new EngineResult(1, "[PYTHON] fail", false);
        };

        EngineResult result =
                new RoutingSvgQualityChecker(java, python, new PptEngineProperties(true, false, false))
                        .check(tempDir, "ppt169", Duration.ZERO);

        assertThat(result.output()).isEqualTo("[JAVA] authority");
        assertThat(result.successful()).isTrue();
        assertThat(pythonCalls.get()).isEqualTo(1);
    }

    @Test
    void dualRunPythonThrowDoesNotReplaceJavaResult() {
        SvgQualityChecker java = (project, format, timeout) -> new EngineResult(0, "[JAVA] ok", false);
        SvgQualityChecker python = (project, format, timeout) -> {
            throw new IllegalStateException("boom");
        };

        EngineResult result =
                new RoutingSvgQualityChecker(java, python, new PptEngineProperties(true, false, false))
                        .check(tempDir, "ppt169", Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("[JAVA] ok");
    }
}
