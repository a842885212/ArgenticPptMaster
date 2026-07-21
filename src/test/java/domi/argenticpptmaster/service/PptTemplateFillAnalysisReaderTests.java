package domi.argenticpptmaster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import domi.argenticpptmaster.domain.FillPlanStatus;
import domi.argenticpptmaster.domain.TemplateFillAnalysisSummary;
import domi.argenticpptmaster.domain.TemplateFillErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PptTemplateFillAnalysisReaderTests {

    @TempDir
    Path tempDir;

    private final PptTemplateFillAnalysisReader reader = new PptTemplateFillAnalysisReader();

    @Test
    void readsValidSlideLibrarySummary() throws Exception {
        Path slideLibrary = tempDir.resolve("template.slide_library.json");
        Files.copy(getClass().getResourceAsStream("/template-fill/template.slide_library.json"), slideLibrary);

        TemplateFillAnalysisSummary summary = reader.readSummary(slideLibrary);

        assertThat(summary.templateSlideCount()).isEqualTo(2);
        assertThat(summary.analysisVersion()).isEqualTo("template_fill_pptx_library.v1");
        assertThat(summary.textSlotCount()).isEqualTo(3);
        assertThat(summary.tableCount()).isEqualTo(1);
        assertThat(summary.chartCount()).isZero();
        assertThat(summary.formatLabel()).isEqualTo("1920x1080");
    }

    @Test
    void toleratesMissingOptionalCounts() throws Exception {
        Path slideLibrary = tempDir.resolve("minimal.slide_library.json");
        Files.writeString(slideLibrary, """
                {"schema":"template_fill_pptx_library.v1","slide_count":2,"canvas_px":{"width":100,"height":100},"slides":[]}
                """);

        TemplateFillAnalysisSummary summary = reader.readSummary(slideLibrary);

        assertThat(summary.templateSlideCount()).isEqualTo(2);
        assertThat(summary.textSlotCount()).isZero();
        assertThat(summary.tableCount()).isZero();
        assertThat(summary.chartCount()).isZero();
    }

    @Test
    void failsWhenSlideLibraryMissing() {
        assertThatThrownBy(() -> reader.readSummary(tempDir.resolve("missing.json")))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .extracting(ex -> ((PptTemplateFillExecutionException) ex).errorCode())
                .isEqualTo(TemplateFillErrorCode.TEMPLATE_ANALYSIS_FAILED);
    }

    @Test
    void failsWhenRequiredFieldsMissing() throws Exception {
        Path slideLibrary = tempDir.resolve("bad.slide_library.json");
        Files.writeString(slideLibrary, "{\"schema\":\"template_fill_pptx_library.v1\"}");

        assertThatThrownBy(() -> reader.readSummary(slideLibrary))
                .isInstanceOf(PptTemplateFillExecutionException.class)
                .hasMessageContaining("slide_count");
    }

    @Test
    void derivesFillPlanStatusFromWorkspaceArtifacts() throws Exception {
        Path analysisDir = tempDir.resolve("analysis");
        Files.createDirectories(analysisDir);
        assertThat(reader.deriveFillPlanStatus(analysisDir)).isEqualTo(FillPlanStatus.NONE);

        Files.copy(getClass().getResourceAsStream("/template-fill/fill-plan-draft.json"),
                analysisDir.resolve("fill_plan.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThat(reader.deriveFillPlanStatus(analysisDir)).isEqualTo(FillPlanStatus.DRAFT);

        Files.copy(getClass().getResourceAsStream("/template-fill/fill-plan-confirmed.json"),
                analysisDir.resolve("fill_plan.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThat(reader.deriveFillPlanStatus(analysisDir)).isEqualTo(FillPlanStatus.CONFIRMED);

        Files.copy(getClass().getResourceAsStream("/template-fill/check_report.json"),
                analysisDir.resolve("check_report.json"));
        assertThat(reader.deriveFillPlanStatus(analysisDir)).isEqualTo(FillPlanStatus.VALIDATED);
    }
}
