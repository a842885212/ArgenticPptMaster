package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingSvgFinalizerTest {

    @TempDir
    Path tempDir;

    @Test
    void usesJavaAuthorityByDefault() {
        AtomicInteger javaCalls = new AtomicInteger();
        AtomicInteger pythonCalls = new AtomicInteger();
        SvgFinalizer java = counting(javaCalls, new EngineResult(0, "java-ok", false));
        SvgFinalizer python = counting(pythonCalls, new EngineResult(0, "python-ok", false));

        EngineResult result =
                new RoutingSvgFinalizer(java, python, PptEngineProperties.defaults())
                        .finalizeSvg(tempDir, Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("java-ok");
        assertThat(javaCalls.get()).isEqualTo(1);
        assertThat(pythonCalls.get()).isZero();
    }

    @Test
    void dualRunFailOnMismatchThrows() throws Exception {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve("svg_output"));
        Files.writeString(
                project.resolve("svg_output/a.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><text x=\"1\" y=\"2\">a</text></svg>");

        SvgFinalizer java = (p, t) -> {
            try {
                Path out = p.resolve("svg_final");
                Files.createDirectories(out);
                Files.writeString(
                        out.resolve("a.svg"),
                        "<svg xmlns=\"http://www.w3.org/2000/svg\"><text x=\"1\" y=\"2\">java</text></svg>");
                return new EngineResult(0, "java", false);
            } catch (Exception ex) {
                return new EngineResult(1, ex.getMessage(), false);
            }
        };
        SvgFinalizer python = (p, t) -> {
            try {
                Path out = p.resolve("svg_final");
                Files.createDirectories(out);
                Files.writeString(
                        out.resolve("a.svg"),
                        "<svg xmlns=\"http://www.w3.org/2000/svg\"><text x=\"1\" y=\"2\">python</text></svg>");
                return new EngineResult(0, "python", false);
            } catch (Exception ex) {
                return new EngineResult(1, ex.getMessage(), false);
            }
        };

        PptEngineProperties props = new PptEngineProperties(true, true);
        assertThatThrownBy(() -> new RoutingSvgFinalizer(java, python, props).finalizeSvg(project, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finalize-svg dual-run");
    }

    @Test
    void dualRunWarnOnlyDoesNotReplaceJavaResult() throws Exception {
        Path project = tempDir.resolve("proj2");
        Files.createDirectories(project.resolve("svg_output"));
        Files.writeString(
                project.resolve("svg_output/a.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");

        SvgFinalizer java = (p, t) -> {
            try {
                Path out = p.resolve("svg_final");
                Files.createDirectories(out);
                Files.writeString(out.resolve("a.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"java\"/>");
                return new EngineResult(0, "java-authority", false);
            } catch (Exception ex) {
                return new EngineResult(1, ex.getMessage(), false);
            }
        };
        SvgFinalizer python = (p, t) -> {
            try {
                Path out = p.resolve("svg_final");
                Files.createDirectories(out);
                Files.writeString(out.resolve("a.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"python\"/>");
                return new EngineResult(0, "python", false);
            } catch (Exception ex) {
                return new EngineResult(1, ex.getMessage(), false);
            }
        };

        EngineResult result =
                new RoutingSvgFinalizer(java, python, new PptEngineProperties(true, false))
                        .finalizeSvg(project, Duration.ZERO);
        assertThat(result.output()).isEqualTo("java-authority");
        assertThat(Files.readString(project.resolve("svg_final/a.svg"))).contains("id=\"java\"");
    }

    private static SvgFinalizer counting(AtomicInteger counter, EngineResult result) {
        return (project, timeout) -> {
            counter.incrementAndGet();
            return result;
        };
    }
}
