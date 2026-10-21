package domi.argenticpptmaster.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.config.PptEngineProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingBeautifyAnalysisEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void identifyIntakeInventoryUseJavaAuthorityByDefault() {
        AtomicInteger javaCalls = new AtomicInteger();
        AtomicInteger pythonCalls = new AtomicInteger();
        BeautifyAnalysisEngine java = counting(javaCalls, new EngineResult(0, "{\"canvas\":{\"aspect\":1.7777}}", false));
        BeautifyAnalysisEngine python = counting(pythonCalls, new EngineResult(0, "python", false));

        RoutingBeautifyAnalysisEngine routing =
                new RoutingBeautifyAnalysisEngine(java, python, PptEngineProperties.defaults());
        assertThat(routing.identify(tempDir.resolve("a.pptx"), Duration.ZERO).successful()).isTrue();
        assertThat(routing.intake(tempDir.resolve("a.pptx"), tempDir.resolve("out"), Duration.ZERO).successful())
                .isTrue();
        assertThat(routing
                        .inventory(tempDir.resolve("lib.json"), Optional.empty(), tempDir.resolve("inv.json"), Duration.ZERO)
                        .successful())
                .isTrue();
        assertThat(javaCalls.get()).isEqualTo(3);
        assertThat(pythonCalls.get()).isZero();
    }

    @Test
    void dualRunFailOnMismatchThrowsWhenIdentityDiffers() throws Exception {
        Path pptx = tempDir.resolve("deck.pptx");
        Files.writeString(pptx, "pptx");
        BeautifyAnalysisEngine java =
                counting(new AtomicInteger(), new EngineResult(0, "{\"slide_count\":1,\"source\":\"/a\"}", false));
        BeautifyAnalysisEngine python =
                counting(new AtomicInteger(), new EngineResult(0, "{\"slide_count\":2,\"source\":\"/b\"}", false));
        PptEngineProperties props = new PptEngineProperties(true, true);

        assertThatThrownBy(() -> new RoutingBeautifyAnalysisEngine(java, python, props)
                        .identify(pptx, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("beautify dual-run JSON mismatch");
    }

    @Test
    void normalizeIgnoresAbsoluteSourcePaths() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var a = mapper.readTree("{\"source\":\"/tmp/a.pptx\",\"slide_count\":1,\"nested\":{\"source_pptx\":\"/x\"}}");
        var b = mapper.readTree("{\"source\":\"/other/b.pptx\",\"slide_count\":1,\"nested\":{\"source_pptx\":\"/y\"}}");
        assertThat(RoutingBeautifyAnalysisEngine.normalize(a)).isEqualTo(RoutingBeautifyAnalysisEngine.normalize(b));
    }

    private static BeautifyAnalysisEngine counting(AtomicInteger counter, EngineResult result) {
        return new BeautifyAnalysisEngine() {
            @Override
            public EngineResult identify(Path sourcePptx, Duration timeout) {
                counter.incrementAndGet();
                return result;
            }

            @Override
            public EngineResult intake(Path sourcePptx, Path analysisOutDir, Duration timeout) {
                counter.incrementAndGet();
                return result;
            }

            @Override
            public EngineResult inventory(
                    Path slideLibrary, Optional<Path> imageManifest, Path inventoryOut, Duration timeout) {
                counter.incrementAndGet();
                return result;
            }
        };
    }
}
