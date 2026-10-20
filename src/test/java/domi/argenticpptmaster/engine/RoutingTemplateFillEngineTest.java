package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingTemplateFillEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeCheckPlanValidateGoStraightToPython() {
        AtomicInteger javaCalls = new AtomicInteger();
        TemplateFillEngine java = countingEngine(javaCalls, new EngineResult(0, "java", false));
        TemplateFillEngine python = countingEngine(new AtomicInteger(), new EngineResult(0, "python", false));

        RoutingTemplateFillEngine routing = new RoutingTemplateFillEngine(java, python);
        assertThat(routing.analyze(tempDir, tempDir.resolve("lib.json"), Duration.ZERO).output())
                .isEqualTo("python");
        assertThat(routing.checkPlan(tempDir, tempDir, tempDir.resolve("r.json"), Duration.ZERO).output())
                .isEqualTo("python");
        assertThat(routing.validate(tempDir, Duration.ZERO).output()).isEqualTo("python");
        assertThat(javaCalls.get()).isZero();
    }

    @Test
    void returnsPythonApplyEvenWhenJavaThrows() throws Exception {
        Path export = tempDir.resolve("exports/out.pptx");
        Files.createDirectories(export.getParent());
        Files.writeString(export, "pptx");

        TemplateFillEngine java = new TemplateFillEngine() {
            @Override
            public EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout) {
                return new EngineResult(0, "j", false);
            }

            @Override
            public EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout) {
                return new EngineResult(0, "j", false);
            }

            @Override
            public EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout) {
                throw new IllegalStateException("java boom");
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                return new EngineResult(0, "j", false);
            }
        };
        TemplateFillEngine python = new TemplateFillEngine() {
            @Override
            public EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout) {
                return new EngineResult(0, "p", false);
            }

            @Override
            public EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout) {
                return new EngineResult(0, "p", false);
            }

            @Override
            public EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout) {
                return new EngineResult(0, "python-apply", false);
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                return new EngineResult(0, "p", false);
            }
        };

        EngineResult result = new RoutingTemplateFillEngine(java, python)
                .apply(tempDir.resolve("t.pptx"), tempDir.resolve("plan.json"), export, Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("python-apply");
    }

    private static TemplateFillEngine countingEngine(AtomicInteger counter, EngineResult result) {
        return new TemplateFillEngine() {
            @Override
            public EngineResult analyze(Path templatePptx, Path slideLibraryOut, Duration timeout) {
                counter.incrementAndGet();
                return result;
            }

            @Override
            public EngineResult checkPlan(Path slideLibrary, Path fillPlan, Path checkReportOut, Duration timeout) {
                counter.incrementAndGet();
                return result;
            }

            @Override
            public EngineResult apply(Path templatePptx, Path fillPlan, Path exportOut, Duration timeout) {
                counter.incrementAndGet();
                return result;
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                counter.incrementAndGet();
                return result;
            }
        };
    }
}
