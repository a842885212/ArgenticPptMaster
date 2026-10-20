package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingSvgQualityCheckerTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsPythonResultEvenWhenJavaFails() {
        AtomicInteger javaCalls = new AtomicInteger();
        AtomicInteger pythonCalls = new AtomicInteger();
        SvgQualityChecker java = (project, format, timeout) -> {
            javaCalls.incrementAndGet();
            return new EngineResult(1, "[JAVA] fail", false);
        };
        SvgQualityChecker python = (project, format, timeout) -> {
            pythonCalls.incrementAndGet();
            return new EngineResult(0, "[PYTHON] ok", false);
        };

        EngineResult result = new RoutingSvgQualityChecker(java, python)
                .check(tempDir, "ppt169", Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("[PYTHON] ok");
        assertThat(javaCalls.get()).isEqualTo(1);
        assertThat(pythonCalls.get()).isEqualTo(1);
    }

    @Test
    void returnsPythonFailureWhenPythonFailsAndJavaPasses() {
        SvgQualityChecker java = (project, format, timeout) -> new EngineResult(0, "[JAVA] ok", false);
        SvgQualityChecker python = (project, format, timeout) -> new EngineResult(1, "[PYTHON] fail", false);

        EngineResult result = new RoutingSvgQualityChecker(java, python)
                .check(tempDir, "ppt169", Duration.ZERO);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).isEqualTo("[PYTHON] fail");
    }

    @Test
    void continuesWithPythonWhenJavaThrows() {
        SvgQualityChecker java = (project, format, timeout) -> {
            throw new IllegalStateException("boom");
        };
        SvgQualityChecker python = (project, format, timeout) -> new EngineResult(0, "[PYTHON] ok", false);

        EngineResult result = new RoutingSvgQualityChecker(java, python)
                .check(tempDir, "ppt169", Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("[PYTHON] ok");
    }
}
