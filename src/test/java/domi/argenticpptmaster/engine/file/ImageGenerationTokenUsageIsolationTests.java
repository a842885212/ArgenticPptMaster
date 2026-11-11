package domi.argenticpptmaster.engine.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 回归：文生图路径不得写入 chat {@code tokenUsage}。
 */
class ImageGenerationTokenUsageIsolationTests {

    @Test
    void engineImagePackagesDoNotReferenceChatTokenUsageRecording() throws Exception {
        Path engineRoot = Path.of("src/main/java/domi/argenticpptmaster/engine");
        assertThat(engineRoot).exists();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(engineRoot)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path);
                    if (source.contains("PptTokenUsage")
                            || source.contains("UsageTrackingModel")
                            || source.contains("PptTokenUsageLedgerStore")
                            || source.contains("PptTokenUsageTelemetry")
                            || source.contains("recordTokenUsage")) {
                        offenders.add(path.toString());
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
        assertThat(offenders).as("image/engine code must not record chat tokenUsage").isEmpty();
    }
}
