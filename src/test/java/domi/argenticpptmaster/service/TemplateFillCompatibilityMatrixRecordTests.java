package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Records pinned compatibility matrix artifacts against Stage 5 rollout docs. */
class TemplateFillCompatibilityMatrixRecordTests {

    @Test
    void stage4MatrixAndStage5OpsRecordExistWithRequiredCapabilities() throws Exception {
        Path stage4 = Path.of("src/test/resources/template-fill/stage4-support-matrix.md");
        Path stage5 = Path.of("docs/ops/template-fill-compatibility-matrix-stage5.md");
        Path thresholds = Path.of("docs/ops/template-fill-rollout-thresholds.md");

        assertThat(stage4).exists();
        assertThat(stage5).exists();
        assertThat(thresholds).exists();

        String matrix = Files.readString(stage4);
        assertThat(matrix).contains("Text slot", "Speaker notes", "Table cells", "Chart data", "Page transition");

        String record = Files.readString(stage5);
        assertThat(record).contains("Fixture-backed evidence", "Live rollout metrics", "insufficient sample");

        String thresholdDoc = Files.readString(thresholds);
        assertThat(thresholdDoc).contains("Block rollout", "Analyze P95", "Recovery failure rate");
    }
}
