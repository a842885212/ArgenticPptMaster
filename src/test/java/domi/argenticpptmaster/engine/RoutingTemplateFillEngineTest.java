package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptEngineProperties;
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
    void analyzeCheckPlanValidateUseJavaAuthorityByDefault() {
        AtomicInteger javaCalls = new AtomicInteger();
        AtomicInteger pythonCalls = new AtomicInteger();
        TemplateFillEngine java = countingEngine(javaCalls, new EngineResult(0, "java", false));
        TemplateFillEngine python = countingEngine(pythonCalls, new EngineResult(0, "python", false));

        RoutingTemplateFillEngine routing =
                new RoutingTemplateFillEngine(java, python, PptEngineProperties.defaults());
        assertThat(routing.analyze(tempDir, tempDir.resolve("lib.json"), Duration.ZERO).output())
                .isEqualTo("java");
        assertThat(routing.checkPlan(tempDir, tempDir, tempDir.resolve("r.json"), Duration.ZERO).output())
                .isEqualTo("java");
        assertThat(routing.validate(tempDir, Duration.ZERO).output()).isEqualTo("java");
        assertThat(javaCalls.get()).isEqualTo(3);
        assertThat(pythonCalls.get()).isZero();
    }

    @Test
    void returnsJavaApplyEvenWhenPythonWouldSucceed() throws Exception {
        Path export = tempDir.resolve("exports/out.pptx");
        Files.createDirectories(export.getParent());

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
                try {
                    Files.writeString(exportOut, "java-pptx");
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                return new EngineResult(0, "java-apply", false);
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                return new EngineResult(0, "j", false);
            }
        };
        TemplateFillEngine python = countingEngine(new AtomicInteger(), new EngineResult(0, "python-apply", false));

        EngineResult result = new RoutingTemplateFillEngine(java, python, PptEngineProperties.defaults())
                .apply(tempDir.resolve("t.pptx"), tempDir.resolve("plan.json"), export, Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("java-apply");
        assertThat(Files.readString(export)).isEqualTo("java-pptx");
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
