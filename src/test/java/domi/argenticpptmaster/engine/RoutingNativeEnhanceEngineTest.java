package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;

import domi.argenticpptmaster.config.PptEngineProperties;
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
    void returnsJavaResultEvenWhenPythonWouldSucceed() {
        AtomicInteger pythonCalls = new AtomicInteger();
        NativeEnhanceEngine java = fixedEngine("java-apply", true);
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
                pythonCalls.incrementAndGet();
                return new EngineResult(0, "python-apply", false);
            }
        };

        EngineResult result = new RoutingNativeEnhanceEngine(java, python, PptEngineProperties.defaults())
                .apply(List.of("apply", tempDir.toString(), "--overwrite"), Duration.ZERO);

        assertThat(result.successful()).isTrue();
        assertThat(result.output()).isEqualTo("java-apply");
        assertThat(pythonCalls.get()).isZero();
    }

    @Test
    void javaFailureIsNotCoveredByPython() {
        NativeEnhanceEngine java = fixedEngine("java-failed", false);
        NativeEnhanceEngine python = fixedEngine("python-ok", true);

        EngineResult result = new RoutingNativeEnhanceEngine(java, python, PptEngineProperties.defaults())
                .apply(List.of("apply", tempDir.toString(), "--overwrite"), Duration.ZERO);

        assertThat(result.successful()).isFalse();
        assertThat(result.output()).isEqualTo("java-failed");
    }

    @Test
    void initUsesJavaAuthority() {
        NativeEnhanceEngine java = fixedEngine("java-init", true);
        NativeEnhanceEngine python = failingEngine();

        EngineResult result = new RoutingNativeEnhanceEngine(java, python, PptEngineProperties.defaults())
                .init(tempDir.resolve("a.pptx"), "n", tempDir, tempDir, Duration.ZERO);

        assertThat(result.output()).isEqualTo("java-init");
    }

    @Test
    void validateUsesJavaAuthorityByDefaultWithoutPython() {
        AtomicInteger pythonCalls = new AtomicInteger();
        NativeEnhanceEngine java = fixedEngine("java-validate", true);
        NativeEnhanceEngine python = new NativeEnhanceEngine() {
            @Override
            public EngineResult init(Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
                return new EngineResult(0, "p", false);
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                pythonCalls.incrementAndGet();
                return new EngineResult(0, "python-validate", false);
            }

            @Override
            public EngineResult apply(List<String> arguments, Duration timeout) {
                return new EngineResult(0, "p", false);
            }
        };

        EngineResult result = new RoutingNativeEnhanceEngine(java, python, PptEngineProperties.defaults())
                .validate(tempDir, Duration.ZERO);

        assertThat(result.output()).isEqualTo("java-validate");
        assertThat(pythonCalls.get()).isZero();
    }

    private static NativeEnhanceEngine fixedEngine(String output, boolean success) {
        return new NativeEnhanceEngine() {
            @Override
            public EngineResult init(Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
                return new EngineResult(success ? 0 : 1, output, false);
            }

            @Override
            public EngineResult validate(Path project, Duration timeout) {
                return new EngineResult(success ? 0 : 1, output, false);
            }

            @Override
            public EngineResult apply(List<String> arguments, Duration timeout) {
                return new EngineResult(success ? 0 : 1, output, false);
            }
        };
    }

    private static NativeEnhanceEngine failingEngine() {
        return new NativeEnhanceEngine() {
            @Override
            public EngineResult init(Path sourcePptx, String name, Path projectDir, Path projectsRoot, Duration timeout) {
                throw new IllegalStateException("python should not run init");
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
