package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingNativeEnhanceEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsPythonResultEvenWhenJavaApplyFails() {
        AtomicInteger javaCalls = new AtomicInteger();
        NativeEnhanceEngine java = new NativeEnhanceEngine() {
            @Override
            public EngineResult init(Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
                return new EngineResult(0, "java-init", false);
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                return new EngineResult(0, "java-validate", false);
            }

            @Override
            public EngineResult apply(List<String> arguments, Duration timeout) {
                javaCalls.incrementAndGet();
                throw new IllegalStateException("boom");
            }
        };
        NativeEnhanceEngine python = new NativeEnhanceEngine() {
            @Override
            public EngineResult init(Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
                return new EngineResult(0, "python-init", false);
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                return new EngineResult(0, "python-validate", false);
            }

            @Override
            public EngineResult apply(List<String> arguments, Duration timeout) {
                return new EngineResult(0, "python-apply", false);
            }
        };

        EngineResult result = new RoutingNativeEnhanceEngine(java, python)
                .apply(List.of("apply", tempDir.toString(), "--overwrite"), Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("python-apply");
    }

    @Test
    void initDelegatesStraightToPython() {
        NativeEnhanceEngine java = failingEngine();
        NativeEnhanceEngine python = new NativeEnhanceEngine() {
            @Override
            public EngineResult init(Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
                return new EngineResult(0, "python-init", false);
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                return new EngineResult(1, "unused", false);
            }

            @Override
            public EngineResult apply(List<String> arguments, Duration timeout) {
                return new EngineResult(1, "unused", false);
            }
        };

        EngineResult result = new RoutingNativeEnhanceEngine(java, python)
                .init(tempDir.resolve("a.pptx"), "n", tempDir, tempDir, Duration.ZERO);

        assertThat(result.output()).isEqualTo("python-init");
    }

    private static NativeEnhanceEngine failingEngine() {
        return new NativeEnhanceEngine() {
            @Override
            public EngineResult init(Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
                throw new IllegalStateException("java should not run init");
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                throw new IllegalStateException("unexpected");
            }

            @Override
            public EngineResult apply(List<String> arguments, Duration timeout) {
                throw new IllegalStateException("unexpected");
            }
        };
    }
}
